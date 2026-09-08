package com.bilifolder.downloader.data

import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * InMemoryCookieJar 域名收集过滤回归测试。
 *
 * 背景：登录 poll 走 passport.bilibili.com，若只认 api.bilibili.com，
 * 登录成功响应的 SESSDATA/bili_jct/DedeUserID 会被全部丢弃（曾导致"登录成功但
 * 会话保存 mid 为空"）。此处锁定"B 站家族域"过滤语义，防止回归。
 */
class InMemoryCookieJarTest {

    private fun cookie(name: String, value: String, domain: String): Cookie =
        Cookie.Builder().name(name).value(value).domain(domain).path("/").build()

    @Test
    fun `passport 域登录响应 Cookie 会被收集`() {
        val store = mutableMapOf<String, String>()
        val jar = InMemoryCookieJar(store)
        val url = "https://passport.bilibili.com/x/passport-login/web/qrcode/poll".toHttpUrl()
        jar.saveFromResponse(
            url,
            listOf(
                cookie("SESSDATA", "sess-abc", "passport.bilibili.com"),
                cookie("bili_jct", "csrf-xyz", "passport.bilibili.com"),
                cookie("DedeUserID", "123456789", "passport.bilibili.com"),
            )
        )
        assertEquals("sess-abc", store["SESSDATA"])
        assertEquals("csrf-xyz", store["bili_jct"])
        assertEquals("123456789", store["DedeUserID"])
    }

    @Test
    fun `api 域 Cookie 照常收集`() {
        val store = mutableMapOf<String, String>()
        val jar = InMemoryCookieJar(store)
        val url = "https://api.bilibili.com/x/frontend/finger/spi".toHttpUrl()
        jar.saveFromResponse(url, listOf(cookie("buvid3", "b3", "api.bilibili.com")))
        assertEquals("b3", store["buvid3"])
    }

    @Test
    fun `非 B 站域 Cookie 被忽略`() {
        val store = mutableMapOf<String, String>()
        val jar = InMemoryCookieJar(store)
        val url = "https://evil.example.com/x".toHttpUrl()
        jar.saveFromResponse(url, listOf(cookie("SESSDATA", "steal", "evil.example.com")))
        assertTrue(store.isEmpty())
    }

    @Test
    fun `isBiliFamily 边界判定`() {
        assertTrue("bilibili.com".isBiliFamily())
        assertTrue("passport.bilibili.com".isBiliFamily())
        assertTrue("api.bilibili.com".isBiliFamily())
        assertTrue("sub.deep.bilibili.com".isBiliFamily())
        // 形近域名不得误收
        assertFalse("evilbilibili.com".isBiliFamily())
        assertFalse("bilibili.com.evil.com".isBiliFamily())
        assertFalse("api.bilibili.com.cn".isBiliFamily())
        assertFalse("example.com".isBiliFamily())
    }
}
