package com.bilifolder.downloader.engine

import com.bilifolder.downloader.data.model.EngineStatus
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GopeedTaskClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: GopeedTaskClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = GopeedTaskClient(HttpGopeedTransport(server.url("/").toString().trimEnd('/'), "test-token"))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `createTask 成功返回任务 ID 并携带认证头`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"code":0,"message":"success","data":"task-123"}""")
        )
        val id = client.createTask("https://example.com/v.mp4", "/data", "v.mp4")
        assertEquals("task-123", id)

        val request = server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)!!
        assertEquals("POST", request.method)
        assertEquals("/api/v1/tasks", request.path)
        assertEquals("test-token", request.getHeader("X-Api-Token"))
        val body = request.body.readUtf8()
        assertTrue(body.contains("v.mp4"))
        assertTrue(body.contains("/data"))
    }

    @Test
    fun `createTask 把请求头写入 extra`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"code":0,"message":"success","data":"task-h"}""")
        )
        client.createTask(
            "https://cn.example.bilivideo.com/v.m4s",
            "/data",
            "v.m4s",
            mapOf("User-Agent" to "UA-1", "Referer" to "https://www.bilibili.com/"),
        )
        val body = server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)!!.body.readUtf8()
        assertTrue(body.contains("\"extra\""))
        assertTrue(body.contains("\"header\""))
        assertTrue(body.contains("\"User-Agent\":\"UA-1\""))
        assertTrue(body.contains("Referer"))
    }

    @Test
    fun `createTask 失败返回 null`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"code":-1,"message":"bad request","data":null}""")
        )
        val id = client.createTask("https://example.com/v.mp4", "/data", "v.mp4")
        assertNull(id)
    }

    @Test
    fun `queryTask 解析进度与状态`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "code": 0,
                      "data": {
                        "id": "task-1",
                        "status": "running",
                        "progress": {"downloaded": 100, "speed": 50},
                        "meta": {"res": {"size": 200}}
                      }
                    }
                    """.trimIndent()
                )
        )
        val state = client.queryTask("task-1")
        assertNotNull(state)
        assertEquals("task-1", state!!.id)
        assertEquals(GopeedTaskClient.GopeedTaskStatus.RUNNING, state.status)
        assertEquals(100L, state.downloaded)
        assertEquals(200L, state.total)
        assertEquals(EngineStatus.RUNNING, state.toEngineProgress().status)
    }

    @Test
    fun `queryTask 支持 files 数组总大小与 done 状态`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "code": 0,
                      "data": {
                        "id": "task-2",
                        "status": "done",
                        "progress": {"downloaded": 500},
                        "meta": {"res": {"files": [{"size": 500}]}}
                      }
                    }
                    """.trimIndent()
                )
        )
        val state = client.queryTask("task-2")
        assertEquals(GopeedTaskClient.GopeedTaskStatus.DONE, state!!.status)
        assertEquals(500L, state.total)
        assertEquals(EngineStatus.DONE, state.toEngineProgress().status)
    }

    @Test
    fun `queryTask meta 为 null 时总大小为 0 且不崩溃`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """{"code":0,"data":{"id":"task-3","status":"ready","progress":{"downloaded":0},"meta":null}}"""
                )
        )
        val state = client.queryTask("task-3")
        assertNotNull(state)
        assertEquals(GopeedTaskClient.GopeedTaskStatus.READY, state!!.status)
        assertEquals(0L, state.total)
    }

    @Test
    fun `queryTask data 为 null 时返回 null`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"code":0,"data":null}"""))
        assertNull(client.queryTask("task-x"))
    }

    @Test
    fun `pause resume delete 调用正确端点`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"code":0}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"code":0}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"code":0}"""))

        client.pauseTask("t1")
        client.resumeTask("t1")
        val deleted = client.deleteTask("t1")

        assertEquals("/api/v1/tasks/t1/pause", server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)!!.path)
        assertEquals("/api/v1/tasks/t1/continue", server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)!!.path)
        val delReq = server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)!!
        assertEquals("/api/v1/tasks/t1", delReq.path)
        assertEquals("DELETE", delReq.method)
        assertTrue(deleted)
    }

    @Test
    fun `healthCheck 网络异常返回 false`() = runBlocking {
        server.shutdown()
        val badClient = GopeedTaskClient(HttpGopeedTransport("http://127.0.0.1:1", "t"))
        assertFalse(badClient.healthCheck())
    }
}
