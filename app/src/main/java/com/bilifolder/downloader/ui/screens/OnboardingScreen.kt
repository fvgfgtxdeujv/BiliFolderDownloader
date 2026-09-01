package com.bilifolder.downloader.ui.screens

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.bilifolder.downloader.ui.MainViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 首次启动合规页（设计 4.7.3，需求 17）。
 *
 * 展示免责声明（仅供个人学习、禁止传播）与隐私说明
 * （Cookie 加密本地存储、不上传第三方）。同意 → 写入标记进入主界面；
 * 拒绝 → finishAffinity() 退出应用。
 */
@Composable
fun OnboardingScreen(
    viewModel: MainViewModel,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "B站收藏夹下载器",
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = "使用须知与隐私说明",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = "1. 本应用仅用于个人学习与备份个人收藏内容，禁止将下载内容用于任何商业用途或传播。\n\n" +
                    "2. 请遵守 B 站用户协议与相关法律法规，尊重视频作者版权。\n\n" +
                    "3. 登录 Cookie（SESSDATA 等）使用 AES-256-GCM 加密后仅保存在本机，不会上传至任何第三方服务器。\n\n" +
                    "4. 下载间隔与限速设置用于降低对服务器的压力，请勿滥用。",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Button(
            onClick = {
                scope.launch {
                    viewModel.container.recordStore.setOnboardingAgreed(true)
                    onDone()
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("同意并继续")
        }
        OutlinedButton(
            onClick = {
                (context as? Activity)?.finishAffinity()
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        ) {
            Text("不同意并退出")
        }
    }
}
