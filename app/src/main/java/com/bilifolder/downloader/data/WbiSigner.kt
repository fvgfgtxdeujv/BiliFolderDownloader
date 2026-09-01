package com.bilifolder.downloader.data

import java.security.MessageDigest

/**
 * WBI 签名预留（设计 4.1）。
 *
 * 收藏夹接口当前不强制 WBI 签名；当未来接口强制签名时，
 * 通过 [WbiSigner] 为请求参数生成 `wts` + `w_rid`。
 */
interface WbiSigner {
    /** 从 nav 接口更新密钥，返回是否成功 */
    fun updateKeys(imgUrl: String, subUrl: String): Boolean

    /** 对参数生成签名，返回带 wts/w_rid 的完整参数表 */
    fun sign(params: Map<String, String>): Map<String, String>

    fun isReady(): Boolean
}

/**
 * WBI 签名实现（bilibili-API-collect 算法）：
 * 1. 取 img_key/sub_key 前 32 字符拼接为 mixin_key
 * 2. 参数按 key 排序并追加 wts 时间戳
 * 3. w_rid = md5(query + mixin_key)
 */
class WbiSignerImpl : WbiSigner {

    @Volatile
    private var mixinKey: String? = null

    override fun updateKeys(imgUrl: String, subUrl: String): Boolean {
        val imgKey = extractKey(imgUrl) ?: return false
        val subKey = extractKey(subUrl) ?: return false
        mixinKey = (imgKey + subKey).take(32)
        return true
    }

    override fun isReady(): Boolean = mixinKey != null

    override fun sign(params: Map<String, String>): Map<String, String> {
        val key = mixinKey ?: return params
        val now = System.currentTimeMillis() / 1000
        val withTs = params + ("wts" to now.toString())
        val query = withTs.entries
            .sortedBy { it.key }
            .joinToString("&") { "${it.key}=${it.value}" }
        val wRid = md5(query + key)
        return withTs + ("w_rid" to wRid)
    }

    private fun extractKey(url: String): String? {
        // 形如 https://i0.hdslb.com/bfs/wbi/xxx.png
        val name = url.substringAfterLast('/')
        return name.substringBefore('.').ifBlank { null }
    }

    private fun md5(input: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
