package com.bilifolder.downloader.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bilifolder.downloader.BiliApp
import com.bilifolder.downloader.data.AppContainer
import com.bilifolder.downloader.data.model.DownloadEngineType
import com.bilifolder.downloader.data.DownloadSettings
import com.bilifolder.downloader.data.model.Folder
import com.bilifolder.downloader.data.model.TaskHistory
import com.bilifolder.downloader.data.model.VideoInfo
import com.bilifolder.downloader.data.model.WebDavConfig
import com.bilifolder.downloader.util.LogUtil
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 主界面状态管理：登录态、收藏夹/视频列表、设置、任务历史。
 * 下载事件由 [AppContainer.downloadManager] 的 SharedFlow 直接暴露给 UI。
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    val container: AppContainer get() = BiliApp.container

    private companion object {
        const val TAG = "MainViewModel"
    }

    // ---------- 登录态（需求 1、2） ----------

    private val _isLoggedIn = MutableStateFlow(container.cookieStore.mid() != null)
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()

    private val _lastMid = MutableStateFlow(0L)
    val lastMid: StateFlow<Long> = _lastMid.asStateFlow()

    init {
        viewModelScope.launch {
            container.recordStore.lastMid.collect { _lastMid.value = it }
        }
    }

    /** 会话过期（接口 401/登录失效）时刷新 */
    fun refreshLoginState() {
        _isLoggedIn.value = container.cookieStore.mid() != null
    }

    /**
     * 登录成功后的统一收尾（扫码 / WebView 两条路径都会调用）。
     *
     * 关键：把当前登录用户的 mid 写入 [DownloadRecordStore]，供收藏夹页自动带入并加载。
     * 优先经 `/x/web-interface/nav` 校验拿到权威 mid（同时把 DedeUserID 写回会话），
     * 网络异常时回退到已持久化的 mid。
     */
    fun onLoginSucceeded() {
        viewModelScope.launch {
            val mid = container.biliApiClient.validateAndGetMid()
                ?: container.cookieStore.mid()?.toLongOrNull()
            if (mid != null && mid > 0L) {
                LogUtil.d(TAG, "onLoginSucceeded: 登录用户 mid=$mid")
                container.recordStore.setLastMid(mid)
                // 重新登录后收藏夹缓存作废，强制下次进入重新拉取
                resetFolderCache()
            } else {
                LogUtil.w(TAG, "onLoginSucceeded: 未取得有效 mid")
            }
            refreshLoginState()
        }
    }

    /** 清空收藏夹缓存与内存列表（退出登录时调用；重新登录路径见 [onLoginSucceeded]） */
    fun clearFolderCache() {
        viewModelScope.launch { resetFolderCache() }
    }

    private suspend fun resetFolderCache() {
        container.folderCache.clear()
        _folders.value = emptyList()
        _folderVideos.value = emptyList()
        _folderMeta.value = null
        _foldersError.value = null
        _videosError.value = null
    }

    // ---------- 收藏夹（需求 3） ----------

    private val _folders = MutableStateFlow<List<Folder>>(emptyList())
    val folders: StateFlow<List<Folder>> = _folders.asStateFlow()

    private val _foldersLoading = MutableStateFlow(false)
    val foldersLoading: StateFlow<Boolean> = _foldersLoading.asStateFlow()

    private val _foldersError = MutableStateFlow<String?>(null)
    val foldersError: StateFlow<String?> = _foldersError.asStateFlow()

    /**
     * 加载收藏夹列表。
     *
     * 缓存策略：命中当日缓存直接复用且不请求网络；跨天或 [force] 时后台刷新；
     * 刷新期间若已有缓存则继续展示缓存、不显示加载态；刷新失败保留缓存并提示。
     */
    fun loadFolders(mid: Long, force: Boolean = false) {
        viewModelScope.launch {
            // 无论接口结果如何都记住本次 mid，保证收藏夹页下次能自动带入并加载
            container.recordStore.setLastMid(mid)
            val cached = container.folderCache.folders(mid)
            if (cached != null) {
                _folders.value = cached.value
                _foldersError.value = null
            }
            if (!force && cached != null && cached.isFresh()) {
                _foldersLoading.value = false
                LogUtil.d(TAG, "loadFolders: 命中当日缓存，跳过网络请求")
                return@launch
            }
            _foldersLoading.value = cached == null
            _foldersError.value = null
            val result = container.biliApiClient.getFolders(mid)
            if (result.isEmpty()) {
                if (cached == null) {
                    _folders.value = emptyList()
                    _foldersError.value = "未获取到收藏夹（该账号可能没有收藏夹，或 MID 不存在）"
                } else {
                    _foldersError.value = "刷新失败，当前显示的是缓存数据"
                }
            } else {
                _folders.value = result
                container.folderCache.saveFolders(mid, result)
            }
            _foldersLoading.value = false
        }
    }

    /**
     * 主界面刷新：重新拉取收藏夹列表，并作废收藏夹内视频缓存，
     * 让刷新范围覆盖“收藏夹里面的视频”（之后进入任一收藏夹都会重新拉取）。
     */
    fun refreshFolders(mid: Long) {
        viewModelScope.launch {
            container.folderCache.clearVideos()
            _folderVideos.value = emptyList()
            _folderMeta.value = null
            _videosError.value = null
            loadFolders(mid, force = true)
        }
    }

    // ---------- 收藏夹视频（需求 4、14） ----------

    private val _folderVideos = MutableStateFlow<List<VideoInfo>>(emptyList())
    val folderVideos: StateFlow<List<VideoInfo>> = _folderVideos.asStateFlow()

    private val _folderMeta = MutableStateFlow<Pair<String, Int>?>(null) // title, mediaCount
    val folderMeta: StateFlow<Pair<String, Int>?> = _folderMeta.asStateFlow()

    private val _videosLoading = MutableStateFlow(false)
    val videosLoading: StateFlow<Boolean> = _videosLoading.asStateFlow()

    private val _videosError = MutableStateFlow<String?>(null)
    val videosError: StateFlow<String?> = _videosError.asStateFlow()

    /**
     * 加载收藏夹内视频（分页拉全量）。
     *
     * 缓存策略与 [loadFolders] 一致：命中当日缓存直接复用；跨天或 [force] 时后台刷新；
     * 仅当整轮分页都成功时才写入缓存，避免把半截数据缓存下来。
     *
     * [expectedCount] 为收藏夹列表里的视频数。当它与缓存里的数量不一致（例如在别的端删除了视频）
     * 时，即使缓存还是当日数据也强制重新拉取，避免列表显示 0 个、点进去却还有旧视频。
     */
    fun loadFolderVideos(mediaId: Long, title: String, force: Boolean = false, expectedCount: Int? = null) {
        viewModelScope.launch {
            val cached = container.folderCache.videos(mediaId)
            val cachedCount = cached?.let { if (it.value.totalCount > 0) it.value.totalCount else it.value.videos.size }
            val countMismatch = expectedCount != null && cachedCount != null && cachedCount != expectedCount
            if (cached != null) {
                _folderVideos.value = cached.value.videos
                _folderMeta.value = cached.value.title.ifBlank { title } to cached.value.totalCount
                _videosError.value = null
            } else {
                _folderMeta.value = title to 0
            }
            if (!force && !countMismatch && cached != null && cached.isFresh()) {
                _videosLoading.value = false
                LogUtil.d(TAG, "loadFolderVideos: 命中当日缓存，跳过网络请求（mediaId=$mediaId）")
                return@launch
            }
            if (countMismatch) {
                LogUtil.d(TAG, "loadFolderVideos: 列表数量($expectedCount)与缓存($cachedCount)不一致，强制刷新（mediaId=$mediaId）")
            }
            _videosLoading.value = cached == null
            _videosError.value = null
            val videos = mutableListOf<VideoInfo>()
            var totalCount = 0
            var completed = true
            var page = 1
            while (true) {
                val result = container.biliApiClient.getFolderVideos(mediaId, page)
                if (result.error != null) {
                    _videosError.value = result.error
                    completed = false
                    break
                }
                if (page == 1 && result.totalCount > 0) {
                    totalCount = result.totalCount
                    _folderMeta.value = title to result.totalCount
                }
                videos += result.videos
                if (result.videos.size < 20) break
                page += 1
                kotlinx.coroutines.delay(1500)
            }
            _videosLoading.value = false
            if (completed) {
                // 拉取成功即以结果为准（包括空结果），避免残留已被删除的旧视频。
                _folderVideos.value = videos
                _folderMeta.value = title to (if (totalCount > 0) totalCount else videos.size)
                container.folderCache.saveVideos(mediaId, title, if (totalCount > 0) totalCount else videos.size, videos)
            } else if (cached == null) {
                _folderVideos.value = videos
            }
        }
    }

    // ---------- 设置（需求 5、13、15、19、20、21） ----------

    val settings: StateFlow<DownloadSettings> = container.recordStore.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, DownloadSettings())

    val engineType: StateFlow<DownloadEngineType> = container.recordStore.engineType
        .stateIn(viewModelScope, SharingStarted.Eagerly, DownloadEngineType.BUILTIN)

    val webDavConfig: StateFlow<WebDavConfig> = container.recordStore.webDavConfig
        .stateIn(viewModelScope, SharingStarted.Eagerly, WebDavConfig())

    val taskHistory: StateFlow<List<TaskHistory>> = container.recordStore.taskHistory
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // ---------- 调试日志开关（正式版设置页） ----------

    val debugLogEnabled: StateFlow<Boolean> = container.recordStore.debugLogEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** 切换调试日志：立即生效并持久化（LogUtil 输出全部级别加密日志） */
    fun setDebugLogEnabled(enabled: Boolean) {
        LogUtil.setDebugOverride(enabled)
        viewModelScope.launch { container.recordStore.setDebugLogEnabled(enabled) }
    }

    /** Gopeed 引擎可用性（设置页禁用逻辑，需求 19） */
    val gopeedAvailable: StateFlow<Boolean> = MutableStateFlow(false).also { flow ->
        viewModelScope.launch { flow.value = container.gopeedEngine.isAvailable() }
    }

    fun updateSettings(block: suspend (DownloadSettings) -> DownloadSettings) {
        viewModelScope.launch { container.recordStore.updateSettings(block) }
    }

    fun setEngineType(type: DownloadEngineType) {
        viewModelScope.launch { container.recordStore.setEngineType(type) }
    }

    fun setWebDavConfig(config: WebDavConfig) {
        viewModelScope.launch { container.recordStore.setWebDavConfig(config) }
    }

    // ---------- 已下载视频库（需求 18） ----------

    val libraryVideos: MutableStateFlow<List<com.bilifolder.downloader.data.VideoLibrary.VideoItem>> =
        MutableStateFlow(container.library.list())

    fun refreshLibrary() {
        libraryVideos.value = container.library.list()
    }

    fun deleteVideo(item: com.bilifolder.downloader.data.VideoLibrary.VideoItem) {
        if (container.library.delete(item)) refreshLibrary()
    }

    // ---------- 单视频播放（下载→合并→播放） ----------

    /** 播放准备状态：驱动收藏夹页的下载进度条与播放器 */
    sealed interface PlaybackState {
        data object Idle : PlaybackState
        data class Preparing(val stage: String, val downloaded: Long, val total: Long) : PlaybackState
        data class Ready(val file: File) : PlaybackState
        data class Error(val message: String) : PlaybackState
    }

    private val _playback = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    val playback: StateFlow<PlaybackState> = _playback.asStateFlow()

    private var playbackJob: Job? = null

    /** 播放代次：取消或重新播放时自增，旧协程据此放弃写状态，避免覆盖新状态 */
    @Volatile
    private var playbackGeneration = 0

    /**
     * 播放单条视频：成品已存在则直接播放；否则下载视频/音频流、合并、校验并入库后播放。
     * 批量下载任务运行中时拒绝（避免与批量任务抢占引擎）。
     */
    fun playVideo(video: VideoInfo) {
        if (container.downloadManager.isRunning) {
            _playback.value = PlaybackState.Error("正在下载任务中，暂不支持播放")
            return
        }
        val gen = ++playbackGeneration
        // 停掉上一次未完成的播放准备（stopFlag 会让其尽快退出）
        container.downloadManager.stop()
        val previous = playbackJob
        playbackJob = viewModelScope.launch {
            // 等待上一次准备退出，确保 DownloadManager 的 playbackPreparing 已复位
            previous?.join()
            if (gen != playbackGeneration) return@launch

            val storage = container.storageManager
            val existing = File(storage.downloadDir, "${storage.safeFileName(video.title)}.mp4")
            if (existing.isFile && existing.length() > 0L) {
                if (gen == playbackGeneration) _playback.value = PlaybackState.Ready(existing)
                return@launch
            }

            _playback.value = PlaybackState.Preparing("准备中…", 0L, 0L)
            val settings = container.recordStore.settings.first()
            val file = container.downloadManager.prepareForPlayback(video, settings) { stage, done, total ->
                if (gen == playbackGeneration) {
                    _playback.value = PlaybackState.Preparing(stage, done, total)
                }
            }
            if (gen != playbackGeneration) return@launch
            _playback.value = if (file != null) {
                refreshLibrary()
                PlaybackState.Ready(file)
            } else {
                PlaybackState.Error("下载或合并失败，请重试")
            }
        }
    }

    /** 取消播放准备（停止下载合并）并复位状态 */
    fun cancelPlayback() {
        playbackGeneration++
        container.downloadManager.stop()
        _playback.value = PlaybackState.Idle
    }

    /** 播放器/失败提示关闭后复位状态 */
    fun consumePlayback() {
        _playback.value = PlaybackState.Idle
    }

    // ---------- 任务历史操作（需求 18、22） ----------

    /** 历史"重新运行"：跳转视频选择页 */
    private val _rerunFolder = MutableStateFlow<Folder?>(null)
    val rerunFolder: StateFlow<Folder?> = _rerunFolder.asStateFlow()

    fun consumeRerun(): Folder? {
        val f = _rerunFolder.value
        _rerunFolder.value = null
        return f
    }

    fun rerunHistory(entry: TaskHistory) {
        val folder = entry.folder ?: return
        _rerunFolder.value = folder
    }

    fun deleteHistory(entry: TaskHistory) {
        viewModelScope.launch { container.recordStore.deleteTaskHistory(entry.timestamp) }
    }
}
