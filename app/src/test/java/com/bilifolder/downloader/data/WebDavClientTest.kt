package com.bilifolder.downloader.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WebDavClientTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var client: WebDavClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = WebDavClient()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun baseUrl(): String = server.url("/dav").toString().trimEnd('/')

    @Test
    fun `testConnection PROPFIND 2xx 通过`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(207).setBody("<multistatus/>"))
        assertTrue(client.testConnection(baseUrl(), "u", "p"))
        val request = server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)!!
        assertEquals("PROPFIND", request.method)
        assertEquals("0", request.getHeader("Depth"))
        assertEquals("Basic dTpw", request.getHeader("Authorization"))
    }

    @Test
    fun `testConnection 401 失败`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        assertFalse(client.testConnection(baseUrl(), "u", "p"))
    }

    @Test
    fun `mkdir 递归创建`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(405)) // 父目录已存在
        server.enqueue(MockResponse().setResponseCode(201)) // 创建子目录

        assertTrue(client.mkdir(baseUrl(), "bili_folder_downloader/20260821_xxx", "u", "p"))
        val first = server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)!!
        assertEquals("/dav/bili_folder_downloader", first.path)
        assertEquals("MKCOL", first.method)
        val second = server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)!!
        assertEquals("/dav/bili_folder_downloader/20260821_xxx", second.path)
        assertEquals("MKCOL", second.method)
    }

    @Test
    fun `uploadZip 成功与进度回调`() = runBlocking {
        val content = ByteArray(2048) { it.toByte() }
        val zip = File(tempFolder.root, "1.zip")
        zip.writeBytes(content)
        server.enqueue(MockResponse().setResponseCode(201))

        var lastProgress = 0L to 0L
        val result = client.uploadZip(baseUrl(), "bili_folder_downloader/1.zip", "u", "p", zip) { done, total ->
            lastProgress = done to total
        }
        assertEquals(WebDavClient.UploadResult.OK, result)
        assertEquals(content.size.toLong(), lastProgress.first)
        assertEquals(content.size.toLong(), lastProgress.second)

        val request = server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)!!
        assertEquals("PUT", request.method)
        assertEquals("/dav/bili_folder_downloader/1.zip", request.path)
        assertTrue(request.body.readByteArray().contentEquals(content))
    }

    @Test
    fun `uploadZip 401 归类为 AUTH`() = runBlocking {
        val zip = File(tempFolder.root, "1.zip")
        zip.writeBytes(ByteArray(100))
        server.enqueue(MockResponse().setResponseCode(401))
        val result = client.uploadZip(baseUrl(), "x.zip", "u", "p", zip) { _, _ -> }
        assertEquals(WebDavClient.UploadResult.AUTH, result)
    }

    @Test
    fun `内嵌凭证优先于配置`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(207).setBody("<multistatus/>"))
        val url = baseUrl().replace("http://", "http://embedded:secret@")
        assertTrue(client.testConnection(url, "config", "pass"))
        val request = server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)!!
        // embedded:secret base64 = ZW1iZWRkZWQ6c2VjcmV0
        assertEquals("Basic ZW1iZWRkZWQ6c2VjcmV0", request.getHeader("Authorization"))
        // 路径不含凭证
        assertEquals("/dav", request.path)
    }
}
