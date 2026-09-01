package com.bilifolder.downloader.data

import com.bilifolder.downloader.data.model.Folder
import com.bilifolder.downloader.data.model.VideoInfo
import com.bilifolder.downloader.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * B 站 API 客户端（设计 4.1），对应桌面版 `BilibiliClient`（`1.py#L59-L370`）。
 *
 * 用 OkHttp 重新实现：二维码登录、Cookie 提取与验证、收藏夹列表、
 * 收藏夹视频分页、DASH 播放地址、收藏夹删除。Cookie 会话语义由
 * 内存 CookieJar + [CookieStore] 持久化还原。
 */
class BiliApiClient(
    private val cookieStore: CookieStore,
    private val apiBaseUrl: String = "https://api.bilibili.com",
    private val passportBaseUrl: String = "https://passport.bilibili.com",
    private val sessionCookies: MutableMap<String, String> = mutableMapOf(),
    private val cookieJar: CookieJar? = null,
) {

    private val json = Json { ignoreUnknownKeys = true }

    private companion object {
        const val TAG = "BiliApiClient"
    }

    // 浏览器 UA（与桌面版 1.py#L63 一致）
    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .cookieJar(cookieJar ?: InMemoryCookieJar(sessionCookies, apiBaseUrl.toHttpUrl().host))
        .addInterceptor { chain ->
            val builder = chain.request().newBuilder()
                .header("User-Agent", userAgent)
                .header("Referer", "https://www.bilibili.com/")
            sessionCookies.forEach { (name, value) ->
                builder.addHeader("Cookie", "$name=$value")
            }
            chain.proceed(builder.build())
        }
        .build()

    init {
        // 启动时从加密存储加载已保存 Cookie（需求 2）
        cookieStore.sessdata()?.let { sessionCookies["SESSDATA"] = it }
        cookieStore.biliJct()?.let { sessionCookies["bili_jct"] = it }
        cookieStore.mid()?.let { sessionCookies["DedeUserID"] = it }
    }

    // ---------- 登录 ----------

    /** 二维码数据（需求 1） */
    data class QrCodeData(val url: String, val qrcodeKey: String)

    /**
     * 轮询登录结果。
     * @param code B 站轮询状态码：0=成功、86038=过期、86090=已扫码待确认、86101=未扫码
     */
    data class PollResult(val code: Int, val message: String = "")

    suspend fun getQrCode(): QrCodeData? = withContext(Dispatchers.IO) {
        LogUtil.d(TAG, "getQrCode: 请求二维码")
        val body = getJson(
            "$passportBaseUrl/x/passport-login/web/qrcode/generate"
        ) ?: run {
            LogUtil.w(TAG, "getQrCode: 接口失败")
            return@withContext null
        }
        val data = body["data"]?.jsonObject ?: run {
            LogUtil.w(TAG, "getQrCode: 响应无 data 字段")
            return@withContext null
        }
        val url = data["url"]?.jsonPrimitive?.content ?: run {
            LogUtil.w(TAG, "getQrCode: 响应无 url 字段")
            return@withContext null
        }
        val key = data["qrcode_key"]?.jsonPrimitive?.content ?: run {
            LogUtil.w(TAG, "getQrCode: 响应无 qrcode_key 字段")
            return@withContext null
        }
        LogUtil.d(TAG, "getQrCode: 二维码获取成功")
        QrCodeData(url, key)
    }

    suspend fun pollLogin(qrcodeKey: String): PollResult = withContext(Dispatchers.IO) {
        val body = getJson(
            "$passportBaseUrl/x/passport-login/web/qrcode/poll",
            mapOf("qrcode_key" to qrcodeKey)
        ) ?: return@withContext PollResult(-1, "网络异常")
        val code = body["data"]?.jsonObject?.get("code")?.jsonPrimitive?.int ?: -1
        val message = body["data"]?.jsonObject?.get("message")?.jsonPrimitive?.content ?: ""
        LogUtil.d(TAG, "pollLogin: code=$code message=$message")
        if (code == 0) {
            // 登录成功：从会话 Cookie 提取并持久化
            val sessdata = sessionCookies["SESSDATA"] ?: ""
            val biliJct = sessionCookies["bili_jct"] ?: ""
            val mid = sessionCookies["DedeUserID"] ?: ""
            LogUtil.d(TAG, "pollLogin: 登录成功，保存会话 mid=$mid")
            cookieStore.saveSession(sessdata, biliJct, mid)
        }
        PollResult(code, message)
    }

    /**
     * 验证 Cookie 有效性并返回 MID（需求 1、2）。
     * 调用 /x/web-interface/nav；非 0 表示 Cookie 失效。
     */
    suspend fun validateAndGetMid(): Long? = withContext(Dispatchers.IO) {
        LogUtil.d(TAG, "validateAndGetMid: 校验 Cookie")
        val body = getJson("$apiBaseUrl/x/web-interface/nav")
            ?: run {
                LogUtil.w(TAG, "validateAndGetMid: 接口失败")
                return@withContext null
            }
        if (body["code"]?.jsonPrimitive?.int != 0) {
            LogUtil.w(TAG, "validateAndGetMid: Cookie 失效 code=${body["code"]}")
            return@withContext null
        }
        val mid = body["data"]?.jsonObject?.get("mid")?.jsonPrimitive?.long
        LogUtil.d(TAG, "validateAndGetMid: 有效 mid=$mid")
        mid?.let { sessionCookies["DedeUserID"] = it.toString() }
        mid
    }

    /**
     * 导入 WebView 官方登录页捕获的会话 Cookie 并加密持久化（需求 1 扩展）。
     * 供 [com.bilifolder.downloader.ui.screens.WebLoginScreen] 在用户完成
     * 手机号+验证码登录后调用；后续请求由本类统一附加 Cookie。
     */
    fun importSessionCookies(sessdata: String, biliJct: String, mid: String) {
        LogUtil.d(TAG, "importSessionCookies: sessdata=${sessdata.isNotBlank()} biliJct=${biliJct.isNotBlank()} mid=$mid")
        if (sessdata.isNotBlank()) sessionCookies["SESSDATA"] = sessdata
        if (biliJct.isNotBlank()) sessionCookies["bili_jct"] = biliJct
        if (mid.isNotBlank()) sessionCookies["DedeUserID"] = mid
        cookieStore.saveSession(sessdata, biliJct, mid)
    }

    // ---------- 收藏夹（需求 3） ----------

    suspend fun getFolders(mid: Long): List<Folder> = withContext(Dispatchers.IO) {
        LogUtil.d(TAG, "getFolders: 拉取收藏夹 mid=$mid")
        val result = mutableListOf<Folder>()
        var page = 1
        while (true) {
            val body = getJson(
                "$apiBaseUrl/x/v3/fav/folder/created/list-all",
                mapOf("up_mid" to mid.toString(), "platform" to "web", "pn" to page.toString(), "ps" to "20")
            ) ?: break
            if (body["code"]?.jsonPrimitive?.int != 0) {
                LogUtil.w(TAG, "getFolders: 第 $page 页错误码 ${body["code"]}")
                break
            }
            val list = body["data"]?.jsonObject?.get("list") as? JsonArray ?: break
            for (item in list) {
                val obj = item.jsonObject
                result += Folder(
                    mediaId = obj["id"]?.jsonPrimitive?.long ?: 0,
                    title = obj["title"]?.jsonPrimitive?.content ?: "",
                    mediaCount = obj["media_count"]?.jsonPrimitive?.int ?: 0,
                )
            }
            LogUtil.d(TAG, "getFolders: 第 $page 页 ${list.size} 条，累计 ${result.size}")
            if (list.size < 20) break
            page += 1
        }
        LogUtil.d(TAG, "getFolders: 完成，共 ${result.size} 个收藏夹")
        result
    }

    /**
     * 收藏夹视频分页结果（对应 `1.py#L254` 的 4 元组语义）。
     * @param videos 本页视频列表
     * @param error  错误信息（成功为 null）
     * @param originalCount 本页原始条数
     * @param totalCount 收藏夹视频总数（media_count）
     */
    data class FolderVideosPage(
        val videos: List<VideoInfo>,
        val error: String?,
        val originalCount: Int,
        val totalCount: Int,
    )

    suspend fun getFolderVideos(mediaId: Long, page: Int, pageSize: Int = 20): FolderVideosPage =
        withContext(Dispatchers.IO) {
            val body = getJson(
                "$apiBaseUrl/x/v3/fav/resource/list",
                mapOf(
                    "media_id" to mediaId.toString(),
                    "pn" to page.toString(),
                    "ps" to pageSize.toString(),
                    "platform" to "web"
                )
            )
            if (body == null) {
                LogUtil.w(TAG, "getFolderVideos: mediaId=$mediaId page=$page 网络异常")
                return@withContext FolderVideosPage(emptyList(), "网络异常", 0, 0)
            }
            val code = body["code"]?.jsonPrimitive?.int
            if (code != 0) {
                val msg = body["message"]?.jsonPrimitive?.content ?: "错误码 $code"
                LogUtil.w(TAG, "getFolderVideos: mediaId=$mediaId page=$page 错误码 $code: $msg")
                return@withContext FolderVideosPage(emptyList(), msg, 0, 0)
            }
            val data = body["data"]?.jsonObject
            val totalCount = data?.get("info")?.jsonObject?.get("media_count")?.jsonPrimitive?.int
                ?: data?.get("media_count")?.jsonPrimitive?.int ?: 0
            val medias = data?.get("medias") as? JsonArray ?: JsonArray(emptyList())
            val videos = medias.mapNotNull { item ->
                val obj = item.jsonObject
                val bvid = obj["bvid"]?.jsonPrimitive?.content ?: return@mapNotNull null
                VideoInfo(
                    bvid = bvid,
                    title = obj["title"]?.jsonPrimitive?.content ?: "",
                    cid = obj["cid"]?.jsonPrimitive?.int ?: 0,
                    avid = obj["id"]?.jsonPrimitive?.long ?: 0,
                    page = 1,
                )
            }
            FolderVideosPage(videos, null, videos.size, totalCount).also {
                LogUtil.d(TAG, "getFolderVideos: mediaId=$mediaId page=$page 返回 ${videos.size} 条，总数 $totalCount")
            }
        }

    // ---------- 播放地址（需求 4、13） ----------

    /** DASH 流数据：选中的视频流与音频流 */
    data class PlayUrlData(
        val videoUrl: String,
        val audioUrl: String?,
        val videoQuality: Int,
        val needVip: Boolean,
    )

    /**
     * 获取 DASH 播放地址（`1.py#L314`：/x/player/playurl，fnval=16）。
     * @param qn 目标清晰度（80=1080P）
     * @return null 表示无可用流或接口失败
     */
    suspend fun getVideoUrl(bvid: String, cid: Int, qn: Int): PlayUrlData? = withContext(Dispatchers.IO) {
        LogUtil.d(TAG, "getVideoUrl: bvid=$bvid cid=$cid qn=$qn")
        val body = getJson(
            "$apiBaseUrl/x/player/playurl",
            mapOf(
                "bvid" to bvid,
                "cid" to cid.toString(),
                "qn" to qn.toString(),
                "fnver" to "0",
                "fnval" to "16",
            )
        ) ?: run {
            LogUtil.w(TAG, "getVideoUrl: bvid=$bvid 接口失败")
            return@withContext null
        }
        if (body["code"]?.jsonPrimitive?.int != 0) {
            LogUtil.w(TAG, "getVideoUrl: bvid=$bvid 错误码 ${body["code"]}")
            return@withContext null
        }
        val data = body["data"]?.jsonObject ?: run {
            LogUtil.w(TAG, "getVideoUrl: bvid=$bvid 无 data")
            return@withContext null
        }
        val dash = data["dash"]?.jsonObject ?: run {
            LogUtil.w(TAG, "getVideoUrl: bvid=$bvid 无 dash 流")
            return@withContext null
        }

        // 选择与 qn 匹配的视频流；无精确匹配时取最高可用
        val videos = dash["video"] as? JsonArray ?: JsonArray(emptyList())
        val audios = dash["audio"] as? JsonArray ?: JsonArray(emptyList())
        if (videos.isEmpty()) return@withContext null

        val videoStream = pickVideoStream(videos, qn)
        val videoUrl = videoStream.url ?: return@withContext null
        val audioUrl = audios.firstOrNull()?.jsonObject?.get("baseUrl")?.jsonPrimitive?.content
        LogUtil.d(
            TAG,
            "getVideoUrl: bvid=$bvid 选中视频流 quality=${videoStream.quality} needVip=${videoStream.needVip} 音频流=${audioUrl != null}"
        )
        PlayUrlData(
            videoUrl = videoUrl,
            audioUrl = audioUrl,
            videoQuality = videoStream.quality,
            needVip = videoStream.needVip,
        )
    }

    private fun pickVideoStream(videos: JsonArray, qn: Int): Stream {
        val parsed = videos.map { item ->
            val obj = item.jsonObject
            Stream(
                quality = obj["id"]?.jsonPrimitive?.int ?: 0,
                url = obj["baseUrl"]?.jsonPrimitive?.content ?: obj["base_url"]?.jsonPrimitive?.content,
            )
        }.filter { it.url != null }
        if (parsed.isEmpty()) return Stream(0, null, false)
        val exact = parsed.firstOrNull { it.quality == qn }
        if (exact != null) return exact
        val best = parsed.maxByOrNull { it.quality }!!
        // 所选 qn 无对应流且最高可用流低于目标：判定为清晰度受限（如需大会员，设计 4.1）
        return Stream(best.quality, best.url, needVip = best.quality < qn)
    }

    private data class Stream(val quality: Int, val url: String?, val needVip: Boolean = false)

    // ---------- 删除收藏夹视频（需求 7） ----------

    suspend fun deleteFolderVideo(mediaId: Long, avid: Long): Boolean = withContext(Dispatchers.IO) {
        val csrf = sessionCookies["bili_jct"] ?: run {
            LogUtil.w(TAG, "deleteFolderVideo: 缺少 bili_jct")
            return@withContext false
        }
        val form = "resources=$avid%3A2&media_id=$mediaId&platform=web&csrf=$csrf"
        val request = Request.Builder()
            .url("$apiBaseUrl/x/v3/fav/resource/batch-del")
            .header("Referer", "https://space.bilibili.com")
            .post(form.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
            .build()
        val body = execute(request)?.jsonObject ?: run {
            LogUtil.w(TAG, "deleteFolderVideo: mediaId=$mediaId avid=$avid 接口失败")
            return@withContext false
        }
        val ok = body["code"]?.jsonPrimitive?.int == 0
        LogUtil.d(TAG, "deleteFolderVideo: mediaId=$mediaId avid=$avid 结果=$ok")
        ok
    }

    // ---------- buvid 设备指纹（设计 4.1） ----------

    suspend fun ensureBuvid() {
        if (sessionCookies.containsKey("buvid3")) return
        val body = getJson("$apiBaseUrl/x/frontend/finger/spi")
        val data = body?.get("data")?.jsonObject ?: return
        data["b_3"]?.jsonPrimitive?.content?.let { sessionCookies["buvid3"] = it }
        data["b_4"]?.jsonPrimitive?.content?.let { sessionCookies["buvid4"] = it }
        LogUtil.d(TAG, "ensureBuvid: buvid3=${sessionCookies.containsKey("buvid3")} buvid4=${sessionCookies.containsKey("buvid4")}")
    }

    // ---------- HTTP 辅助 ----------

    private fun getJson(url: String, params: Map<String, String> = emptyMap()): JsonObject? {
        val urlBuilder = url.toHttpUrl().newBuilder()
        params.forEach { (k, v) -> urlBuilder.addQueryParameter(k, v) }
        val request = Request.Builder().url(urlBuilder.build()).build()
        return execute(request)
    }

    private fun execute(request: Request): JsonObject? {
        return try {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return null
                json.parseToJsonElement(resp.body?.string() ?: return null) as? JsonObject
            }
        } catch (e: IOException) {
            null
        } catch (e: Exception) {
            null
        }
    }
}

/** 内存 CookieJar：只保存 B 站域名 Cookie 到会话 map（配合请求拦截器附加） */
private class InMemoryCookieJar(
    private val store: MutableMap<String, String>,
    private val cookieHost: String,
) : CookieJar {

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (url.host != cookieHost && !url.host.endsWith(".$cookieHost")) return
        cookies.forEach { cookie ->
            if (!cookie.value.isNullOrBlank()) {
                store[cookie.name] = cookie.value
            }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> = emptyList()
}
