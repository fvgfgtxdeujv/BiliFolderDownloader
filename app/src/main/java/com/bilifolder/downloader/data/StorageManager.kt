package com.bilifolder.downloader.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.bilifolder.downloader.util.LogUtil
import java.io.File

/**
 * 下载目录与文件操作封装（设计 4.8，需求 12）。
 *
 * - 下载目录固定为应用专属外部存储：`context.getExternalFilesDir(null)/downloads/`
 *   （Gopeed 与内置引擎均以本地路径写入，无需存储权限，卸载即清理）
 * - 文件名安全化：替换 Windows/Android 非法字符（对应桌面版安全文件名处理）
 * - SAF 导出辅助：把 MP4/zip 流式复制到用户通过 `ACTION_OPEN_DOCUMENT_TREE` 选择的目录
 */
class StorageManager(context: Context) {

    private val appContext = context.applicationContext

    val downloadDir: File
        get() = File(appContext.getExternalFilesDir(null), "downloads").apply {
            mkdirs()
            LogUtil.d(TAG, "downloadDir: $absolutePath writable=${canWrite()}")
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
    }
}
