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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 主界面状态管理：登录态、收藏夹/视频列表、设置、任务历史。
 * 下载事件由 [AppContainer.downloadManager] 的 SharedFlow 直接暴露给 UI。
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    val container: AppContainer get() = BiliApp.container

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

    // ---------- 收藏夹（需求 3） ----------

    private val _folders = MutableStateFlow<List<Folder>>(emptyList())
    val folders: StateFlow<List<Folder>> = _folders.asStateFlow()

    private val _foldersLoading = MutableStateFlow(false)
    val foldersLoading: StateFlow<Boolean> = _foldersLoading.asStateFlow()

    private val _foldersError = MutableStateFlow<String?>(null)
    val foldersError: StateFlow<String?> = _foldersError.asStateFlow()

    fun loadFolders(mid: Long) {
        viewModelScope.launch {
            _foldersLoading.value = true
            _foldersError.value = null
            val result = container.biliApiClient.getFolders(mid)
            if (result.isEmpty()) {
                _foldersError.value = "未获取到收藏夹（检查 MID 是否正确）"
            } else {
                _folders.value = result
                container.recordStore.setLastMid(mid)
            }
            _foldersLoading.value = false
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

    fun loadFolderVideos(mediaId: Long, title: String) {
        viewModelScope.launch {
            _videosLoading.value = true
            _videosError.value = null
            _folderMeta.value = title to 0
            val videos = mutableListOf<VideoInfo>()
            var page = 1
            while (true) {
                val result = container.biliApiClient.getFolderVideos(mediaId, page)
                if (result.error != null) {
                    _videosError.value = result.error
                    break
                }
                if (page == 1 && result.totalCount > 0) {
                    _folderMeta.value = title to result.totalCount
                }
                videos += result.videos
                if (result.videos.size < 20) break
                page += 1
                kotlinx.coroutines.delay(1500)
            }
            _folderVideos.value = videos
            _videosLoading.value = false
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
