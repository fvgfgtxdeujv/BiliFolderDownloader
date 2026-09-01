package com.bilifolder.downloader.engine

import com.bilifolder.downloader.data.model.EngineProgress
import com.bilifolder.downloader.data.model.EngineStatus
import com.bilifolder.downloader.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Gopeed REST API 客户端（设计 4.2.2，需求 4、6、15、19）。
 *
 * 端点基于 gopeed 源码 `pkg/rest/server.go`：
 * - `POST /api/v1/tasks`：创建任务（body: `{"req":{"url":...},"opts":{"name":...,"path":...}}`）
 * - `GET /api/v1/tasks/{id}`：查询任务（status / progress.downloaded / meta.res.size）
 * - `PUT /api/v1/tasks/{id}/pause`、`PUT /api/v1/tasks/{id}/continue`：暂停/恢复
 * - `DELETE /api/v1/tasks/{id}`：删除任务（不删已下载文件）
 *
 * 认证：`X-Api-Token` 请求头（headless 入口配置的随机令牌，仅回环访问）。
 * 注：当前 gopeed 版本已移除带宽限速与 `/api/v1/settings` 限速端点（见设计 4.2 限速差异）。
 */
class GopeedTaskClient(
    private val baseUrl: String,
    private val apiToken: String,
) {

    private val json = Json { ignoreUnknownKeys = true }

    private companion object {
        const val TAG = "GopeedTaskClient"
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    /** Gopeed 任务状态 */
    enum class GopeedTaskStatus {
        READY, RUNNING, PAUSE, WAIT, DONE, ERROR, UNKNOWN;

        fun toEngineStatus(): EngineStatus = when (this) {
            READY, RUNNING, WAIT -> EngineStatus.RUNNING
            PAUSE -> EngineStatus.PAUSED
            DONE -> EngineStatus.DONE
            ERROR -> EngineStatus.ERROR
            UNKNOWN -> EngineStatus.ERROR
        }
    }

    /** Gopeed 任务查询结果 */
    data class TaskState(
        val id: String,
        val status: GopeedTaskStatus,
        val downloaded: Long,
        val total: Long,
    ) {
        fun toEngineProgress(): EngineProgress = EngineProgress(
            downloaded = downloaded,
            total = total,
            status = status.toEngineStatus(),
        )
    }

    /**
     * 创建单文件下载任务。
     * @param path 目标目录（绝对路径）
     * @param name 目标文件名
     * @return Gopeed 任务 ID
     */
    suspend fun createTask(url: String, path: String, name: String): String? = withContext(Dispatchers.IO) {
        val body = """
            {"req":{"url":"${url.escapeJson()}"},"opts":{"path":"${path.escapeJson()}","name":"${name.escapeJson()}"}}
        """.trimIndent()
        val request = Request.Builder()
            .url("$baseUrl/api/v1/tasks")
            .header("X-Api-Token", apiToken)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        val resp = execute(request) ?: run {
            LogUtil.w(TAG, "createTask: 接口失败 name=$name")
            return@withContext null
        }
        val code = resp["code"]?.jsonPrimitive?.int
        if (code != 0) {
            LogUtil.w(TAG, "createTask: 错误码 $code name=$name")
            return@withContext null
        }
        val taskId = resp["data"]?.jsonPrimitive?.content
        LogUtil.d(TAG, "createTask: 成功 taskId=$taskId name=$name")
        taskId
    }

    suspend fun queryTask(id: String): TaskState? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$baseUrl/api/v1/tasks/$id")
            .header("X-Api-Token", apiToken)
            .get()
            .build()
        val resp = execute(request) ?: return@withContext null
        if (resp["code"]?.jsonPrimitive?.int != 0) return@withContext null
        val task = resp["data"]?.jsonObject ?: return@withContext null
        val status = runCatching { GopeedTaskStatus.valueOf((task["status"]?.jsonPrimitive?.content ?: "").uppercase()) }
            .getOrDefault(GopeedTaskStatus.UNKNOWN)
        val downloaded = task["progress"]?.jsonObject?.get("downloaded")?.jsonPrimitive?.long ?: 0
        val total = resolveTotalSize(task)
        LogUtil.d(TAG, "queryTask: id=$id status=$status downloaded=$downloaded total=$total")
        TaskState(
            id = task["id"]?.jsonPrimitive?.content ?: id,
            status = status,
            downloaded = downloaded,
            total = total,
        )
    }

    suspend fun pauseTask(id: String) = withContext(Dispatchers.IO) {
        put("$baseUrl/api/v1/tasks/$id/pause")
    }

    suspend fun resumeTask(id: String) = withContext(Dispatchers.IO) {
        put("$baseUrl/api/v1/tasks/$id/continue")
    }

    suspend fun deleteTask(id: String) = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$baseUrl/api/v1/tasks/$id")
                .header("X-Api-Token", apiToken)
                .delete()
                .build()
            val ok = client.newCall(request).execute().use { it.code in 200..299 }
            LogUtil.d(TAG, "deleteTask: id=$id 结果=$ok")
            ok
        } catch (e: IOException) {
            LogUtil.w(TAG, "deleteTask: id=$id 网络异常", e)
            false
        }
    }

    /** 健康检查：任意 /api 端点返回 2xx/401 均视为服务就绪 */
    suspend fun healthCheck(): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$baseUrl/api/v1/tasks")
                .header("X-Api-Token", apiToken)
                .get()
                .build()
            client.newCall(request).execute().use { it.code in 200..299 }
        } catch (e: IOException) {
            false
        }
    }

    private fun put(url: String) {
        try {
            val request = Request.Builder()
                .url(url)
                .header("X-Api-Token", apiToken)
                .put("{}".toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(request).execute().use { }
        } catch (e: IOException) {
            // 忽略暂停/恢复失败，由上层重试
        }
    }

    /** 总大小：优先 meta.res.size，其次 meta.res.files[0].size */
    private fun resolveTotalSize(task: JsonObject): Long {
        val res = task["meta"]?.jsonObject?.get("res")?.jsonObject ?: return 0
        res["size"]?.jsonPrimitive?.long?.let { if (it > 0) return it }
        val files = res["files"]?.jsonArray ?: return 0
        return files.firstOrNull()?.jsonObject?.get("size")?.jsonPrimitive?.long ?: 0
    }

    private fun execute(request: Request): JsonObject? {
        return try {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return null
                json.parseToJsonElement(resp.body?.string() ?: return null) as? JsonObject
            }
        } catch (e: IOException) {
            null
        }
    }

    private fun String.escapeJson(): String =
        replace("\\", "\\\\").replace("\"", "\\\"")
}
