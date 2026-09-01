package com.bilifolder.downloader.engine

import com.bilifolder.downloader.data.model.EngineProgress
import com.bilifolder.downloader.data.model.EngineStatus
import com.bilifolder.downloader.data.model.EngineTask
import com.bilifolder.downloader.data.model.EngineTaskKind
import com.bilifolder.downloader.util.LogUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 内置下载器（设计 4.2.1，需求 6、19）。
 *
 * 恢复桌面版 `_download_stream`（`1.py#L730-L806`）语义：
 * - 断点续传：携带 `Range: bytes={size}-`；206 追加写入、416 删除残留重下、200 从头下载
 * - 限速：令牌桶控制读取速率（`setLimit(0)` 不限速）
 * - 取消保留已下载文件，供下次任务断点续传
 *
 * 单连接下载，视频流与音频流由上层分别创建任务并行执行。
 */
class OkHttpDownloadEngine(
    private val client: OkHttpClient = defaultClient(),
) : DownloadEngine {

    override val name: String = "内置下载器"

    override val supportsLimit: Boolean = true

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 运行中的任务：taskId -> 内部任务状态 */
    private val tasks = ConcurrentHashMap<String, InternalTask>()

    /** 全局限速（bytes/sec，0 = 不限速） */
    @Volatile
    private var limitBytesPerSec: Long = 0

    override suspend fun isAvailable(): Boolean = true

    override suspend fun download(uri: String, savePath: String, fileName: String): EngineTask {
        val taskId = UUID.randomUUID().toString()
        LogUtil.d(TAG, "download: 创建任务 $taskId -> $fileName")
        val internal = InternalTask(
            taskId = taskId,
            uri = uri,
            file = File(savePath),
            total = 0,
            job = null,
        )
        tasks[taskId] = internal

        // 启动下载协程；由调用方通过 query/cancel 管理
        val job = scope.launch {
            try {
                downloadStream(internal)
            } catch (e: CancellationException) {
                // 取消：保留已下载部分
            } catch (e: Exception) {
                internal.status = EngineStatus.ERROR
                internal.error = e.message
            }
        }
        internal.job = job
        internal.ready.complete(taskId)
        return EngineTask(taskId, EngineTaskKind.FILE, savePath)
    }

    override suspend fun query(task: EngineTask): EngineProgress {
        val t = tasks[task.id] ?: return EngineProgress(0, 0, EngineStatus.ERROR)
        return EngineProgress(
            downloaded = t.downloaded,
            total = if (t.total > 0) t.total else 0,
            status = t.status,
        )
    }

    override suspend fun pause(task: EngineTask) {
        tasks[task.id]?.let {
            it.pauseRequested = true
            it.status = EngineStatus.PAUSED
        }
    }

    override suspend fun resume(task: EngineTask) {
        tasks[task.id]?.let {
            it.pauseRequested = false
            it.status = EngineStatus.RUNNING
        }
    }

    override suspend fun cancel(task: EngineTask) {
        tasks.remove(task.id)?.let { internal ->
            internal.job?.cancel()
            // 保留已下载文件（断点续传），不删除
        }
    }

    override fun setLimit(bytesPerSec: Long) {
        limitBytesPerSec = bytesPerSec
    }

    override fun shutdown() {
        tasks.values.forEach { it.job?.cancel() }
        tasks.clear()
    }

    // ---------- 流下载核心（对应 1.py#L730-L806） ----------

    private suspend fun downloadStream(t: InternalTask) {
        val targetFile = t.file
        var downloaded = 0L
        if (targetFile.exists()) downloaded = targetFile.length()
        LogUtil.d(TAG, "downloadStream: ${targetFile.name} 已存在 $downloaded 字节（断点续传）")

        var attempt = 0
        while (true) {
            if (t.pauseRequested) {
                delay(500)
                continue
            }
            val requestBuilder = Request.Builder().url(t.uri)
            // 断点续传：携带 Range 头（对应 1.py#L739-L742）
            if (downloaded > 0) {
                requestBuilder.header("Range", "bytes=$downloaded-")
            }
            val request = requestBuilder.build()
            val resp = try {
                client.newCall(request).execute()
            } catch (e: IOException) {
                t.status = EngineStatus.ERROR
                t.error = "网络异常: ${e.message}"
                return
            }

            when (resp.code) {
                200 -> {
                    // 服务器不支持续传，从头下载
                    if (downloaded > 0) {
                        LogUtil.d(TAG, "downloadStream: ${targetFile.name} 服务器返回 200，从头下载")
                        downloaded = 0
                    }
                    resp.use { r ->
                        val total = r.body?.contentLength() ?: 0L
                        t.total = total
                        if (total in 0..downloaded) {
                            t.status = EngineStatus.DONE
                            return
                        }
                        writeBody(r, t, downloaded)
                    }
                    if (t.status == EngineStatus.ERROR) return
                    LogUtil.d(TAG, "downloadStream: ${targetFile.name} 完成，共 ${t.downloaded} 字节")
                    t.status = EngineStatus.DONE
                    return
                }
                206 -> {
                    resp.use { r ->
                        val contentRange = r.header("Content-Range") ?: ""
                        val total = contentRange.substringAfter('/').toLongOrNull()
                            ?: (downloaded + (r.body?.contentLength() ?: 0))
                        t.total = total
                        LogUtil.d(TAG, "downloadStream: ${targetFile.name} 206 续传，总大小 $total")
                        writeBody(r, t, downloaded)
                    }
                    if (t.status == EngineStatus.ERROR) return
                    LogUtil.d(TAG, "downloadStream: ${targetFile.name} 完成，共 ${t.downloaded} 字节")
                    t.status = EngineStatus.DONE
                    return
                }
                416 -> {
                    // 本地残留与服务器不匹配：删除后重下（对应 1.py#L761-L775）
                    resp.close()
                    attempt++
                    LogUtil.w(TAG, "downloadStream: ${targetFile.name} HTTP 416 残留无效，重试 $attempt/3")
                    if (attempt > 2) {
                        t.status = EngineStatus.ERROR
                        t.error = "HTTP 416 残留无效"
                        return
                    }
                    targetFile.delete()
                    downloaded = 0
                    continue
                }
                else -> {
                    resp.close()
                    LogUtil.e(TAG, "downloadStream: ${targetFile.name} HTTP ${resp.code}")
                    t.status = EngineStatus.ERROR
                    t.error = "HTTP ${resp.code}"
                    return
                }
            }
        }
    }

    private fun writeBody(resp: okhttp3.Response, t: InternalTask, startOffset: Long) {
        val body = resp.body ?: return
        val input = body.byteStream()
        try {
            RandomAccessFile(t.file, "rw").use { raf ->
                if (startOffset > 0) raf.seek(startOffset) else raf.setLength(0)
                t.downloaded = startOffset
                val buffer = ByteArray(DEFAULT_CHUNK_SIZE)
                val rateLimiter = RateLimiter(limitBytesPerSec)
                while (true) {
                    if (t.pauseRequested) {
                        Thread.sleep(500)
                        continue
                    }
                    if (t.job?.isCancelled == true) return
                    val read = input.read(buffer)
                    if (read == -1) break
                    raf.write(buffer, 0, read)
                    t.downloaded += read
                    rateLimiter.waitIfNeeded(read)
                }
            }
        } catch (e: IOException) {
            LogUtil.e(TAG, "writeBody: ${t.file.name} 写文件失败", e)
            t.status = EngineStatus.ERROR
            t.error = "写文件失败: ${e.message}"
        } finally {
            input.close()
        }
    }

    /** 令牌桶限速器（对应 1.py#L968-L976 的 _rate_limit） */
    private class RateLimiter(bytesPerSec: Long) {
        @Volatile
        private var rate: Double = bytesPerSec.toDouble()
        private var tokens: Double = bytesPerSec.toDouble()
        private var lastRefill: Long = System.nanoTime()

        fun waitIfNeeded(bytes: Int) {
            if (rate <= 0) return
            var remaining = bytes.toDouble()
            while (remaining > 0) {
                refill()
                if (tokens >= remaining) {
                    tokens -= remaining
                    remaining = 0.0
                } else {
                    remaining -= tokens
                    tokens = 0.0
                    val waitMs = (remaining / rate * 1000).toLong()
                    if (waitMs > 0) Thread.sleep(waitMs.coerceAtMost(1000))
                }
            }
        }

        private fun refill() {
            val now = System.nanoTime()
            val elapsed = (now - lastRefill) / 1_000_000_000.0
            if (elapsed > 0) {
                tokens = (tokens + elapsed * rate).coerceAtMost(rate * 2)
                lastRefill = now
            }
        }
    }

    private class InternalTask(
        val taskId: String,
        val uri: String,
        val file: File,
        @Volatile var total: Long,
        @Volatile var job: Job?,
    ) {
        val ready = CompletableDeferred<String>()
        @Volatile var downloaded: Long = 0
        @Volatile var status: EngineStatus = EngineStatus.RUNNING
        @Volatile var pauseRequested: Boolean = false
        @Volatile var error: String? = null
    }

    private companion object {
        const val TAG = "OkHttpDownloadEngine"
        const val DEFAULT_CHUNK_SIZE = 8192

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
