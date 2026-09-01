package com.bilifolder.downloader.ui.screens

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.bilifolder.downloader.data.BiliApiClient
import com.bilifolder.downloader.util.LogUtil
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 官方登录页（方案 A，需求 1 扩展）。
 *
 * 内嵌 WebView 打开 B 站官方登录页 https://passport.bilibili.com/login，
 * 用户可在官方页面选择「短信验证码登录」（手机号+验证码）完成登录。
 * 官方页面自带极验人机验证，由用户手动完成，应用不做任何绕过。
 *
 * 登录成功后从 WebView 的 CookieManager 捕获 SESSDATA / bili_jct /
 * DedeUserID，回填到 [BiliApiClient] 并加密持久化，随后沿用现有
 * 收藏夹下载流程。
 */
@Composable
fun WebLoginScreen(
    client: BiliApiClient,
    onLoggedIn: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var statusText by remember { mutableStateOf("正在打开官方登录页…") }
    var pageLoading by remember { mutableStateOf(true) }
    var done by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // 轮询捕获登录 Cookie（B 站登录成功后写入 SESSDATA）
    LaunchedEffect(done) {
        LogUtil.d(TAG, "WebLoginScreen: 开始轮询捕获 Cookie")
        while (!done) {
            delay(1000)
            val cookies = readWebViewCookies()
            val sessdata = cookies["SESSDATA"]
            if (!sessdata.isNullOrBlank()) {
                LogUtil.d(TAG, "WebLoginScreen: 捕获到 SESSDATA，bili_jct=${cookies["bili_jct"]?.isNotBlank()} mid=${cookies["DedeUserID"]}")
                client.importSessionCookies(
                    sessdata = sessdata,
                    biliJct = cookies["bili_jct"].orEmpty(),
                    mid = cookies["DedeUserID"].orEmpty(),
                )
                val mid = client.validateAndGetMid()
                if (mid != null) {
                    LogUtil.d(TAG, "WebLoginScreen: 会话校验通过 mid=$mid")
                    done = true
                    statusText = "登录成功"
                    // 清理 WebView Cookie，避免下次打开登录页自动跳过
                    CookieManager.getInstance().removeAllCookies(null)
                    scope.launch {
                        delay(300)
                        onLoggedIn()
                    }
                } else {
                    LogUtil.w(TAG, "WebLoginScreen: 会话校验失败，等待重新登录")
                    statusText = "会话校验失败，请在页面重新登录"
                }
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onBack) {
                Text("返回")
            }
            Text(
                "手机号+验证码登录",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 16.dp),
            )
        }
        Text(
            statusText,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
        )
        Box(modifier = Modifier.fillMaxWidth()) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.cacheMode = WebSettings.LOAD_NO_CACHE
                        // 使用桌面浏览器 UA，与 BiliApiClient 保持一致，页面行为稳定
                        settings.userAgentString = DESKTOP_USER_AGENT
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                pageLoading = true
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                pageLoading = false
                            }
                        }
                        loadUrl(LOGIN_URL)
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            if (pageLoading && !done) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(48.dp)
                        .align(Alignment.Center),
                )
            }
        }
        Button(
            onClick = { onLoggedIn() },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
        ) {
            Text("已有账号，直接进入")
        }
    }
}

/** 从 WebView 全局 CookieManager 读取 B 站会话 Cookie */
private fun readWebViewCookies(): Map<String, String> = runCatching {
    val raw = buildList {
        addAll(
            (CookieManager.getInstance().getCookie("https://passport.bilibili.com") ?: "").split(";")
        )
        addAll(
            (CookieManager.getInstance().getCookie("https://www.bilibili.com") ?: "").split(";")
        )
    }
    raw.mapNotNull { part ->
        val kv = part.trim().split("=", limit = 2)
        if (kv.size == 2) kv[0] to kv[1] else null
    }.toMap()
}.getOrDefault(emptyMap())

private const val LOGIN_URL = "https://passport.bilibili.com/login"
private const val TAG = "WebLoginScreen"
private const val DESKTOP_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
