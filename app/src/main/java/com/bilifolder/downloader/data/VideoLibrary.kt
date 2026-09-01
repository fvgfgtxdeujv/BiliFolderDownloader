package com.bilifolder.downloader.data

import java.io.File

/**
 * 已下载视频库（设计 4.7.2，需求 18）。
 *
 * 扫描下载目录中的成品 MP4（排除 `_video.mp4`/`_audio.mp4` 临时文件），
 * 输出列表（标题、大小、修改时间），支持删除文件。
 */
class VideoLibrary(
    private val storageManager: StorageManager,
) {

    data class VideoItem(
        val file: File,
        val title: String,
        val sizeBytes: Long,
        val lastModified: Long,
    )

    /** 列出下载目录中全部成品 MP4（不含临时流文件） */
    fun list(): List<VideoItem> {
        val dir = storageManager.downloadDir
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles { f ->
            f.isFile && f.name.endsWith(".mp4", ignoreCase = true) &&
                !f.name.endsWith("_video.mp4", ignoreCase = true) &&
                !f.name.endsWith("_audio.mp4", ignoreCase = true)
        }?.map { f ->
            VideoItem(
                file = f,
                title = f.name.removeSuffix(".mp4"),
                sizeBytes = f.length(),
                lastModified = f.lastModified(),
            )
        }?.sortedByDescending { it.lastModified }.orEmpty()
    }

    /** 删除指定视频文件 */
    fun delete(item: VideoItem): Boolean = runCatching { item.file.delete() }.getOrDefault(false)
}
