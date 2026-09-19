package com.bilifolder.downloader.util

import android.content.Context
import com.bilifolder.downloader.BuildConfig
import com.bilifolder.downloader.data.WebDavClient
import java.io.File
import java.util.Properties

/**
 * 开发版一键上传调试日志数据库。
 *
 * 凭据来自外置配置文件 `assets/debug_webdav.properties`（明文，已在 .gitignore 中排除）。
 * 文件位于 `app/src/debug/assets/`（debug 源集），仅开发版打包，正式版 APK 不含凭据。
 * ```
 * url=https://example.com/webdav
 * username=xxx
 * password=xxx
 * ```
 * 源码不含任何凭据；文件缺失或字段为空时按钮点击返回「未配置」，不会发起请求。
 *
 * 仅开发版（[BuildConfig.DEBUG]）生效；正式版直接返回提示，不发起任何网络请求。
 */
object DebugDbUploader {

    private const val TAG = "DebugDbUploader"

    /** 外置配置文件（assets 下，不纳入版本控制） */
    private const val CONFIG_ASSET = "debug_webdav.properties"

    /** 远端文件名 */
    private const val REMOTE_NAME = "debug_logs.db"

    /** 本地 SQLite 库文件名（见 LogStore.DB_NAME） */
    private const val LOCAL_DB_NAME = "debug_logs.db"

    private data class Credentials(val url: String, val username: String, val password: String)

    /**
     * 上传当前日志数据库。
     * @return 面向用户的结果描述（成功含字节数）
     */
    suspend fun uploadDb(context: Context, client: WebDavClient): String {
        if (!BuildConfig.DEBUG) return "仅开发版可用"
        val cred = loadCredentials(context)
            ?: return "未配置调试 WebDAV（缺少 assets/$CONFIG_ASSET）"
        val db = context.getDatabasePath(LOCAL_DB_NAME)
        if (!db.exists() || db.length() == 0L) return "日志库为空，无需上传"

        // 复制一份快照再上传，避免长时间读文件句柄与 SQLite 写入相互影响
        val snapshot = File(context.cacheDir, "debug_logs_upload.db")
        return try {
            db.copyTo(snapshot, overwrite = true)
            LogUtil.d(TAG, "uploadDb: 快照 ${snapshot.length()}B，上传 ${snapshot.name}")
            when (client.uploadZip(cred.url, REMOTE_NAME, cred.username, cred.password, snapshot) { _, _ -> }) {
                WebDavClient.UploadResult.OK -> "上传成功（${snapshot.length()}B）"
                WebDavClient.UploadResult.AUTH -> "上传失败：账号或密码错误"
                WebDavClient.UploadResult.FAILED -> "上传失败：网络或服务器错误"
            }
        } catch (e: Exception) {
            LogUtil.w(TAG, "uploadDb: 异常", e)
            "上传异常：${e.message}"
        } finally {
            runCatching { snapshot.delete() }
        }
    }

    /** 从 assets 读取外置凭据；文件缺失或地址/用户名为空时返回 null */
    private fun loadCredentials(context: Context): Credentials? = runCatching {
        context.assets.open(CONFIG_ASSET).use { input ->
            val props = Properties().apply { load(input) }
            val url = props.getProperty("url")?.trim().orEmpty()
            val username = props.getProperty("username")?.trim().orEmpty()
            val password = props.getProperty("password")?.trim().orEmpty()
            if (url.isEmpty() || username.isEmpty()) null else Credentials(url, username, password)
        }
    }.getOrNull()

    /** 是否已配置调试 WebDAV（用于界面提示，仅读取键是否存在） */
    fun isConfigured(context: Context): Boolean = loadCredentials(context) != null
}
