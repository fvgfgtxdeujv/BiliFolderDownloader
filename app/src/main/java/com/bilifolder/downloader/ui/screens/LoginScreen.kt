package com.bilifolder.downloader.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.bilifolder.downloader.ui.MainViewModel
import com.bilifolder.downloader.ui.QrRenderer
import kotlinx.coroutines.delay

/**
 * 扫码登录页（设计 4.7.3，需求 1、2）。
 *
 * 展示 B 站登录二维码并轮询状态：
 * - 0：登录成功（Cookie 已由 BiliApiClient 提取并加密持久化）
 * - 86038：二维码过期，重新生成
 * - 86090 / 86101：等待扫码/确认
 */
@Composable
fun LoginScreen(
    viewModel: MainViewModel,
    onLoggedIn: () -> Unit,
    onOpenWebLogin: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val client = viewModel.container.biliApiClient
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var statusText by remember { mutableStateOf("正在获取二维码…") }
    var polling by remember { mutableStateOf(true) }
    var qrcodeKey by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(polling) {
        while (polling) {
            val key = qrcodeKey
            if (key == null) {
                val qr = client.getQrCode()
                if (qr == null) {
                    statusText = "获取二维码失败，请检查网络"
                    delay(3000)
                    continue
                }
                qrcodeKey = qr.qrcodeKey
                qrBitmap = QrRenderer.render(qr.url)
                statusText = "请使用 B 站 App 扫码登录"
            } else {
                val result = client.pollLogin(key)
                when (result.code) {
                    0 -> {
                        statusText = "登录成功"
                        polling = false
                        onLoggedIn()
                    }
                    86038 -> {
                        qrcodeKey = null
                        statusText = "二维码已过期，正在重新生成…"
                        delay(500)
                    }
                    86090 -> statusText = "已扫码，请在手机上确认"
                    86101 -> statusText = "等待扫码…"
                    else -> {
                        statusText = "登录异常：${result.message}"
                        delay(3000)
                    }
                }
                delay(2000)
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("扫码登录 B 站", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(24.dp))
        if (qrBitmap != null) {
            Image(
                bitmap = qrBitmap!!.asImageBitmap(),
                contentDescription = "登录二维码",
                modifier = Modifier.size(260.dp),
            )
        } else {
            CircularProgressIndicator()
        }
        Spacer(Modifier.height(16.dp))
        Text(statusText, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(24.dp))
        OutlinedButton(
            onClick = { qrcodeKey = null },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("刷新二维码")
        }
        OutlinedButton(
            onClick = { onOpenWebLogin() },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        ) {
            Text("手机号+验证码登录")
        }
        Button(
            onClick = { onLoggedIn() },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        ) {
            Text("已有账号，直接进入")
        }
    }
}
