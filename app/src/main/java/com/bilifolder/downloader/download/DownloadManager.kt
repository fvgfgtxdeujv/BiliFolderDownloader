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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
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
 * 5. 全部完成：默认不打包；仅开启 WebDAV 自动上传时打包并上传，远端确认后删除本地 zip（需求 9、21）
 *
 * 网络暂停恢复（需求 15）：断网时暂停任务等待网络恢复；`仅 WiFi 模式 + 蜂窝网络` 时暂停等待 WiFi。
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

        /** 当前文件（视频流/音频流）的字节级进度，驱动下载页与通知的进度条 */
        data class FileProgress(val label: String, val downloaded: Long, val total: Long) : DownloadEvent
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

    private val _running = MutableStateFlow(false)

    /** 运行状态流：UI 据此在任务结束（含主动停止）后自动返回主页面 */
    val runningState: StateFlow<Boolean> = _running.asStateFlow()

    @Volatile
    private var stopFlag = false

    @Volatile
    private var currentEngine: DownloadEngine? = null

    val isRunning: Boolean get() = _running.value

    /** 启动下载；已运行时不重复启动 */
    fun start(requests: List<DownloadRequest>) {
        LogUtil.d(TAG, "start: 请求 ${requests.size} 个收藏夹")
        if (_running.value || requests.isEmpty()) {
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
        _running.value = true
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
        // 本次任务新增的成品文件（成品保留在公共目录，zip 只打包这些）
        val producedFiles = mutableListOf<File>()

        try {
            requests.forEachIndexed { index, request ->
                if (stopFlag) return@forEachIndexed
                LogUtil.d(TAG, "run: 开始处理收藏夹「${request.folder.title}」(${index + 1}/${requests.size})")
                emit(DownloadEvent.Log("开始处理收藏夹：${request.folder.title}"))
                val result = processFolder(request, engine, producedFiles)
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
                // 全部完成：默认不打包；仅开启 WebDAV 自动上传时打包并上传（需求 9、21）
                val zipPath = packAndUploadIfEnabled(firstFolderName, producedFiles)

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
            _running.value = false
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
        produced: MutableList<File>,
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

        // 应用全局限速（0 = 不限速，单位 KB/s）
        engine.setLimit(settings.limitKbps.toLong() * 1024L)

        var done = 0
        var success = 0
        var failed = 0
        selected.forEachIndexed { index, video ->
            if (stopFlag) return@forEachIndexed
            emit(DownloadEvent.Progress(done, mediaCount, video.title))
            val ok = processVideo(video, folder, engine, settings, produced)
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
        produced: MutableList<File>,
    ): Boolean {
        val safeTitle = storageManager.safeFileName(video.title)
        // 分片写应用专属目录，成品写公共 Movies（相册/文件管理器可见）
        val videoFile = File(storageManager.tempDir, "${safeTitle}_video.mp4")
        val audioFile = File(storageManager.tempDir, "${safeTitle}_audio.mp4")
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
            val videoOk = downloadStream(engine, play.videoUrl, videoFile, settings, "视频流")
            val audioOk = if (play.audioUrl != null) {
                downloadStream(engine, play.audioUrl, audioFile, settings, "音频流")
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

            // 成功：删除临时分片，并让媒体库索引成品（相册/文件管理器立即可见）
            videoFile.delete()
            audioFile.delete()
            storageManager.scanMedia(outputFile)

            // 可选删除源视频（需求 7）
            if (settings.deleteAfterDownload) {
                val deleted = biliApiClient.deleteFolderVideo(folder.mediaId, video.avid)
                LogUtil.d(TAG, "processVideo: 「${video.title}」删除源视频结果=$deleted")
                emit(DownloadEvent.Log(if (deleted) "已从收藏夹删除：${video.title}" else "删除源视频失败：${video.title}"))
            }

            // 记录本次新增成品，供任务结束时打包 zip
            produced += outputFile

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
        label: String = file.name,
    ): Boolean {
        // 断网/仅 WiFi 不满足时，先等待网络再创建任务，避免离线创建必然失败的任务
        waitForNetwork(settings.wifiOnly)
        if (stopFlag) return false

        val task: EngineTask = try {
            LogUtil.d(TAG, "downloadStream: 创建任务 ${file.name}")
            engine.download(url, file.absolutePath, file.name)
        } catch (e: Exception) {
            LogUtil.e(TAG, "downloadStream: 创建任务失败 ${file.name}", e)
            emit(DownloadEvent.Log("创建下载任务失败：${e.message}"))
            return false
        }
        // 新流开始：先把进度条归零，避免沿用上一条流的百分比
        emit(DownloadEvent.FileProgress(label, 0, 0))

        var lastDownloaded = 0L
        var stallCount = 0
        while (!stopFlag) {
            // 网络不满足条件时暂停等待（断网 / 仅 WiFi 模式下用移动数据）
            awaitNetworkIfNeeded(settings.wifiOnly, engine, task)

            val progress = engine.query(task)
            emit(DownloadEvent.FileProgress(label, progress.downloaded, progress.total))
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

    /**
     * 网络不可用/不满足条件时暂停当前任务，恢复后自动续传（需求 15）。
     *
     * 两种情况会暂停：
     * - 完全断网（`DISCONNECTED`）：任意模式下暂停，等待网络恢复（WiFi 或移动数据均可）
     * - 仅 WiFi 模式且当前为移动网络：暂停等待切换到 WiFi
     */
    private suspend fun awaitNetworkIfNeeded(
        wifiOnly: Boolean,
        engine: DownloadEngine,
        task: EngineTask,
    ) {
        if (isNetworkReady(wifiOnly)) return
        val reason = networkPauseReason()
        LogUtil.d(TAG, "awaitNetworkIfNeeded: 暂停 ${task.id}（$reason）")
        emit(DownloadEvent.Log(reason))
        engine.pause(task)
        while (!stopFlag && !isNetworkReady(wifiOnly)) {
            delay(2000)
        }
        if (!stopFlag) {
            LogUtil.d(TAG, "awaitNetworkIfNeeded: 网络恢复，继续 ${task.id}")
            emit(DownloadEvent.Log("网络已恢复，继续下载"))
            engine.resume(task)
        }
    }

    /** 创建任务前等待网络满足条件（此时尚无引擎任务，只能等待） */
    private suspend fun waitForNetwork(wifiOnly: Boolean) {
        if (isNetworkReady(wifiOnly)) return
        emit(DownloadEvent.Log(networkPauseReason()))
        while (!stopFlag && !isNetworkReady(wifiOnly)) {
            delay(2000)
        }
    }

    /** 当前网络是否满足下载条件：非断网，且（非仅 WiFi 模式 或 已连 WiFi） */
    private fun isNetworkReady(wifiOnly: Boolean): Boolean =
        when (networkMonitor.networkState.value) {
            NetworkMonitor.NetworkState.DISCONNECTED -> false
            NetworkMonitor.NetworkState.WIFI -> true
            NetworkMonitor.NetworkState.CELLULAR -> !wifiOnly
        }

    /** 暂停原因文案 */
    private fun networkPauseReason(): String =
        if (networkMonitor.networkState.value == NetworkMonitor.NetworkState.DISCONNECTED) {
            "当前无网络，任务暂停，等待网络恢复…"
        } else {
            "当前为移动网络，任务暂停，等待 WiFi…"
        }

    // ---------- zip 打包与自动上传（需求 9、21） ----------

    /**
     * 下载完成后的收尾：默认不打包。
     *
     * 仅当用户开启 WebDAV 自动上传且配置完整时，才把本次新增成品打成 zip 并上传；
     * 上传成功后再次确认远端存在，确认到才删除本地 zip。
     *
     * @return 失败时保留在本地待重试的 zip 路径；未打包或上传成功已删除时返回 null
     */
    private suspend fun packAndUploadIfEnabled(folderName: String, produced: List<File>): String? {
        val config = recordStore.webDavConfig.first()
        if (!config.autoUpload || !config.isConfigured()) {
            LogUtil.d(TAG, "packAndUploadIfEnabled: 未开启 WebDAV 自动上传，跳过打包")
            return null
        }
        val password = cookieStore.webDavPassword()
        if (password.isNullOrEmpty()) {
            LogUtil.w(TAG, "packAndUploadIfEnabled: WebDAV 密码未设置")
            emit(DownloadEvent.Log("WebDAV 密码未设置，跳过打包上传"))
            return null
        }
        if (produced.isEmpty()) {
            LogUtil.d(TAG, "packAndUploadIfEnabled: 本次无新增成品，跳过打包")
            return null
        }

        LogUtil.d(TAG, "packAndUploadIfEnabled: 开始打包（本次新增 ${produced.size} 个）")
        emit(DownloadEvent.Log("WebDAV 已开启，开始打包 ${produced.size} 个成品…"))
        val zipResult = ZipHelper.zipFiles(
            sources = produced,
            outputDir = storageManager.zipDir,
            deleteSources = false,
        )
        val zipFile = zipResult.zipFile
        if (zipResult.error != null || zipFile == null) {
            emit(DownloadEvent.Log("打包失败：${zipResult.error ?: "未知错误"}"))
            return null
        }
        emit(DownloadEvent.Log("打包完成：${zipFile.name}，共 ${zipResult.fileCount} 个文件"))
        return uploadAndConfirm(zipFile, folderName, config.url, config.username, password)
    }

    /**
     * 上传 zip 并二次确认远端存在，确认成功才删除本地 zip。
     * @return 未确认成功时返回本地 zip 路径（保留待重试）；确认成功返回 null
     */
    private suspend fun uploadAndConfirm(
        zipFile: File,
        folderName: String,
        url: String,
        username: String,
        password: String,
    ): String? {
        val remoteDir = UPLOAD_REMOTE_DIR
        if (!webDavClient.mkdir(url, remoteDir, username, password)) {
            LogUtil.w(TAG, "uploadAndConfirm: 目录创建失败 $remoteDir")
            emit(DownloadEvent.Log("WebDAV 目录创建失败，zip 已保留本地，可稍后重试"))
            return zipFile.absolutePath
        }
        val remotePath = "$remoteDir/${buildUploadFileName(folderName)}"
        emit(DownloadEvent.Log("上传 zip 到 WebDAV…"))
        val result = webDavClient.uploadZip(url, remotePath, username, password, zipFile) { done, total ->
            emit(DownloadEvent.Log("上传进度：${done * 100 / total.coerceAtLeast(1)}%"))
        }
        LogUtil.d(TAG, "uploadAndConfirm: 上传结果=$result remote=$remotePath")
        when (result) {
            WebDavClient.UploadResult.AUTH -> {
                emit(DownloadEvent.Log("WebDAV 认证失败，请检查账号配置，zip 已保留本地"))
                return zipFile.absolutePath
            }
            WebDavClient.UploadResult.FAILED -> {
                emit(DownloadEvent.Log("zip 上传失败，已保留本地，可稍后重试"))
                return zipFile.absolutePath
            }
            WebDavClient.UploadResult.OK -> Unit
        }
        // 上传返回成功后仍需确认远端确实存在，确认到才删除本地 zip
        if (!webDavClient.exists(url, remotePath, username, password)) {
            LogUtil.w(TAG, "uploadAndConfirm: 上传后未确认到远端文件 $remotePath")
            emit(DownloadEvent.Log("已上传但未确认到远端文件，zip 已保留本地"))
            return zipFile.absolutePath
        }
        val deleted = zipFile.delete()
        LogUtil.d(TAG, "uploadAndConfirm: 远端确认成功，删除本地 zip=${zipFile.name} deleted=$deleted")
        emit(DownloadEvent.Log("zip 上传成功，已删除本地 zip（$remotePath）"))
        return null
    }

    /** 远端文件名：`<收藏夹名>_<时间戳>.zip`（时间戳为 yyyyMMdd_HHmmss） */
    private fun buildUploadFileName(folderName: String): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        return "${storageManager.safeFileName(folderName)}_$stamp.zip"
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

        /** WebDAV 上传固定目录：`bili_folder_downloader/` */
        const val UPLOAD_REMOTE_DIR = "bili_folder_downloader"

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
