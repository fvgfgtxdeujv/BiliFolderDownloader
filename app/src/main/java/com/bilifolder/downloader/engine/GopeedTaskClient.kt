package com.bilifolder.downloader.engine

import com.bilifolder.downloader.data.model.EngineProgress
import com.bilifolder.downloader.data.model.EngineStatus
import com.bilifolder.downloader.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Gopeed REST 客户端（设计 4.2.2，需求 4、6、15、19）。
 *
 * 端点基于 gopeed 源码 `pkg/api/service.go` 路由表：
 * - `POST /api/v1/tasks`：创建任务（body: `{"req":{"url":...},"opts":{"name":...,"path":...}}`）
 * - `GET /api/v1/tasks/{id}`：查询任务（status / progress.downloaded / meta.res.size）
 * - `PUT /api/v1/tasks/{id}/pause`、`PUT /api/v1/tasks/{id}/continue`：暂停/恢复
 * - `DELETE /api/v1/tasks/{id}`：删除任务（不删已下载文件）
 *
 * 请求经 [GopeedTransport] 发送：正式运行时为进程内直连（[NativeGopeedTransport]），
 * 单测/外部服务场景为 HTTP 回环（[HttpGopeedTransport]）。两条通道共用同一套 gopeed
 * 路由与 `{code,message,data}` 响应约定。
 */
class GopeedTaskClient(
    private val transport: GopeedTransport = NativeGopeedTransport,
) {

    private val json = Json { ignoreUnknownKeys = true }

    private companion object {
        const val TAG = "GopeedTaskClient"
    }

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
    suspend fun createTask(
        url: String,
        path: String,
        name: String,
        headers: Map<String, String> = emptyMap(),
    ): String? = withContext(Dispatchers.IO) {
        // 通过 req.extra.header 下发请求头（bilivideo CDN 拒绝默认 UA，会 403）
        val extra = if (headers.isEmpty()) "" else buildString {
            append(",\"extra\":{\"method\":\"GET\",\"header\":{")
            append(headers.entries.joinToString(",") { (k, v) -> "\"${k.escapeJson()}\":\"${v.escapeJson()}\"" })
            append("},\"body\":\"\"}")
        }
        val body = """
            {"req":{"url":"${url.escapeJson()}"$extra},"opts":{"path":"${path.escapeJson()}","name":"${name.escapeJson()}"}}
        """.trimIndent()
        val resp = execute("POST", "/api/v1/tasks", body = body) ?: run {
            LogUtil.w(TAG, "createTask: 接口失败 name=$name")
            return@withContext null
        }
        val code = resp["code"]?.jsonPrimitive?.int
        if (code != 0) {
            LogUtil.w(TAG, "createTask: 错误码 $code name=$name")
            return@withContext null
        }
        val taskId = (resp["data"] as? JsonPrimitive)?.content
            ?.takeIf { it.isNotBlank() && it != "null" }
        LogUtil.d(TAG, "createTask: 成功 taskId=$taskId name=$name")
        taskId
    }

    suspend fun queryTask(id: String): TaskState? = withContext(Dispatchers.IO) {
        val resp = execute("GET", "/api/v1/tasks/$id") ?: return@withContext null
        if (resp["code"]?.jsonPrimitive?.int != 0) return@withContext null
        val task = resp["data"] as? JsonObject ?: return@withContext null
        val status = runCatching { GopeedTaskStatus.valueOf((task["status"]?.jsonPrimitive?.content ?: "").uppercase()) }
            .getOrDefault(GopeedTaskStatus.UNKNOWN)
        val downloaded = ((task["progress"] as? JsonObject)?.get("downloaded") as? JsonPrimitive)?.longOrNull ?: 0
        val total = resolveTotalSize(task)
        LogUtil.d(TAG, "queryTask: id=$id status=$status downloaded=$downloaded total=$total")
        TaskState(
            id = task["id"]?.jsonPrimitive?.content ?: id,
            status = status,
            downloaded = downloaded,
            total = total,
        )
    }

    suspend fun pauseTask(id: String) {
        withContext(Dispatchers.IO) { execute("PUT", "/api/v1/tasks/$id/pause", body = "{}") }
    }

    suspend fun resumeTask(id: String) {
        withContext(Dispatchers.IO) { execute("PUT", "/api/v1/tasks/$id/continue", body = "{}") }
    }

    suspend fun deleteTask(id: String): Boolean = withContext(Dispatchers.IO) {
        val resp = execute("DELETE", "/api/v1/tasks/$id")
        val ok = resp != null && resp["code"]?.jsonPrimitive?.int == 0
        LogUtil.d(TAG, "deleteTask: id=$id 结果=$ok")
        ok
    }

    /** 健康检查：任务列表接口能正常返回即视为服务就绪 */
    suspend fun healthCheck(): Boolean = withContext(Dispatchers.IO) {
        transport.invoke("GET", "/api/v1/tasks").success
    }

    /** 总大小：优先 meta.res.size，其次 meta.res.files[0].size；任务元数据未就绪（meta=null）时返回 0 */
    private fun resolveTotalSize(task: JsonObject): Long {
        val res = (task["meta"] as? JsonObject)?.get("res") as? JsonObject ?: return 0
        (res["size"] as? JsonPrimitive)?.longOrNull?.let { if (it > 0) return it }
        val files = res["files"] as? JsonArray ?: return 0
        val first = files.firstOrNull() as? JsonObject ?: return 0
        return (first["size"] as? JsonPrimitive)?.longOrNull ?: 0
    }

    private suspend fun execute(
        method: String,
        path: String,
        query: String = "",
        body: String = "",
    ): JsonObject? {
        val target = path + if (query.isBlank()) "" else "?$query"
        LogUtil.d(TAG, "REQ $method $target" + if (body.isNotBlank()) " body=$body" else "")
        val result = transport.invoke(method, path, query, body)
        if (!result.success) {
            LogUtil.w(TAG, "REQ FAIL $method $target: ${result.payload}")
            return null
        }
        LogUtil.d(TAG, "RESP $method $target body=${result.payload}")
        return runCatching {
            json.parseToJsonElement(result.payload) as? JsonObject
        }.getOrNull()
    }

    private fun String.escapeJson(): String =
        replace("\\", "\\\\").replace("\"", "\\\"")
}
