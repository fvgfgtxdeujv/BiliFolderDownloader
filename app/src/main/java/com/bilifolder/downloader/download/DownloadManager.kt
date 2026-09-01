package com.bilifolder.downloader.download

import com.bilifolder.downloader.data.BiliApiClient
import com.bilifolder.downloader.data.CookieStore
import com.bilifolder.downloader.data.DownloadRecordStore
import com.bilifolder.downloader.data.NetworkMonitor
import com.bilifolder.downloader.data.StorageManager
import com.bilifolder.downloader.data.WebDavClient
import com.bilifolder.downloader.data.model.DownloadEngineType
import com.bilifolder.downloader.data.model.EngineStatus
import com.bilifolder.downloader.data.model.EngineTask
import com.bilifolder.downloader.data.model.Folder
import com.bilifolder.downloader.data.model.TaskHistory
import com.bilifolder.downloader.data.model.VideoInfo
import com.bilifolder.downloader.engine.DownloadEngine
import com.bilifolder.downloader.engine.GopeedEngine
import com.bilifolder.downloader.engine.OkHttpDownloadEngine
import com.bilifolder.downloader.media.Mp4Muxer
import com.bilifolder.downloader.media.PlaybackVerifier
import com.bilifolder.downloader.media.ZipHelper
import com.bilifolder.downloader.util.LogUtil
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 下载调度器（设计 4.6，需求 4-10、14-16、19、21）。
 *
 * 对应桌面版 `_download_worker`（`1.py#L950`）与 `_process_folder`（`1.py#L1064`）：
 * 1. 校验 Cookie 与下载目录；引擎可用性回退（需求 19）
 * 2. 按收藏夹分页拉取视频（翻页间隔 1.5s），过滤已下载（bvid 记录 + 文件名索引）与用户勾选子集
 * 3. 逐视频：取播放地址 → 引擎下载（视频/音频并行）→ Mp4Muxer 合并 → PlaybackVerifier 校验
 *    → 可选删除源视频 → 记录 bvid；失败按重试次数递增间隔重试（5s、15s）
 * 4. 视频间间隔 2s，收藏夹间间隔 3s（需求 5 反风控）
 * 5. 全部完成：ZipHelper 打包 → 可选 WebDAV 自动上传（需求 9、21）
 *
 * 网络暂停恢复（需求 15）：`仅 WiFi 模式 + 蜂窝网络` 时暂停当前任务并等待 WiFi。
 * 通过 [DownloadEvent] SharedFlow 上报日志/进度/完成事件驱动 UI。
 */
class DownloadManager(
    private val biliApiClient: BiliApiClient,
    private val recordStore: DownloadRecordStore,
    private val cookieStore: CookieStore,
    private val networkMonitor: NetworkMonitor,
    private val storageManager: StorageManager,
    private val okHttpEngine: OkHttpDownloadEngine,
    private val gopeedEngine: GopeedEngine?,
    private val webDavClient: WebDavClient = WebDavClient(),
) {

    /** 下载任务请求：一个收藏夹 + 用户勾选 bvid 子集（null = 全部） */
    data class DownloadRequest(
        val folder: Folder,
        val selectedBvids: Set<String>? = null,
    )

    sealed interface DownloadEvent {
        data class Log(val line: String) : DownloadEvent
        data class Progress(val done: Int, val total: Int, val currentTitle: String?) : DownloadEvent
        data class Finished(
            val folderName: String,
            val total: Int,
            val success: Int,
            val failed: Int,
            val skipped: Int,
            val zipPath: String?,
        ) : DownloadEvent
    }

    private val _events = MutableSharedFlow<DownloadEvent>(replay = 200, extraBufferCapacity = 128)
    val events: SharedFlow<DownloadEvent> = _events.asSharedFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var running = false

    @Volatile
    private var stopFlag = false

    @Volatile
    private var currentEngine: DownloadEngine? = null

    val isRunning: Boolean get() = running

    /** 启动下载；已运行时不重复启动 */
    fun start(requests: List<DownloadRequest>) {
        LogUtil.d(TAG, "start: 请求 ${requests.size} 个收藏夹")
        if (running || requests.isEmpty()) {
            LogUtil.w(TAG, "start: 已运行或请求为空，忽略")
            return
        }
        if (cookieStore.biliJct().isNullOrEmpty()) {
            LogUtil.w(TAG, "start: 未登录，无法下载")
            emit(DownloadEvent.Log("未登录，无法下载"))
            return
        }
        if (!storageManager.isDownloadDirWritable()) {
            LogUtil.w(TAG, "start: 下载目录不可写")
            emit(DownloadEvent.Log("下载目录不可写，请检查存储空间"))
            return
        }
        running = true
        stopFlag = false
        scope.launch { run(requests) }
    }

    /** 请求停止：置停止标记，协程安全退出，保留已完成文件 */
    fun stop() {
        LogUtil.d(TAG, "stop: 请求停止")
        stopFlag = true
    }

    // ---------- 主流程 ----------

    private suspend fun run(requests: List<DownloadRequest>) {
        var engine = resolveEngine()
        var totalDone = 0
        var totalSuccess = 0
        var totalFailed = 0
        var totalSkipped = 0
        val firstFolderName = requests.first().folder.title

        try {
            requests.forEachIndexed { index, request ->
                if (stopFlag) return@forEachIndexed
                LogUtil.d(TAG, "run: 开始处理收藏夹「${request.folder.title}」(${index + 1}/${requests.size})")
                emit(DownloadEvent.Log("开始处理收藏夹：${request.folder.title}"))
                val result = processFolder(request, engine)
                totalDone += result.processed
                totalSuccess += result.success
                totalFailed += result.failed
                totalSkipped += result.skipped
                emit(DownloadEvent.Progress(totalDone, -1, null))

                // 收藏夹间间隔 3s（需求 5 第 3 条）
                if (index < requests.size - 1) {
                    emit(DownloadEvent.Log("等待 3 秒后处理下一个收藏夹…"))
                    delay(3000)
                }
            }

            if (!stopFlag) {
                // 全部完成：打包 + 自动上传（需求 9、21）
                LogUtil.d(TAG, "run: 全部收藏夹处理完成，开始打包 zip")
                emit(DownloadEvent.Log("全部收藏夹处理完成，开始打包 zip…"))
                val zipResult = ZipHelper.zipDirectory(storageManager.downloadDir)
                val zipPath = zipResult.zipFile?.absolutePath
                if (zipResult.error != null) {
                    emit(DownloadEvent.Log("打包失败：${zipResult.error}"))
                } else if (zipPath != null) {
                    emit(DownloadEvent.Log("打包完成：${zipResult.zipFile?.name}，共 ${zipResult.fileCount} 个文件"))
                    uploadZipIfEnabled(zipResult.zipFile!!, firstFolderName)
                }

                recordStore.addTaskHistory(
                    TaskHistory(
                        timestamp = System.currentTimeMillis(),
                        folderName = firstFolderName,
                        total = totalDone,
                        success = totalSuccess,
                        failed = totalFailed,
                        zipPath = zipPath,
                        folder = requests.firstOrNull()?.folder,
                    )
                )
            }
        } finally {
            LogUtil.d(TAG, "run: 结束，success=$totalSuccess failed=$totalFailed skipped=$totalSkipped")
            running = false
            stopFlag = false
            currentEngine = null
            emit(DownloadEvent.Finished(firstFolderName, totalDone, totalSuccess, totalFailed, totalSkipped, null))
        }
    }

    /** 引擎选择与回退（需求 19）：Gopeed 不可用时回退内置并提示 */
    private suspend fun resolveEngine(): DownloadEngine {
        val preferred = recordStore.engineType.first()
        LogUtil.d(TAG, "resolveEngine: 首选引擎 $preferred")
        if (preferred == DownloadEngineType.GOPEED) {
            val gopeed = gopeedEngine
            if (gopeed != null && gopeed.isAvailable()) {
                LogUtil.d(TAG, "resolveEngine: 使用 Gopeed 引擎")
                emit(DownloadEvent.Log("使用 Gopeed 引擎（多线程下载）"))
                currentEngine = gopeed
                return gopeed
            }
            LogUtil.w(TAG, "resolveEngine: Gopeed 不可用，回退内置下载器")
            emit(DownloadEvent.Log("未检测到 Gopeed 二进制，已回退内置下载器"))
        } else {
            LogUtil.d(TAG, "resolveEngine: 使用内置下载器")
            emit(DownloadEvent.Log("使用内置下载器"))
        }
        currentEngine = okHttpEngine
        return okHttpEngine
    }

    // ---------- 收藏夹处理 ----------

    private suspend fun processFolder(
        request: DownloadRequest,
        engine: DownloadEngine,
    ): FolderResult {
        val folder = request.folder
        // 分页拉取全部视频（翻页间隔 1.5s，需求 5 第 4 条）
        val videos = mutableListOf<VideoInfo>()
        var page = 1
        var mediaCount = folder.mediaCount
        LogUtil.d(TAG, "processFolder: 「${folder.title}」开始分页拉取")
        while (!stopFlag) {
            emit(DownloadEvent.Log("拉取第 $page 页视频…"))
            val result = biliApiClient.getFolderVideos(folder.mediaId, page)
            if (result.error != null) {
                LogUtil.w(TAG, "processFolder: 「${folder.title}」第 $page 页失败: ${result.error}")
                emit(DownloadEvent.Log("分页失败：${result.error}，跳过该收藏夹"))
                return FolderResult(processed = videos.size, 0, 0, videos.size)
            }
            if (page == 1 && result.totalCount > 0) mediaCount = result.totalCount
            videos += result.videos
            emit(DownloadEvent.Progress(0, mediaCount, null))
            if (result.videos.size < 20) break
            page += 1
            delay(1500)
        }
        LogUtil.d(TAG, "processFolder: 「${folder.title}」共拉取 ${videos.size} 条视频")
        if (videos.isEmpty()) {
            LogUtil.d(TAG, "processFolder: 「${folder.title}」无视频")
            emit(DownloadEvent.Log("收藏夹「${folder.title}」无视频"))
            return FolderResult(0, 0, 0, 0)
        }

        // 文件名索引：任务开始时 listFiles() 一次（设计 4.6.b）
        val existingFiles = storageManager.downloadDir.listFiles { f -> f.isFile }
            ?.mapTo(HashSet()) { it.nameWithoutExtension } ?: HashSet()
        val downloadedBvids = recordStore.downloadedBvids.first()

        // 过滤已下载（bvid 记录 + 文件名索引双重判断，对应 1.py#L1092-L1106）与勾选子集
        val selected = filterCandidates(
            videos = videos,
            downloadedBvids = downloadedBvids,
            existingFileNames = existingFiles,
            selectedBvids = request.selectedBvids,
            safeFileName = { storageManager.safeFileName(it) },
        )

        val skipped = videos.size - selected.size
        LogUtil.d(TAG, "processFolder: 「${folder.title}」候选 ${videos.size}，筛选后 ${selected.size}，跳过 $skipped")
        if (skipped > 0) emit(DownloadEvent.Log("跳过 ${skipped} 个已下载/未勾选视频"))
        emit(DownloadEvent.Progress(0, mediaCount, null))

        val settings = recordStore.settings.first()

        var done = 0
        var success = 0
        var failed = 0
        selected.forEachIndexed { index, video ->
            if (stopFlag) return@forEachIndexed
            emit(DownloadEvent.Progress(done, mediaCount, video.title))
            val ok = processVideo(video, folder, engine, settings)
            if (ok) success++ else failed++
            done++
            emit(DownloadEvent.Progress(done, mediaCount, video.title))

            // 视频间间隔 2s（需求 5 第 2 条）
            if (index < selected.size - 1) {
                delay(2000)
            }
        }
        emit(DownloadEvent.Log("收藏夹「${folder.title}」完成：成功 $success，失败 $failed"))
        return FolderResult(selected.size, success, failed, skipped)
    }

    // ---------- 单视频处理 ----------

    private suspend fun processVideo(
        video: VideoInfo,
        folder: Folder,
        engine: DownloadEngine,
        settings: com.bilifolder.downloader.data.DownloadSettings,
    ): Boolean {
        val safeTitle = storageManager.safeFileName(video.title)
        val videoFile = File(storageManager.downloadDir, "${safeTitle}_video.mp4")
        val audioFile = File(storageManager.downloadDir, "${safeTitle}_audio.mp4")
        val outputFile = File(storageManager.downloadDir, "$safeTitle.mp4")

        val retryCount = settings.retryCount.coerceIn(0, 5)
        LogUtil.d(TAG, "processVideo: 「${video.title}」开始，bvid=${video.bvid} cid=${video.cid} 清晰度=${settings.quality} 重试上限=$retryCount")
        for (attempt in 0..retryCount) {
            if (stopFlag) return false
            emit(DownloadEvent.Log("下载：${video.title}（第 ${attempt + 1} 次尝试）"))

            // 取播放地址（按所选清晰度）
            val play = biliApiClient.getVideoUrl(video.bvid, video.cid, settings.quality)
            if (play == null) {
                LogUtil.w(TAG, "processVideo: 「${video.title}」获取播放地址失败")
                emit(DownloadEvent.Log("获取播放地址失败：${video.title}"))
                if (attempt >= retryCount) return false
                delay(retryDelay(attempt))
                continue
            }
            if (play.needVip) {
                LogUtil.d(TAG, "processVideo: 「${video.title}」为大会员清晰度，实际取到 ${play.videoQuality}")
                emit(DownloadEvent.Log("「${video.title}」为大会员清晰度，已降级或跳过"))
            }

            // 合并失败重试时先删除残留临时文件（设计 4.6 临时文件策略）
            if (attempt > 0) {
                videoFile.delete()
                audioFile.delete()
            }

            // 下载视频流 + 音频流（并行）
            val videoOk = downloadStream(engine, play.videoUrl, videoFile, settings)
            val audioOk = if (play.audioUrl != null) {
                downloadStream(engine, play.audioUrl, audioFile, settings)
            } else {
                emit(DownloadEvent.Log("无音频流，仅视频轨"))
                true
            }
            if (stopFlag) return false
            if (!videoOk || !audioOk) {
                LogUtil.w(TAG, "processVideo: 「${video.title}」流下载失败 videoOk=$videoOk audioOk=$audioOk")
                emit(DownloadEvent.Log("下载失败：${video.title}"))
                if (attempt >= retryCount) return false
                delay(retryDelay(attempt))
                continue
            }

            // 合并
            LogUtil.d(TAG, "processVideo: 「${video.title}」合并音视频")
            emit(DownloadEvent.Log("合并音视频：${video.title}"))
            try {
                Mp4Muxer.mux(
                    videoPath = videoFile.absolutePath,
                    audioPath = if (audioOk && audioFile.exists()) audioFile.absolutePath else null,
                    outputPath = outputFile.absolutePath,
                )
            } catch (e: Exception) {
                LogUtil.e(TAG, "processVideo: 「${video.title}」合并失败", e)
                emit(DownloadEvent.Log("合并失败：${e.message}"))
                // 合并失败保留临时文件供排查，重试时删除残留（设计 4.6）
                if (attempt >= retryCount) return false
                delay(retryDelay(attempt))
                continue
            }

            // 校验
            val verifyError = PlaybackVerifier.verify(outputFile.absolutePath)
            if (verifyError != null) {
                LogUtil.w(TAG, "processVideo: 「${video.title}」校验失败: $verifyError")
                emit(DownloadEvent.Log("校验失败：${video.title}（$verifyError）"))
                if (attempt >= retryCount) return false
                delay(retryDelay(attempt))
                continue
            }

            // 成功：删除临时文件
            videoFile.delete()
            audioFile.delete()

            // 可选删除源视频（需求 7）
            if (settings.deleteAfterDownload) {
                val deleted = biliApiClient.deleteFolderVideo(folder.mediaId, video.avid)
                LogUtil.d(TAG, "processVideo: 「${video.title}」删除源视频结果=$deleted")
                emit(DownloadEvent.Log(if (deleted) "已从收藏夹删除：${video.title}" else "删除源视频失败：${video.title}"))
            }

            // 记录已下载 bvid（需求 8）
            recordStore.addDownloadedBvids(setOf(video.bvid))
            LogUtil.d(TAG, "processVideo: 「${video.title}」完成")
            emit(DownloadEvent.Log("完成：${video.title}"))
            return true
        }
        LogUtil.e(TAG, "processVideo: 「${video.title}」重试耗尽，判定失败")
        return false
    }

    /** 创建并等待单条流下载完成（含网络暂停恢复与无进展超时） */
    private suspend fun downloadStream(
        engine: DownloadEngine,
        url: String,
        file: File,
        settings: com.bilifolder.downloader.data.DownloadSettings,
    ): Boolean {
        val task: EngineTask = try {
            LogUtil.d(TAG, "downloadStream: 创建任务 ${file.name}")
            engine.download(url, file.absolutePath, file.name)
        } catch (e: Exception) {
            LogUtil.e(TAG, "downloadStream: 创建任务失败 ${file.name}", e)
            emit(DownloadEvent.Log("创建下载任务失败：${e.message}"))
            return false
        }

        var lastDownloaded = 0L
        var stallCount = 0
        while (!stopFlag) {
            // 仅 WiFi 模式下的网络等待（需求 15）
            awaitNetworkIfNeeded(settings.wifiOnly, engine, task)

            val progress = engine.query(task)
            when (progress.status) {
                EngineStatus.DONE -> {
                    LogUtil.d(TAG, "downloadStream: ${file.name} 完成，${progress.downloaded} 字节")
                    return true
                }
                EngineStatus.ERROR -> {
                    LogUtil.e(TAG, "downloadStream: ${file.name} 任务异常")
                    emit(DownloadEvent.Log("任务异常：${file.name}"))
                    return false
                }
                EngineStatus.PAUSED -> { /* 等待恢复 */ }
                else -> {
                    // 无进展超时检测：3 分钟无进展视为卡死
                    if (progress.downloaded == lastDownloaded) {
                        stallCount++
                        if (stallCount >= 180) {
                            LogUtil.w(TAG, "downloadStream: ${file.name} 3 分钟无进展，判定失败")
                            emit(DownloadEvent.Log("任务 3 分钟无进展，判定失败：${file.name}"))
                            engine.cancel(task)
                            return false
                        }
                    } else {
                        lastDownloaded = progress.downloaded
                        stallCount = 0
                    }
                }
            }
            delay(1000)
        }
        LogUtil.d(TAG, "downloadStream: ${file.name} 被停止，取消任务")
        engine.cancel(task)
        return false
    }

    /** 仅 WiFi 模式 + 非 WiFi 网络：暂停任务等待恢复（需求 15） */
    private suspend fun awaitNetworkIfNeeded(
        wifiOnly: Boolean,
        engine: DownloadEngine,
        task: EngineTask,
    ) {
        if (!wifiOnly) return
        if (networkMonitor.networkState.value == NetworkMonitor.NetworkState.WIFI) return
        LogUtil.d(TAG, "awaitNetworkIfNeeded: 移动网络，暂停 ${task.id} 等待 WiFi")
        emit(DownloadEvent.Log("当前为移动网络，任务暂停，等待 WiFi…"))
        engine.pause(task)
        while (!stopFlag && networkMonitor.networkState.value != NetworkMonitor.NetworkState.WIFI) {
            delay(2000)
        }
        if (!stopFlag) {
            LogUtil.d(TAG, "awaitNetworkIfNeeded: WiFi 已恢复，继续 ${task.id}")
            engine.resume(task)
        }
    }

    // ---------- zip 自动上传（需求 21） ----------

    private suspend fun uploadZipIfEnabled(zipFile: File, folderName: String) {
        val config = recordStore.webDavConfig.first()
        LogUtil.d(TAG, "uploadZipIfEnabled: autoUpload=${config.autoUpload} configured=${config.isConfigured()}")
        if (!config.autoUpload || !config.isConfigured()) {
            if (config.autoUpload) emit(DownloadEvent.Log("WebDAV 未配置完整，跳过自动上传"))
            return
        }
        val password = cookieStore.webDavPassword()
        if (password.isNullOrEmpty()) {
            LogUtil.w(TAG, "uploadZipIfEnabled: WebDAV 密码未设置")
            emit(DownloadEvent.Log("WebDAV 密码未设置，跳过自动上传"))
            return
        }
        LogUtil.d(TAG, "uploadZipIfEnabled: 开始自动上传 ${zipFile.name}")
        emit(DownloadEvent.Log("自动上传 zip 到 WebDAV…"))
        val remoteDir = buildUploadRemoteDir(folderName)
        val dirOk = webDavClient.mkdir(config.url, remoteDir, config.username, password)
        if (!dirOk) {
            LogUtil.w(TAG, "uploadZipIfEnabled: 目录创建失败 $remoteDir")
            emit(DownloadEvent.Log("WebDAV 目录创建失败，zip 已保留本地，可稍后重试"))
            return
        }
        val remotePath = "$remoteDir/${zipFile.name}"
        val result = webDavClient.uploadZip(config.url, remotePath, config.username, password, zipFile) { done, total ->
            emit(DownloadEvent.Log("上传进度：${done * 100 / total.coerceAtLeast(1)}%"))
        }
        LogUtil.d(TAG, "uploadZipIfEnabled: 上传结果=$result")
        when (result) {
            WebDavClient.UploadResult.OK -> emit(DownloadEvent.Log("zip 上传成功：$remotePath"))
            WebDavClient.UploadResult.AUTH -> emit(DownloadEvent.Log("WebDAV 认证失败，请检查账号配置"))
            WebDavClient.UploadResult.FAILED -> emit(DownloadEvent.Log("zip 上传失败，已保留本地，可稍后重试"))
        }
    }

    private fun buildUploadRemoteDir(folderName: String): String {
        val date = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
        val safeName = storageManager.safeFileName(folderName)
        return "bili_folder_downloader/${date}_$safeName"
    }

    /** 重试间隔递增：5s、15s、30s…（需求 5，设计 4.6.f） */
    private fun retryDelay(attempt: Int): Long {
        val seconds = when (attempt) {
            0 -> 5L
            1 -> 15L
            else -> 30L
        }
        return seconds * 1000
    }

    private fun emit(event: DownloadEvent) {
        _events.tryEmit(event)
    }

    /**
     * 候选视频过滤（纯函数，便于单元测试）：先排除已下载 bvid 与已存在文件名，
     * 再按用户勾选子集（null = 全部）收窄。
     */
    companion object {
        const val TAG = "DownloadManager"

        fun filterCandidates(
            videos: List<VideoInfo>,
            downloadedBvids: Set<String>,
            existingFileNames: Set<String>,
            selectedBvids: Set<String>?,
            safeFileName: (String) -> String,
        ): List<VideoInfo> = videos.filter { video ->
            if (video.bvid in downloadedBvids) return@filter false
            if (safeFileName(video.title) in existingFileNames) return@filter false
            selectedBvids?.let { video.bvid in it } ?: true
        }
    }

    private data class FolderResult(
        val processed: Int,
        val success: Int,
        val failed: Int,
        val skipped: Int,
    )
}
