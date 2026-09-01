package com.bilifolder.downloader.data

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BiliApiClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: BiliApiClient
    private lateinit var cookieStore: CookieStore
    private lateinit var session: MutableMap<String, String>

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        // Robolectric 无 AndroidKeyStore，注入固定的 JVM AES 密钥（须复用同一密钥）
        val fakeKey = javax.crypto.KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        cookieStore = CookieStore(
            ApplicationProvider.getApplicationContext(),
            keyProvider = SecretKeyProvider { fakeKey },
        )
        cookieStore.clearSession()
        // MockWebServer 使用 IP host，OkHttp 不会为 IP 解析 Set-Cookie；
        // 测试注入宽松 CookieJar 直接写入内存会话 map（生产走 InMemoryCookieJar 域名校验）
        session = mutableMapOf()
        client = BiliApiClient(
            cookieStore = cookieStore,
            apiBaseUrl = server.url("/").toString().trimEnd('/'),
            passportBaseUrl = server.url("/").toString().trimEnd('/'),
            sessionCookies = session,
            cookieJar = object : okhttp3.CookieJar {
                override fun saveFromResponse(url: okhttp3.HttpUrl, cookies: List<okhttp3.Cookie>) {
                    cookies.forEach { session[it.name] = it.value }
                }

                override fun loadForRequest(url: okhttp3.HttpUrl): List<okhttp3.Cookie> = emptyList()
            },
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `getQrCode 解析 url 与 key`() = runBlocking {
        server.enqueue(
            MockResponse().setBody("""{"code":0,"data":{"url":"https://passport.bilibili.com/h5/login?navhide=1&qrcode_key=KEY123","qrcode_key":"KEY123"}}""")
        )
        val qr = client.getQrCode()
        assertNotNull(qr)
        assertEquals("KEY123", qr!!.qrcodeKey)
        assertTrue(qr.url.contains("KEY123"))
    }

    @Test
    fun `pollLogin 成功提取并持久化 Cookie`() = runBlocking {
        // 预置内存会话，模拟 CookieJar 已从真实域名响应解析 Set-Cookie
        // （MockWebServer 用 IP host，OkHttp 不为其解析 Cookie）
        session["SESSDATA"] = "abc123"
        session["bili_jct"] = "csrf456"
        session["DedeUserID"] = "98765"
        server.enqueue(
            MockResponse().setBody("""{"code":0,"data":{"code":0,"message":"ok"}}""")
        )
        val result = client.pollLogin("KEY")
        assertEquals(0, result.code)
        assertEquals("abc123", cookieStore.sessdata())
        assertEquals("csrf456", cookieStore.biliJct())
        assertEquals("98765", cookieStore.mid())
    }

    @Test
    fun `pollLogin 二维码过期返回 86038`() = runBlocking {
        server.enqueue(
            MockResponse().setBody("""{"code":0,"data":{"code":86038,"message":"二维码已失效"}}""")
        )
        assertEquals(86038, client.pollLogin("KEY").code)
    }

    @Test
    fun `validateAndGetMid 返回 MID`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"code":0,"data":{"mid":111}}"""))
        assertEquals(111L, client.validateAndGetMid())
    }

    @Test
    fun `getFolders 分页终止`() = runBlocking {
        // 第一页 20 条 → 第二页 2 条（<20）终止
        server.enqueue(folderListPage(20))
        server.enqueue(folderListPage(2))
        val folders = client.getFolders(123L)
        assertEquals(22, folders.size)
        assertEquals(1L, folders.first().mediaId)
    }

    @Test
    fun `getFolderVideos 错误码返回 error`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"code":-403,"message":"访问权限不足"}"""))
        val page = client.getFolderVideos(1L, 1)
        assertTrue(page.error != null)
        assertTrue(page.videos.isEmpty())
    }

    @Test
    fun `getVideoUrl 无流返回 null`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"code":0,"data":{"dash":{"video":[],"audio":[]}}}"""))
        assertNull(client.getVideoUrl("BV1", 1, 80))
    }

    @Test
    fun `getVideoUrl DASH 解析选中流`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """
                {"code":0,"data":{"dash":{
                  "video":[{"id":80,"baseUrl":"https://v.example/1080.mp4"},{"id":64,"baseUrl":"https://v.example/720.mp4"}],
                  "audio":[{"id":30280,"baseUrl":"https://a.example/a.m4s"}]
                }}}
                """.trimIndent()
            )
        )
        val play = client.getVideoUrl("BV1", 1, 80)
        assertNotNull(play)
        assertEquals("https://v.example/1080.mp4", play!!.videoUrl)
        assertEquals("https://a.example/a.m4s", play.audioUrl)
        assertEquals(80, play.videoQuality)
    }

    @Test
    fun `deleteFolderVideo 缺 bili_jct 返回 false`() = runBlocking {
        val ok = client.deleteFolderVideo(1L, 2L)
        assertEquals(false, ok)
    }

    private fun folderPage(count: Int, total: Int): MockResponse {
        val items = (1..count).joinToString(",") { i ->
            """{"bvid":"BV$i","title":"视频$i","cid":$i,"id":$i}"""
        }
        return MockResponse().setBody(
            """{"code":0,"data":{"info":{"media_count":$total},"medias":[$items]}}"""
        )
    }

    /** getFolders 的分页响应：data.list */
    private fun folderListPage(count: Int): MockResponse {
        val items = (1..count).joinToString(",") { i ->
            """{"id":$i,"title":"收藏夹$i","media_count":${10 + i}}"""
        }
        return MockResponse().setBody(
            """{"code":0,"data":{"list":[$items]}}"""
        )
    }
}
