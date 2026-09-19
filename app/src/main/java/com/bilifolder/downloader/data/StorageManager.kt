package com.bilifolder.downloader.data

import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.bilifolder.downloader.util.LogUtil
import java.io.File

/**
 * 下载目录与文件操作封装（设计 4.8，需求 12）。
 *
 * - [downloadDir]：成品目录，公共 `Movies/BiliFolderDownloader/`，系统文件管理器与相册可直接看到
 *   （Android 11+ 允许应用用直接路径写自己创建的媒体文件；Android 10 依赖
 *   `requestLegacyExternalStorage` + `WRITE_EXTERNAL_STORAGE`）
 * - [tempDir]：`_video.mp4`/`_audio.mp4` 分片目录，应用专属外部存储，避免分片进入相册
 * - [zipDir]：打包 zip 目录，应用专属外部存储，避免 zip 混入公共媒体目录
 * - 文件名安全化：替换 Windows/Android 非法字符（对应桌面版安全文件名处理）
 * - SAF 导出辅助：把 MP4/zip 流式复制到用户通过 `ACTION_OPEN_DOCUMENT_TREE` 选择的目录
 */
class StorageManager(context: Context) {

    private val appContext = context.applicationContext

    /** 成品目录（公共 Movies 下子目录），系统文件管理器/相册可见 */
    val downloadDir: File
        get() = publicDir(PUBLIC_SUBDIR)

    /** 临时分片目录（应用专属），存放下载中的 `_video.mp4`/`_audio.mp4` */
    val tempDir: File
        get() = privateDir(TEMP_SUBDIR)

    /** zip 打包目录（应用专属） */
    val zipDir: File
        get() = privateDir(ZIP_SUBDIR)

    @Suppress("DEPRECATION")
    private fun publicDir(name: String): File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), name).apply {
            mkdirs()
            LogUtil.d(TAG, "publicDir: $absolutePath writable=${canWrite()}")
        }

    private fun privateDir(name: String): File =
        File(appContext.getExternalFilesDir(null), name).apply {
            mkdirs()
            LogUtil.d(TAG, "privateDir: $absolutePath")
        }

    /** 触发媒体库扫描，使成品在系统文件管理器/相册立即可见 */
    fun scanMedia(file: File) {
        runCatching {
            MediaScannerConnection.scanFile(appContext, arrayOf(file.absolutePath), null, null)
        }.onFailure { LogUtil.w(TAG, "scanMedia: ${file.name} 触发扫描失败", it) }
    }


    /** 安全文件名：去掉非法字符，限制长度，避免标题中的 `/ \ : * ? " < > |` 破坏路径 */
    fun safeFileName(title: String): String {
        val cleaned = title.replace(Regex("""[/\\:*?"<>|\r\n\t]"""), "_").trim()
        val collapsed = cleaned.replace(Regex("""_+"""), "_").trim('_', ' ')
        val limited = collapsed.take(MAX_FILE_NAME_LENGTH)
        val result = limited.ifBlank { "untitled" }
        LogUtil.d(TAG, "safeFileName: \"$title\" -> \"$result\"")
        return result
    }

    /** 校验下载目录可写（任务开始前调用，需求 12） */
    fun isDownloadDirWritable(): Boolean = runCatching {
        val dir = downloadDir
        val ok = dir.isDirectory && dir.canWrite()
        LogUtil.d(TAG, "isDownloadDirWritable: $ok")
        ok
    }.getOrDefault(false).also {
        if (!it) LogUtil.w(TAG, "isDownloadDirWritable: 不可写")
    }

    /**
     * 导出到用户选择的目标目录（SAF）。
     * @param targetTreeUri ACTION_OPEN_DOCUMENT_TREE 返回的树 URI
     * @param file 要导出的本地文件
     * @return 成功返回目标 URI，失败返回 null
     */
    suspend fun exportToTree(targetTreeUri: Uri, file: File): Uri? {
        if (!file.exists()) {
            LogUtil.w(TAG, "exportToTree: 文件不存在 ${file.name}")
            return null
        }
        return try {
            val docUri = DocumentsContract.createDocument(
                appContext.contentResolver,
                targetTreeUri,
                "application/octet-stream",
                file.name,
            ) ?: run {
                LogUtil.w(TAG, "exportToTree: createDocument 返回 null ${file.name}")
                return null
            }
            appContext.contentResolver.openOutputStream(docUri, "w")?.use { out ->
                file.inputStream().use { it.copyTo(out) }
            }
            LogUtil.d(TAG, "exportToTree: 导出完成 ${file.name} -> $docUri")
            docUri
        } catch (e: Exception) {
            LogUtil.e(TAG, "exportToTree: 导出失败 ${file.name}", e)
            null
        }
    }

    /** 文档名查询（用于 SAF 目录展示） */
    fun queryDisplayName(uri: Uri): String? = runCatching {
        appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
    }.getOrNull()

    private companion object {
        const val TAG = "StorageManager"
        const val MAX_FILE_NAME_LENGTH = 120

        /** 公共成品目录名（位于 Movies 下） */
        const val PUBLIC_SUBDIR = "BiliFolderDownloader"

        /** 应用专属临时分片目录名 */
        const val TEMP_SUBDIR = "downloads_tmp"

        /** 应用专属 zip 目录名 */
        const val ZIP_SUBDIR = "zips"
    }
}
