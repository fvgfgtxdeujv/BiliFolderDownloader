package com.bilifolder.downloader.data

import com.bilifolder.downloader.util.LogUtil
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import java.io.File
import java.io.IOException
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * WebDAV 备份上传客户端（设计 4.5.1，需求 20、21）。
 *
 * WebDAV 是 HTTP 扩展协议，直接基于 OkHttp 发送扩展方法，Basic 认证：
 * - `testConnection()`：`PROPFIND`（Depth: 0），2xx 判定连接可用
 * - `mkdir(remoteDir)`：递归 `MKCOL` 建目录
 * - `uploadZip(...)`：流式 `PUT` 上传 zip，自定义 RequestBody 按读入字节上报进度
 *
 * 失败归类：401/403 = 配置错误（UI 提示检查账号）；其余 = 可重试（保留本地 zip）。
 */
class WebDavClient {

    /** 结果归类：OK / AUTH（401/403，配置错误）/ FAILED（网络或服务器错误） */
    enum class UploadResult { OK, AUTH, FAILED }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(5, TimeUnit.MINUTES)
        .build()

    /** 拼接基本 URL（去尾部斜杠）+ 相对路径 */
    private fun buildUrl(baseUrl: String, relativePath: String = ""): String {
        val base = baseUrl.trim().trimEnd('/')
        val rel = relativePath.trim().trimStart('/')
        return if (rel.isEmpty()) base else "$base/$rel"
    }

    private fun authHeader(username: String, password: String): String {
        val raw = "$username:$password"
        return "Basic " + Base64.getEncoder().encodeToString(raw.toByteArray(StandardCharsets.UTF_8))
    }

    /**
     * 连接测试：PROPFIND Depth: 0，2xx 即通过。
     * @param url 基础地址（可能含用户名密码内嵌）
     * @param username 配置用户名（内嵌凭证存在时忽略）
     * @param password 配置密码
     */
    suspend fun testConnection(url: String, username: String, password: String): Boolean =
        withContext(Dispatchers.IO) {
            val cred = parseEmbeddedCredentials(url, username, password)
            val request = Request.Builder()
                .url(buildUrl(cred.url))
                .header("Authorization", authHeader(cred.username, cred.password))
                .method("PROPFIND", RequestBody.create(null, ""))
                .header("Depth", "0")
                .build()
            try {
                val ok = client.newCall(request).execute().use { it.code in 200..299 }
                LogUtil.d(TAG, "testConnection: host=${cred.url} 结果=$ok")
                ok
            } catch (e: IOException) {
                LogUtil.w(TAG, "testConnection: 网络异常", e)
                false
            }
        }

    /**
     * 递归创建目录（父目录不存在时逐级 MKCOL）。
     * @return true 全部成功（已存在视为成功）
     */
    suspend fun mkdir(baseUrl: String, remoteDir: String, username: String, password: String): Boolean =
        withContext(Dispatchers.IO) {
            val cred = parseEmbeddedCredentials(baseUrl, username, password)
            val segments = remoteDir.trim('/').split('/').filter { it.isNotEmpty() }
            if (segments.isEmpty()) return@withContext true
            val parts = mutableListOf<String>()
            for (seg in segments) {
                parts += seg
                val url = buildUrl(cred.url, parts.joinToString("/"))
                val request = Request.Builder()
                    .url(url)
                    .header("Authorization", authHeader(cred.username, cred.password))
                    .method("MKCOL", RequestBody.create(null, ""))
                    .build()
                val code = try {
                    client.newCall(request).execute().use { it.code }
                } catch (e: IOException) {
                    LogUtil.w(TAG, "mkdir: ${parts.joinToString("/")} 网络异常", e)
                    return@withContext false
                }
                LogUtil.d(TAG, "mkdir: ${parts.joinToString("/")} HTTP $code")
                if (code != 201 && code != 405 && code !in 200..299) {
                    // 405 = 已存在
                    LogUtil.w(TAG, "mkdir: ${parts.joinToString("/")} 失败 HTTP $code")
                    return@withContext false
                }
            }
            true
        }

    /**
     * 流式上传 zip。
     * @param remotePath 服务器端完整路径（如 bili_folder_downloader/20260821_xxx/1.zip）
     * @param onProgress (已传字节, 总字节)
     */
    suspend fun uploadZip(
        baseUrl: String,
        remotePath: String,
        username: String,
        password: String,
        localFile: File,
        onProgress: (Long, Long) -> Unit,
    ): UploadResult = withContext(Dispatchers.IO) {
        if (!localFile.exists() || localFile.length() == 0L) return@withContext UploadResult.FAILED
        val cred = parseEmbeddedCredentials(baseUrl, username, password)
        val body = ProgressRequestBody(localFile, onProgress)
        val request = Request.Builder()
            .url(buildUrl(cred.url, remotePath))
            .header("Authorization", authHeader(cred.username, cred.password))
            .put(body)
            .build()
        try {
            client.newCall(request).execute().use { resp ->
                val result = when {
                    resp.code in 200..299 -> UploadResult.OK
                    resp.code == 401 || resp.code == 403 -> UploadResult.AUTH
                    else -> UploadResult.FAILED
                }
                LogUtil.d(TAG, "uploadZip: $remotePath HTTP ${resp.code} -> $result")
                result
            }
        } catch (e: IOException) {
            LogUtil.w(TAG, "uploadZip: $remotePath 网络异常", e)
            UploadResult.FAILED
        }
    }

    /** 兼容 `https://user:pass@host/path` 内嵌凭证；内嵌存在时优先，否则用配置 */
    private fun parseEmbeddedCredentials(url: String, configUser: String, configPass: String): Cred {
        val match = Regex("""^([a-zA-Z][a-zA-Z0-9+.-]*://)([^/@]+)@(.+)$""").find(url)
        if (match != null) {
            val userPass = match.groupValues[2].split(":", limit = 2)
            val cleanUrl = match.groupValues[1] + match.groupValues[3]
            return Cred(
                cleanUrl,
                URLDecoder.decode(userPass[0], StandardCharsets.UTF_8.name()),
                URLDecoder.decode(userPass.getOrElse(1) { "" }, StandardCharsets.UTF_8.name()),
            )
        }
        return Cred(url, configUser, configPass)
    }

    private data class Cred(val url: String, val username: String, val password: String)

    private companion object {
        const val TAG = "WebDavClient"
    }

    private class ProgressRequestBody(
        private val file: File,
        private val onProgress: (Long, Long) -> Unit,
    ) : RequestBody() {

        override fun contentType() = "application/octet-stream".toMediaType()

        override fun contentLength(): Long = file.length()

        override fun writeTo(sink: BufferedSink) {
            val total = contentLength()
            var written = 0L
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            file.inputStream().use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    sink.write(buffer, 0, read)
                    written += read
                    onProgress(written, total)
                }
            }
        }

        private companion object {
            const val DEFAULT_BUFFER_SIZE = 8192
        }
    }
}
