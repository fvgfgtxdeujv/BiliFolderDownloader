package com.bilifolder.downloader.data.model

import kotlinx.serialization.Serializable

/**
 * 收藏夹内视频信息，对应桌面版 `VideoInfo` dataclass（`1.py#L50-L56`）。
 */
@Serializable
data class VideoInfo(
    val bvid: String,
    val title: String,
    val cid: Int,
    val avid: Long = 0,
    val page: Int = 1,
)

/**
 * 用户收藏夹，对应桌面版 `get_user_folders` 返回的 dict（`1.py#L230-L253`）。
 */
@Serializable
data class Folder(
    val mediaId: Long,
    val title: String,
    val mediaCount: Int = 0,
)

/**
 * 下载引擎统一任务句柄（对应设计 4.2 引擎层）。
 */
data class EngineTask(
    val id: String,
    val kind: EngineTaskKind,
    val savePath: String,
)

/** 引擎任务类型：视频流 / 音频流 / 单文件 */
enum class EngineTaskKind { VIDEO, AUDIO, FILE }

/**
 * 引擎进度，两个引擎实现归一化后返回同一语义（设计 4.2）。
 */
data class EngineProgress(
    val downloaded: Long,
    val total: Long,
    val status: EngineStatus,
)

enum class EngineStatus { RUNNING, PAUSED, DONE, ERROR, WAITING }

/**
 * 下载历史记录（需求 18）。folder 用于历史"重新运行"跳转视频选择页。
 */
@Serializable
data class TaskHistory(
    val timestamp: Long,
    val folderName: String,
    val total: Int,
    val success: Int,
    val failed: Int,
    val zipPath: String? = null,
    val folder: Folder? = null,
)

/**
 * WebDAV 配置（需求 20）。密码独立密文存储于 CookieStore，不入此对象明文。
 */
@Serializable
data class WebDavConfig(
    val url: String = "",
    val username: String = "",
    val autoUpload: Boolean = false,
) {
    fun isConfigured(): Boolean = url.isNotBlank() && username.isNotBlank()
}

/**
 * 下载引擎枚举（需求 19）。
 */
enum class DownloadEngineType(val displayName: String) {
    BUILTIN("内置下载器"),
    GOPEED("Gopeed 引擎"),
}
