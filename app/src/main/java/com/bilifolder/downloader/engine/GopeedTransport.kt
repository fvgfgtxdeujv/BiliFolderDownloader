package com.bilifolder.downloader.engine

import com.bilifolder.downloader.util.LogUtil
import com.gopeed.libgopeed.InvokeResultListener
import com.gopeed.libgopeed.Libgopeed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

/**
 * Gopeed 请求传输结果。
 *
 * [success] 表示传输层是否成功送达；业务错误（如任务不存在）仍以 `success=true` 返回，
 * 其错误码体现在 [payload] 的 `code` 字段中（与 Gopeed 的 `{code,message,data}` 约定一致）。
 */
data class GopeedTransportResult(
    val success: Boolean,
    val payload: String,
)

/**
 * Gopeed REST 请求传输抽象。
 *
 * 存在两种实现：
 * - [NativeGopeedTransport]：进程内直连（Android 正式运行路径），经 gomobile 绑定的
 *   `Libgopeed.invokeAsync` 调用 gopeed 内部的 `rest.Dispatch`，不占用端口、无需令牌。
 * - [HttpGopeedTransport]：走 HTTP 回环（保留用于单元测试与将来外部 API server 形态）。
 */
interface GopeedTransport {
    suspend fun invoke(
        method: String,
        path: String,
        query: String = "",
        body: String = "",
    ): GopeedTransportResult

    companion object {
        private const val JSON = "application/json"
        val JSON_MEDIA_TYPE = JSON.toMediaType()
    }
}

/**
 * 进程内传输：包装 gomobile 生成的异步回调 API 为挂起函数。
 *
 * gomobile 的 [Libgopeed.invokeAsync] 通过 [InvokeResultListener] 在后台线程回调，
 * 这里用 [suspendCancellableCoroutine] 把单次回调桥接为一次挂起调用。
 */
object NativeGopeedTransport : GopeedTransport {

    private const val TAG = "GopeedTransport"
    private val nextRequestId = AtomicLong(1)

    override suspend fun invoke(
        method: String,
        path: String,
        query: String,
        body: String,
    ): GopeedTransportResult = suspendCancellableCoroutine { cont ->
        val requestId = nextRequestId.getAndIncrement()
        val listener = InvokeResultListener { _, success, payload ->
            if (cont.isActive) {
                cont.resume(GopeedTransportResult(success, payload.orEmpty()))
            }
        }
        try {
            Libgopeed.invokeAsync(method, path, query, body, requestId, listener)
        } catch (t: Throwable) {
            // 绑定层未送达（如 native 运行时未启动、回调注册失败）
            LogUtil.e(TAG, "invoke 失败: $method $path", t)
            if (cont.isActive) {
                cont.resume(GopeedTransportResult(false, t.message.orEmpty()))
            }
        }
    }
}

/**
 * HTTP 回环传输：与旧的 REST 客户端行为一致（`X-Api-Token` 认证）。
 * 仅用于单元测试与需要独立 API server 的场景。
 */
class HttpGopeedTransport(
    private val baseUrl: String,
    private val apiToken: String,
) : GopeedTransport {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    override suspend fun invoke(
        method: String,
        path: String,
        query: String,
        body: String,
    ): GopeedTransportResult = withContext(Dispatchers.IO) {
        val url = baseUrl + path + if (query.isEmpty()) "" else "?$query"
        val requestBody = when (method.uppercase()) {
            "POST", "PUT", "PATCH" -> body.toRequestBody(GopeedTransport.JSON_MEDIA_TYPE)
            else -> null
        }
        val request = Request.Builder()
            .url(url)
            .header("X-Api-Token", apiToken)
            .method(method.uppercase(), requestBody)
            .build()
        try {
            client.newCall(request).execute().use { resp ->
                GopeedTransportResult(resp.isSuccessful, resp.body?.string().orEmpty())
            }
        } catch (e: IOException) {
            GopeedTransportResult(false, e.message.orEmpty())
        }
    }
}
