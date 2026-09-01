package com.bilifolder.downloader.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bilifolder.downloader.data.model.DownloadEngineType
import com.bilifolder.downloader.ui.MainViewModel
import android.content.Intent
import kotlinx.coroutines.launch

/**
 * 设置页（设计 4.7.3，需求 19、20、21）。
 *
 * - 引擎切换：Gopeed 二进制检测不可用时禁用并展示原因（需求 19）
 * - WebDAV：地址/用户名/密码（密码 AES 加密存储）、连接测试、自动上传开关（需求 20、21）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val engineType by viewModel.engineType.collectAsStateWithLifecycle()
    val gopeedAvailable by viewModel.gopeedAvailable.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val webDavConfig by viewModel.webDavConfig.collectAsStateWithLifecycle()
    val debugLogEnabled by viewModel.debugLogEnabled.collectAsStateWithLifecycle()

    var webdavUrl by remember { mutableStateOf("") }
    var webdavUser by remember { mutableStateOf("") }
    var webdavPass by remember { mutableStateOf("") }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    var limit by remember { mutableStateOf(100) }

    val scope = rememberCoroutineScope()

    LaunchedEffect(webDavConfig) {
        webdavUrl = webDavConfig.url
        webdavUser = webDavConfig.username
        webdavPass = viewModel.container.cookieStore.webDavPassword() ?: ""
    }
    LaunchedEffect(settings) { limit = settings.limitKbps }

    fun saveConfig() {
        viewModel.setWebDavConfig(
            com.bilifolder.downloader.data.model.WebDavConfig(
                url = webdavUrl.trim(),
                username = webdavUser.trim(),
                autoUpload = webDavConfig.autoUpload,
            )
        )
        viewModel.container.cookieStore.saveWebDavPassword(webdavPass)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            // ---------- 下载引擎（需求 19） ----------
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("下载引擎", style = MaterialTheme.typography.titleMedium)
                    EngineOption(
                        label = DownloadEngineType.BUILTIN.displayName,
                        selected = engineType == DownloadEngineType.BUILTIN,
                        enabled = true,
                        hint = null,
                        onClick = { viewModel.setEngineType(DownloadEngineType.BUILTIN) },
                    )
                    EngineOption(
                        label = DownloadEngineType.GOPEED.displayName,
                        selected = engineType == DownloadEngineType.GOPEED,
                        enabled = gopeedAvailable,
                        hint = if (!gopeedAvailable) "未检测到 Gopeed 二进制" else null,
                        onClick = { viewModel.setEngineType(DownloadEngineType.GOPEED) },
                    )
                    if (engineType == DownloadEngineType.GOPEED) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Gopeed 引擎不支持带宽限速",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // ---------- 限速（仅内置引擎，需求 5） ----------
            Spacer(Modifier.height(12.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("默认限速", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.weight(1f))
                        Text(
                            when (limit) {
                                0 -> "不限速"
                                100 -> "100 KB/s"
                                300 -> "300 KB/s"
                                500 -> "500 KB/s"
                                1000 -> "1 MB/s"
                                else -> "$limit KB/s"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    LimitOption("不限速", limit == 0, engineType != DownloadEngineType.GOPEED) { limit = 0 }
                    LimitOption("100 KB/s", limit == 100, engineType != DownloadEngineType.GOPEED) { limit = 100 }
                    LimitOption("300 KB/s", limit == 300, engineType != DownloadEngineType.GOPEED) { limit = 300 }
                    LimitOption("500 KB/s", limit == 500, engineType != DownloadEngineType.GOPEED) { limit = 500 }
                    LimitOption("1 MB/s", limit == 1000, engineType != DownloadEngineType.GOPEED) { limit = 1000 }
                }
            }

            // ---------- WebDAV（需求 20、21） ----------
            Spacer(Modifier.height(12.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("WebDAV 备份", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = webdavUrl,
                        onValueChange = { webdavUrl = it },
                        label = { Text("服务器地址") },
                        placeholder = { Text("https://dav.example.com/dav") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = webdavUser,
                        onValueChange = { webdavUser = it },
                        label = { Text("用户名") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = webdavPass,
                        onValueChange = { webdavPass = it },
                        label = { Text("密码（加密存储）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = {
                                saveConfig()
                                testing = true
                                testResult = null
                                scope.launch {
                                    val ok = viewModel.container.webDavClient.testConnection(
                                        webdavUrl.trim(), webdavUser.trim(), webdavPass,
                                    )
                                    testResult = if (ok) "连接成功" else "连接失败，请检查地址与账号"
                                    testing = false
                                }
                            },
                            enabled = !testing && webdavUrl.isNotBlank(),
                        ) {
                            Text(if (testing) "测试中…" else "测试连接")
                        }
                        testResult?.let {
                            Spacer(Modifier.padding(8.dp))
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (it == "连接成功") MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "下载完成后自动上传 zip",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(
                            checked = webDavConfig.autoUpload,
                            onCheckedChange = { checked ->
                                viewModel.setWebDavConfig(
                                    com.bilifolder.downloader.data.model.WebDavConfig(
                                        url = webdavUrl.trim(),
                                        username = webdavUser.trim(),
                                        autoUpload = checked,
                                    )
                                )
                            },
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            saveConfig()
                            viewModel.updateSettings { it.copy(limitKbps = limit) }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("保存设置")
                    }
                }
            }
            // ---------- 调试日志（正式版） ----------
            Spacer(Modifier.height(12.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("调试日志", style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (com.bilifolder.downloader.BuildConfig.DEBUG) {
                                    "开发版：logcat 明文 + 文件导出（明文 txt）"
                                } else {
                                    "开启后导出整体 RSA 加密的日志文件（logcat 已禁用），需先在 assets/rsa_public_key.pem 放置公钥"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = debugLogEnabled || com.bilifolder.downloader.BuildConfig.DEBUG,
                            onCheckedChange = { viewModel.setDebugLogEnabled(it) },
                            enabled = !com.bilifolder.downloader.BuildConfig.DEBUG,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    val context = LocalContext.current
                    var exportMsg by remember { mutableStateOf<String?>(null) }
                    Button(
                        onClick = {
                            // 默认导出最近 30 分钟日志
                            val f = com.bilifolder.downloader.util.LogUtil.exportLogs()
                            if (f != null && f.exists()) {
                                exportMsg = null
                                val uri = FileProvider.getUriForFile(
                                    context, "${context.packageName}.fileprovider", f,
                                )
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(intent, "导出日志文件"))
                            } else {
                                exportMsg = "暂无最近 30 分钟日志可导出"
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("导出日志文件（最近 30 分钟）")
                    }
                    exportMsg?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun EngineOption(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    hint: String?,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
    ) {
        RadioButton(selected = selected, onClick = { if (enabled) onClick() }, enabled = enabled)
        Column {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            hint?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun LimitOption(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
    ) {
        RadioButton(selected = selected, onClick = { if (enabled) onClick() }, enabled = enabled)
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
