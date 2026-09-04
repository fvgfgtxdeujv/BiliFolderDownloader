package com.bilifolder.downloader.debug

import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.system.ErrnoException
import android.system.Os
import android.system.StructStat
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/**
 * 开发版专用文件提供器：向 MT 管理器及其他 SAF/DocumentsContract 客户端导出应用私有数据目录，
 * 免 ROOT 在手机上浏览 / 下载 / 清理 data 目录（查看 SQLite 日志库、导出加密日志、重置数据等）。
 *
 * 仅存在于 debug 源集：本类与 manifest 声明只在 debug 变体参与编译与打包，release 正式版不含。
 * 功能与 https://github.com/L-JINBIN/MTDataFilesProvider 一致（含 MT 定制列与 mt: call 扩展）。
 *
 * docId 编码：`{packageName}` 为根目录；`{packageName}/data` 等为顶层虚拟目录；
 * 深层文件为 `{packageName}/data/相对路径`。data -> /data/data/包名（或 /data/user/0/包名），
 * user_de_data -> /data/user_de/0/包名，android_data -> /sdcard/Android/data/包名。
 */
class DebugFilesProvider : DocumentsProvider() {

    companion object {
        /** MT 定制列：文件真实绝对路径 */
        const val COLUMN_MT_PATH = "mt_path"

        /** MT 定制列：mode|uid|gid[|symlink 目标]，用于 MT 显示权限与所有者 */
        const val COLUMN_MT_EXTRAS = "mt_extras"

        private const val METHOD_SET_LAST_MODIFIED = "mt:setLastModified"
        private const val METHOD_SET_PERMISSIONS = "mt:setPermissions"
        private const val METHOD_CREATE_SYMLINK = "mt:createSymlink"

        private val DEFAULT_ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_MIME_TYPES,
            Root.COLUMN_FLAGS,
            Root.COLUMN_ICON,
            Root.COLUMN_TITLE,
            Root.COLUMN_SUMMARY,
            Root.COLUMN_DOCUMENT_ID
        )

        private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS,
            Document.COLUMN_SIZE,
            COLUMN_MT_PATH,
            COLUMN_MT_EXTRAS
        )

        // stat.st_mode 位掩码（与 C 语言 S_IFMT/S_IFLNK 一致）
        private const val S_IFMT = 0xF000.toInt()
        private const val S_IFLNK = 0xA000.toInt()
    }

    private var packageName = ""
    private lateinit var dataDir: File
    private var userDeDataDir: File? = null
    private var androidDataDir: File? = null
    private var androidObbDir: File? = null

    override fun attachInfo(context: Context, info: ProviderInfo) {
        super.attachInfo(context, info)
        packageName = context.packageName
        dataDir = context.filesDir.parentFile ?: File(context.filesDir, "..")
        val dataPath = dataDir.path
        if (dataPath.startsWith("/data/user/")) {
            // /data/user/0/<pkg> -> /data/user_de/0/<pkg>
            userDeDataDir = File("/data/user_de/" + dataPath.removePrefix("/data/user/"))
        }
        androidDataDir = context.getExternalFilesDir(null)?.parentFile
        androidObbDir = context.obbDir
    }

    override fun onCreate(): Boolean = true

    /**
     * docId 解码为真实文件；返回 null 表示根目录。
     * @param checkExists 为 false 时不校验路径是否存在（创建软链接等场景）
     */
    private fun getFileForDocId(docId: String, checkExists: Boolean = true): File? {
        if (!docId.startsWith(packageName)) {
            throw FileNotFoundException("$docId not found")
        }
        val filename = docId.removePrefix(packageName).removePrefix("/")
        if (filename.isEmpty()) return null
        val type = filename.substringBefore('/')
        val subPath = filename.substringAfter('/', missingDelimiterValue = "")
        var file: File? = null
        when (type) {
            "data" -> file = File(dataDir, subPath)
            "android_data" -> androidDataDir?.let { file = File(it, subPath) }
            "android_obb" -> androidObbDir?.let { file = File(it, subPath) }
            "user_de_data" -> userDeDataDir?.let { file = File(it, subPath) }
            else -> throw FileNotFoundException("$docId not found")
        }
        val f = file ?: throw FileNotFoundException("$docId not found")
        if (checkExists) {
            try {
                // 不能用 File.exists()：若文件是软链接且目标不可访问，exists() 会误返回 false
                Os.lstat(f.path)
            } catch (e: ErrnoException) {
                throw FileNotFoundException("$docId not found")
            }
        }
        return f
    }

    override fun queryRoots(projection: Array<String>?): Cursor {
        val ctx = requireNotNull(context) { "provider not attached" }
        val appInfo = ctx.applicationInfo
        val appName = ctx.packageManager.getApplicationLabel(appInfo).toString()
        val result = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)
        result.newRow()
            .add(Root.COLUMN_ROOT_ID, packageName)
            .add(Root.COLUMN_DOCUMENT_ID, packageName)
            .add(Root.COLUMN_SUMMARY, packageName)
            .add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_IS_CHILD)
            .add(Root.COLUMN_TITLE, "$appName (debug)")
            .add(Root.COLUMN_MIME_TYPES, "*/*")
            .add(Root.COLUMN_ICON, appInfo.icon)
        return result
    }

    override fun queryDocument(documentId: String, projection: Array<String>?): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        includeFile(result, documentId, null)
        return result
    }

    @Suppress("DEPRECATION") // 旧版 sortOrder 接口，Android 15+ 系统仍可能经 Bundle 版转入
    override fun queryChildDocuments(parentDocumentId: String, projection: Array<String>?, sortOrder: String?): Cursor =
        queryChildDocumentsInternal(parentDocumentId, projection)

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<String>?, queryArgs: Bundle?): Cursor =
        queryChildDocumentsInternal(parentDocumentId, projection)

    private fun queryChildDocumentsInternal(parentDocumentId: String, projection: Array<String>?): Cursor {
        val parent = if (parentDocumentId.endsWith("/")) {
            parentDocumentId.dropLast(1)
        } else {
            parentDocumentId
        }
        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val dir = getFileForDocId(parent)
        if (dir == null) {
            // 根目录：列出各顶层虚拟目录（不存在/不可用的不显示）
            includeFile(result, "$parent/data", dataDir)
            androidDataDir?.takeIf { it.exists() }?.let { includeFile(result, "$parent/android_data", it) }
            androidObbDir?.takeIf { it.exists() }?.let { includeFile(result, "$parent/android_obb", it) }
            userDeDataDir?.takeIf { it.exists() }?.let { includeFile(result, "$parent/user_de_data", it) }
        } else {
            dir.listFiles()?.forEach { child ->
                includeFile(result, "$parent/${child.name}", child)
            }
        }
        return result
    }

    override fun getDocumentType(documentId: String): String {
        val file = getFileForDocId(documentId)
        return if (file == null || file.isDirectory) Document.MIME_TYPE_DIR else getMimeType(file)
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        val file = getFileForDocId(documentId)
        if (file == null) throw FileNotFoundException("$documentId not found")
        return ParcelFileDescriptor.open(file, parseFileMode(mode))
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean =
        documentId.startsWith(parentDocumentId)

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val parent = getFileForDocId(parentDocumentId) ?: throw FileNotFoundException("$parentDocumentId not found")
        val newFile = File(parent, displayName)
        val succeeded = if (Document.MIME_TYPE_DIR == mimeType) {
            newFile.mkdir()
        } else {
            newFile.createNewFile()
        }
        if (!succeeded) throw FileNotFoundException("Failed to create document in $parentDocumentId with name $displayName")
        return if (parentDocumentId.endsWith("/")) {
            parentDocumentId + newFile.name
        } else {
            "$parentDocumentId/${newFile.name}"
        }
    }

    override fun deleteDocument(documentId: String) {
        val file = getFileForDocId(documentId)
        if (file == null || !deleteRecursively(file)) {
            throw FileNotFoundException("Failed to delete document $documentId")
        }
    }

    override fun removeDocument(documentId: String, parentDocumentId: String) {
        deleteDocument(documentId)
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        val file = getFileForDocId(documentId) ?: throw FileNotFoundException("$documentId not found")
        val target = File(file.parentFile, displayName)
        if (!file.renameTo(target)) throw FileNotFoundException("Failed to rename document $documentId to $displayName")
        val i = documentId.lastIndexOf('/', documentId.length - 2)
        return documentId.substring(0, i) + "/" + displayName
    }

    override fun moveDocument(sourceDocumentId: String, sourceParentDocumentId: String, targetParentDocumentId: String): String {
        val sourceFile = getFileForDocId(sourceDocumentId) ?: throw FileNotFoundException("$sourceDocumentId not found")
        val targetDir = getFileForDocId(targetParentDocumentId) ?: throw FileNotFoundException("$targetParentDocumentId not found")
        val targetFile = File(targetDir, sourceFile.name)
        if (targetFile.exists() || !sourceFile.renameTo(targetFile)) {
            throw FileNotFoundException("Failed to move document $sourceDocumentId to $targetParentDocumentId")
        }
        return if (targetParentDocumentId.endsWith("/")) {
            targetParentDocumentId + targetFile.name
        } else {
            "$targetParentDocumentId/${targetFile.name}"
        }
    }

    /**
     * MT 定制扩展方法，返回 Bundle；非 mt: 方法交由基类处理。
     */
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val result = super.call(method, arg, extras)
        if (result != null) return result
        if (extras == null || !method.startsWith("mt:")) return null
        val out = Bundle()
        try {
            val uri: Uri = extras.getParcelable("uri") ?: return out
            val segments = uri.pathSegments
            // content://<authority>/document/<docId> 或 tree/<treeId>/document/<docId>
            val documentId = if (segments.size >= 4) segments[3] else segments.getOrNull(1)
            if (documentId == null) {
                out.putBoolean("result", false)
                return out
            }
            // 创建软链接时 link 路径允许尚不存在
            val file = getFileForDocId(documentId, checkExists = method != METHOD_CREATE_SYMLINK)
            if (file == null) {
                out.putBoolean("result", false)
                return out
            }
            when (method) {
                METHOD_SET_LAST_MODIFIED -> {
                    val time = extras.getLong("time")
                    out.putBoolean("result", file.setLastModified(time))
                }
                METHOD_SET_PERMISSIONS -> {
                    try {
                        Os.chmod(file.path, extras.getInt("permissions"))
                        out.putBoolean("result", true)
                    } catch (e: ErrnoException) {
                        out.putBoolean("result", false)
                        out.putString("message", e.message)
                    }
                }
                METHOD_CREATE_SYMLINK -> {
                    val target = extras.getString("path")
                    try {
                        Os.symlink(target, file.path)
                        out.putBoolean("result", true)
                    } catch (e: ErrnoException) {
                        out.putBoolean("result", false)
                        out.putString("message", e.message)
                    }
                }
                else -> {
                    out.putBoolean("result", false)
                    out.putString("message", "Unsupported method: $method")
                }
            }
        } catch (e: Exception) {
            out.putBoolean("result", false)
            out.putString("message", e.toString())
        }
        return out
    }

    /**
     * 向游标写入文件/目录的一行描述。
     */
    private fun includeFile(result: MatrixCursor, docId: String, file: File?) {
        val f = file ?: getFileForDocId(docId) ?: run {
            result.newRow()
                .add(Document.COLUMN_DOCUMENT_ID, packageName)
                .add(Document.COLUMN_DISPLAY_NAME, packageName)
                .add(Document.COLUMN_SIZE, 0L)
                .add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR)
                .add(Document.COLUMN_LAST_MODIFIED, 0L)
                .add(Document.COLUMN_FLAGS, 0)
            return
        }

        var flags = 0
        if (f.isDirectory) {
            if (f.canWrite()) flags = flags or Document.FLAG_DIR_SUPPORTS_CREATE
        } else if (f.canWrite()) {
            flags = flags or Document.FLAG_SUPPORTS_WRITE
        }
        f.parentFile?.let { parent ->
            if (parent.canWrite()) {
                flags = flags or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
            }
        }

        var addExtras = true
        val displayName = when (f.path) {
            dataDir.path -> { addExtras = false; "data" }
            androidDataDir?.path -> { addExtras = false; "android_data" }
            androidObbDir?.path -> { addExtras = false; "android_obb" }
            userDeDataDir?.path -> { addExtras = false; "user_de_data" }
            else -> f.name
        }

        val row = result.newRow()
            .add(Document.COLUMN_DOCUMENT_ID, docId)
            .add(Document.COLUMN_DISPLAY_NAME, displayName)
            .add(Document.COLUMN_SIZE, f.length())
            .add(Document.COLUMN_MIME_TYPE, if (f.isDirectory) Document.MIME_TYPE_DIR else getMimeType(f))
            .add(Document.COLUMN_LAST_MODIFIED, f.lastModified())
            .add(Document.COLUMN_FLAGS, flags)
            .add(COLUMN_MT_PATH, f.absolutePath)
        if (addExtras) {
            // mode|uid|gid[|symlink 目标]，供 MT 显示权限/所有者/软链接
            row.add(COLUMN_MT_EXTRAS, buildMtExtras(f))
        }
    }

    private fun buildMtExtras(file: File): String? {
        // 仅对非顶层虚拟目录给出 mode|uid|gid[|linkTarget]，供 MT 显示权限与所有者
        return try {
            val stat: StructStat = Os.lstat(file.path)
            val sb = StringBuilder()
                .append(stat.st_mode).append('|')
                .append(stat.st_uid).append('|')
                .append(stat.st_gid)
            if ((stat.st_mode and S_IFMT) == S_IFLNK) {
                sb.append('|').append(Os.readlink(file.path))
            }
            sb.toString()
        } catch (e: ErrnoException) {
            null
        }
    }

    private fun deleteRecursively(file: File): Boolean {
        if (file.isDirectory && !isSymbolicLink(file)) {
            file.listFiles()?.forEach { child ->
                if (!deleteRecursively(child)) return false
            }
        }
        return file.delete()
    }

    private fun isSymbolicLink(file: File): Boolean {
        return try {
            val stat = Os.lstat(file.path)
            (stat.st_mode and S_IFMT) == S_IFLNK
        } catch (e: ErrnoException) {
            false
        }
    }

    private fun getMimeType(file: File): String {
        val name = file.name
        val lastDot = name.lastIndexOf('.')
        if (lastDot >= 0) {
            val extension = name.substring(lastDot + 1).lowercase()
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)?.let { return it }
        }
        return "application/octet-stream"
    }

    private fun parseFileMode(mode: String): Int = when (mode) {
        "r" -> ParcelFileDescriptor.MODE_READ_ONLY
        "w", "wt" -> ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE
        "wa" -> ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_APPEND
        "rw" -> ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE
        "rwt" -> ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE
        else -> throw IllegalArgumentException("Invalid mode: $mode")
    }
}
