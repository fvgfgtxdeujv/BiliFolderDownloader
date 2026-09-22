package com.bilifolder.downloader.data

import android.content.Context
import com.bilifolder.downloader.data.model.Folder
import com.bilifolder.downloader.data.model.VideoInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Calendar

/**
 * 收藏夹数据本地缓存：收藏夹列表按 mid 缓存，收藏夹内视频按 mediaId 缓存。
 *
 * 复用规则（与用户约定一致）：
 * - 缓存与当前处于同一自然日时有效，页面直接使用缓存、不请求网络；
 * - 跨天视为过期，进入页面时自动刷新（每天更新一次）；
 * - 重新登录/退出登录调用 [clear] 清空全部缓存；
 * - 手动刷新时由调用方传 `force = true` 忽略缓存强制拉取。
 *
 * 落盘位置：`filesDir/folder_cache/`，每个 mid/mediaId 一个 JSON 文件。
 */
class FolderCacheStore(context: Context) {

    private val cacheDir = File(context.applicationContext.filesDir, CACHE_DIR).apply { mkdirs() }

    private val json = Json { ignoreUnknownKeys = true }

    /** 命中缓存的数据及其写入时间 */
    data class Cached<T>(val value: T, val fetchedAt: Long) {
        /** 缓存是否与当前处于同一自然日 */
        fun isFresh(): Boolean = isSameDay(fetchedAt, System.currentTimeMillis())
    }

    /** 收藏夹内视频缓存内容 */
    data class CachedVideos(val title: String, val totalCount: Int, val videos: List<VideoInfo>)

    @Serializable
    private data class FolderListCache(val fetchedAt: Long, val folders: List<Folder>)

    @Serializable
    private data class FolderVideosCache(
        val fetchedAt: Long,
        val title: String,
        val totalCount: Int,
        val videos: List<VideoInfo>,
    )

    suspend fun folders(mid: Long): Cached<List<Folder>>? = withContext(Dispatchers.IO) {
        val raw = readText(File(cacheDir, "$FOLDER_PREFIX$mid.json")) ?: return@withContext null
        runCatching { json.decodeFromString(FolderListCache.serializer(), raw) }
            .getOrNull()
            ?.let { Cached(it.folders, it.fetchedAt) }
    }

    suspend fun saveFolders(mid: Long, folders: List<Folder>) = withContext(Dispatchers.IO) {
        val cache = FolderListCache(System.currentTimeMillis(), folders)
        writeText(File(cacheDir, "$FOLDER_PREFIX$mid.json"), json.encodeToString(FolderListCache.serializer(), cache))
    }

    suspend fun videos(mediaId: Long): Cached<CachedVideos>? = withContext(Dispatchers.IO) {
        val raw = readText(File(cacheDir, "$VIDEO_PREFIX$mediaId.json")) ?: return@withContext null
        runCatching { json.decodeFromString(FolderVideosCache.serializer(), raw) }
            .getOrNull()
            ?.let { Cached(CachedVideos(it.title, it.totalCount, it.videos), it.fetchedAt) }
    }

    suspend fun saveVideos(mediaId: Long, title: String, totalCount: Int, videos: List<VideoInfo>) =
        withContext(Dispatchers.IO) {
            val cache = FolderVideosCache(System.currentTimeMillis(), title, totalCount, videos)
            writeText(File(cacheDir, "$VIDEO_PREFIX$mediaId.json"), json.encodeToString(FolderVideosCache.serializer(), cache))
        }

    /** 清空全部缓存（重新登录/退出登录时调用） */
    suspend fun clear() = withContext(Dispatchers.IO) {
        cacheDir.listFiles()?.forEach { it.delete() }
    }

    /** 仅作废收藏夹内视频缓存（主界面刷新时调用，下次进入各收藏夹会重新拉取） */
    suspend fun clearVideos() = withContext(Dispatchers.IO) {
        cacheDir.listFiles { file -> file.name.startsWith(VIDEO_PREFIX) }?.forEach { it.delete() }
    }

    private fun readText(file: File): String? =
        if (file.isFile) runCatching { file.readText() }.getOrNull() else null

    private fun writeText(file: File, text: String) {
        runCatching { file.writeText(text) }
    }

    private companion object {
        const val CACHE_DIR = "folder_cache"
        const val FOLDER_PREFIX = "folders_"
        const val VIDEO_PREFIX = "videos_"
    }
}

/** 两个时间戳是否落在同一自然日（用于“每天更新一次”的缓存判断） */
private fun isSameDay(first: Long, second: Long): Boolean {
    if (first <= 0L) return false
    val a = Calendar.getInstance().apply { timeInMillis = first }
    val b = Calendar.getInstance().apply { timeInMillis = second }
    return a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
        a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
}
