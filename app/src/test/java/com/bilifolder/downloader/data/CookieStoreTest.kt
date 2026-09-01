package com.bilifolder.downloader.data

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CookieStoreTest {

    private lateinit var store: CookieStore

    @Before
    fun setUp() {
        // Robolectric 无 AndroidKeyStore，注入固定的 JVM AES 密钥（须复用同一密钥）
        val fakeKey = javax.crypto.KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        store = CookieStore(
            ApplicationProvider.getApplicationContext(),
            keyProvider = SecretKeyProvider { fakeKey },
        )
        store.clearSession()
        store.clearWebDavPassword()
    }

    @Test
    fun `会话存取还原`() {
        store.saveSession("sessdata_value", "jct_value", "12345")
        assertEquals("sessdata_value", store.sessdata())
        assertEquals("jct_value", store.biliJct())
        assertEquals("12345", store.mid())
    }

    @Test
    fun `明文不出现在 SharedPreferences`() {
        val prefs = ApplicationProvider.getApplicationContext<android.content.Context>()
            .getSharedPreferences("bili_secure_prefs", android.content.Context.MODE_PRIVATE)
        store.saveSession("secret_sessdata", "secret_jct", "999")

        val raw = prefs.getString(CookieStore.Keys.SESSDATA, "")
        assertTrue("密文不应包含明文", raw?.contains("secret_sessdata") != true)
        assertTrue("密文应为 iv.cipher 两段 base64", raw?.split(".")?.size == 2)
    }

    @Test
    fun `清理会话`() {
        store.saveSession("a", "b", "c")
        store.clearSession()
        assertNull(store.sessdata())
        assertNull(store.biliJct())
        assertNull(store.mid())
    }

    @Test
    fun `WebDAV 密码存取与空白清理`() {
        store.saveWebDavPassword("dav-pass")
        assertEquals("dav-pass", store.webDavPassword())
        store.saveWebDavPassword("")
        assertNull(store.webDavPassword())
    }

    @Test
    fun `篡改密文解密失败并清理`() {
        store.saveSession("original", "jct", "1")
        val prefs = ApplicationProvider.getApplicationContext<android.content.Context>()
            .getSharedPreferences("bili_secure_prefs", android.content.Context.MODE_PRIVATE)
        val raw = prefs.getString(CookieStore.Keys.SESSDATA, "")!!
        // 翻转密文段（不影响格式），解密应失败
        val parts = raw.split(".")
        val tampered = parts[0] + "." + parts[1].reversed()
        prefs.edit().putString(CookieStore.Keys.SESSDATA, tampered).apply()

        assertNull(store.sessdata())
        assertNull("损坏数据应被清理", prefs.getString(CookieStore.Keys.SESSDATA, null))
    }

    @Test
    fun `两次加密密文不同`() {
        store.saveSession("same", "same", "same")
        val prefs = ApplicationProvider.getApplicationContext<android.content.Context>()
            .getSharedPreferences("bili_secure_prefs", android.content.Context.MODE_PRIVATE)
        val first = prefs.getString(CookieStore.Keys.SESSDATA, "")
        store.saveSession("same", "same", "same")
        val second = prefs.getString(CookieStore.Keys.SESSDATA, "")
        assertNotEquals(first, second)
    }
}
