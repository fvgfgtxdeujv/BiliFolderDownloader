package com.bilifolder.downloader.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.bilifolder.downloader.BiliApp
import com.bilifolder.downloader.MainActivity
import com.bilifolder.downloader.R
import com.bilifolder.downloader.data.model.Folder
import com.bilifolder.downloader.download.DownloadManager
import com.bilifolder.downloader.util.LogUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * 下载前台服务（设计 4.7，需求 10、11）。
 *
 * - 前台服务类型 `dataSync`（API 29+ 通用；不受 mediaProcessing 的版本限制）
 * - 常驻通知实时显示进度与当前视频标题，提供"停止"动作（ACTION_STOP）
 * - 停止时置停止标记，协程安全退出，保留已完成文件
 *
 * 通过 Intent Extra（JSON）接收下载请求：
 * - [ACTION_START_DOWNLOAD] + [EXTRA_REQUESTS]（DownloadRequestDto 列表）
 * - [ACTION_STOP]
 */
class DownloadService : Service() {

    /** 下载请求 DTO（Intent JSON 传输） */
    @Serializable
    data class DownloadRequestDto(
        val folder: Folder,
        val selected: List<String> = emptyList(),
    )

    companion object {
        const val TAG = "DownloadService"
        const val ACTION_START_DOWNLOAD = "com.bilifolder.downloader.action.START_DOWNLOAD"
        const val ACTION_STOP = "com.bilifolder.downloader.action.STOP"
        const val EXTRA_REQUESTS = "extra_requests"

        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "download_channel"

        /** 构造开始下载的 Intent */
        fun buildStartIntent(context: Context, requests: List<DownloadRequestDto>): Intent =
            Intent(context, DownloadService::class.java)
                .setAction(ACTION_START_DOWNLOAD)
                .putExtra(EXTRA_REQUESTS, Json.encodeToString(ListSerializer(DownloadRequestDto.serializer()), requests))

        /** 构造停止 Intent */
        fun buildStopIntent(context: Context): Intent =
            Intent(context, DownloadService::class.java).setAction(ACTION_STOP)
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var manager: DownloadManager

    private lateinit var notificationManager: NotificationManager

    /** 最近一次的总体进度文案，与当前文件进度拼接后展示在通知里 */
    private var overallText: String = ""

    override fun onCreate() {
        super.onCreate()
        LogUtil.d(TAG, "onCreate")
        manager = BiliApp.container.downloadManager
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
        BiliApp.container.networkMonitor.start()
        // 收集下载事件驱动通知更新
        serviceScope.launch {
            manager.events.collect { event ->
                when (event) {
                    is DownloadManager.DownloadEvent.Progress -> {
                        val title = event.currentTitle ?: ""
                        overallText = if (event.total > 0) {
                            "已处理 ${event.done}/${event.total}：$title"
                        } else {
                            title
                        }
                        updateNotification("下载中…", overallText, false, null)
                    }
                    is DownloadManager.DownloadEvent.FileProgress -> {
                        val percent = if (event.total > 0) {
                            (event.downloaded * 100 / event.total).toInt().coerceIn(0, 100)
                        } else {
                            null
                        }
                        val fileText = if (percent != null) {
                            "${event.label} $percent%"
                        } else {
                            "正在下载 ${event.label}…"
                        }
                        val text = if (overallText.isBlank()) fileText else "$overallText · $fileText"
                        updateNotification("下载中…", text, false, percent)
                    }
                    is DownloadManager.DownloadEvent.Finished -> {
                        updateNotification(
                            "下载完成",
                            "成功 ${event.success}，失败 ${event.failed}，跳过 ${event.skipped}",
                            true,
                        )
                        stopForegroundInternal()
                    }
                    else -> Unit
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        LogUtil.d(TAG, "onStartCommand: action=${intent?.action}")
        when (intent?.action) {
            ACTION_START_DOWNLOAD -> {
                val raw = intent.getStringExtra(EXTRA_REQUESTS)
                if (raw == null) {
                    LogUtil.w(TAG, "onStartCommand: 缺少请求数据")
                    stopSelf()
                    return START_NOT_STICKY
                }
                val requests = runCatching {
                    Json.decodeFromString(ListSerializer(DownloadRequestDto.serializer()), raw)
                }.getOrNull()
                if (requests.isNullOrEmpty()) {
                    LogUtil.w(TAG, "onStartCommand: 请求解析失败")
                    stopSelf()
                    return START_NOT_STICKY
                }
                LogUtil.d(TAG, "onStartCommand: 收到 ${requests.size} 个下载请求")
                val downloadRequests = requests.map {
                    DownloadManager.DownloadRequest(
                        folder = it.folder,
                        selectedBvids = it.selected.takeIf { s -> s.isNotEmpty() }?.toSet(),
                    )
                }
                startForegroundCompat()
                manager.start(downloadRequests)
            }
            ACTION_STOP -> {
                LogUtil.d(TAG, "onStartCommand: 收到停止指令")
                manager.stop()
                updateNotification("正在停止…", "任务将在当前视频结束后停止", false)
            }
            else -> Unit
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        LogUtil.d(TAG, "onDestroy")
        BiliApp.container.networkMonitor.stop()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------- 前台服务 ----------

    /**
     * 启动前台服务。
     *
     * 统一使用 `dataSync` 类型：API 29 起即可用，API 34/35 均受支持，跨系统版本稳定。
     * （`mediaProcessing` 是 API 35 新增类型，旧系统不识别会导致
     * `InvalidForegroundServiceTypeException`，故不再使用。）
     */
    private fun startForegroundCompat() {
        val notification = buildNotification("准备下载…", "", false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    // ---------- 通知 ----------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "下载进度",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "显示收藏夹下载任务的实时进度"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(
        title: String,
        text: String,
        done: Boolean,
        progressPercent: Int? = null,
    ): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            buildStopIntent(this),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            androidx.core.app.NotificationCompat.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            androidx.core.app.NotificationCompat.Builder(this)
        }
        builder
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(!done)
            .setOnlyAlertOnce(true)
            .addAction(0, "停止", stopIntent)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_LOW)
        // 有字节级进度时展示确定性进度条，否则不显示
        if (progressPercent != null) {
            builder.setProgress(100, progressPercent, false)
        }
        return builder.build()
    }

    private fun updateNotification(
        title: String,
        text: String,
        done: Boolean,
        progressPercent: Int? = null,
    ) {
        notificationManager.notify(
            NOTIFICATION_ID,
            buildNotification(title, text, done, progressPercent),
        )
    }

    private fun stopForegroundInternal() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            stopForeground(STOP_FOREGROUND_DETACH)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }
}
