package com.bilifolder.downloader.engine

import com.bilifolder.downloader.data.model.EngineStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class OkHttpDownloadEngineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var engine: OkHttpDownloadEngine

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        engine = OkHttpDownloadEngine()
        engine.setLimit(0)
    }

    @After
    fun tearDown() {
        engine.shutdown()
        server.shutdown()
    }

    private suspend fun waitDone(task: com.bilifolder.downloader.data.model.EngineTask): EngineStatus {
        repeat(200) {
            val p = engine.query(task)
            if (p.status == EngineStatus.DONE || p.status == EngineStatus.ERROR) return p.status
            delay(50)
        }
        return EngineStatus.ERROR
    }

    @Test
    fun `200 从头下载完整内容`() = runBlocking {
        val content = ByteArray(2000) { it.toByte() }
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(okio.Buffer().write(content))
        )
        val file = File(tempFolder.root, "v.mp4")
        val task = engine.download(server.url("/v.mp4").toString(), file.absolutePath, "v.mp4")
        assertEquals(EngineStatus.DONE, waitDone(task))
        assertTrue(file.readBytes().contentEquals(content))
        // 首次请求不带 Range
        val request = server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)!!
        assertNull(request.getHeader("Range"))
    }

    @Test
    fun `206 断点续传追加写入`() = runBlocking {
        val first = ByteArray(100) { 1 }
        val second = ByteArray(100) { 2 }
        val file = File(tempFolder.root, "v.mp4")
        file.writeBytes(first)

        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 100-199/200")
                .setBody(okio.Buffer().write(second))
        )
        val task = engine.download(server.url("/v.mp4").toString(), file.absolutePath, "v.mp4")
        assertEquals(EngineStatus.DONE, waitDone(task))

        val request = server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)!!
        assertEquals("bytes=100-", request.getHeader("Range"))
        val result = file.readBytes()
        assertEquals(200, result.size)
        assertTrue(result.copyOfRange(0, 100).all { it == 1.toByte() })
        assertTrue(result.copyOfRange(100, 200).all { it == 2.toByte() })
    }

    @Test
    fun `416 残留无效删除后重下`() = runBlocking {
        val content = ByteArray(500) { 9 }
        // 第一次请求（带残留）→ 416；第二次（无残留）→ 200
        server.enqueue(MockResponse().setResponseCode(416).setBody(""))
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(okio.Buffer().write(content))
        )
        val file = File(tempFolder.root, "v.mp4")
        file.writeBytes(ByteArray(300) { 7 }) // 残留文件

        val task = engine.download(server.url("/v.mp4").toString(), file.absolutePath, "v.mp4")
        assertEquals(EngineStatus.DONE, waitDone(task))
        assertTrue(file.readBytes().contentEquals(content))
        // 第二次请求不带 Range（残留已删）
        server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)!!
        val second = server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)!!
        assertNull(second.getHeader("Range"))
    }

    @Test
    fun `限速生效`() = runBlocking {
        // 4KB 文件，限速 1000 B/s → 至少约 4 秒
        val content = ByteArray(4096) { it.toByte() }
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(okio.Buffer().write(content))
        )
        engine.setLimit(1000)
        val file = File(tempFolder.root, "v.mp4")
        val start = System.currentTimeMillis()
        val task = engine.download(server.url("/v.mp4").toString(), file.absolutePath, "v.mp4")
        assertEquals(EngineStatus.DONE, waitDone(task))
        val elapsed = System.currentTimeMillis() - start
        // 宽松断言：≥3 秒（留出调度误差）
        assertTrue("elapsed=$elapsed", elapsed >= 3000)
        assertTrue(file.readBytes().contentEquals(content))
    }

    @Test
    fun `pause 挂起 resume 继续`() = runBlocking {
        val content = ByteArray(5000) { it.toByte() }
        // 限速响应体使下载持续约 5 秒，确保 pause 在完成前生效
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(okio.Buffer().write(content))
                .throttleBody(1024, 1, java.util.concurrent.TimeUnit.SECONDS)
        )
        engine.setLimit(0)
        val file = File(tempFolder.root, "v.mp4")
        val task = engine.download(server.url("/v.mp4").toString(), file.absolutePath, "v.mp4")
        delay(200)
        engine.pause(task)
        assertEquals(EngineStatus.PAUSED, engine.query(task).status)
        engine.resume(task)
        assertEquals(EngineStatus.DONE, waitDone(task))
    }

    @Test
    fun `服务端错误标记 ERROR`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403).setBody("forbidden"))
        val file = File(tempFolder.root, "v.mp4")
        val task = engine.download(server.url("/v.mp4").toString(), file.absolutePath, "v.mp4")
        assertEquals(EngineStatus.ERROR, waitDone(task))
    }
}
