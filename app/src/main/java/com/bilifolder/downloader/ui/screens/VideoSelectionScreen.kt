package com.bilifolder.downloader.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bilifolder.downloader.data.NetworkMonitor
import com.bilifolder.downloader.data.model.DownloadEngineType
import com.bilifolder.downloader.data.model.Folder
import com.bilifolder.downloader.data.model.VideoInfo
import com.bilifolder.downloader.service.DownloadService
import com.bilifolder.downloader.ui.MainViewModel

private val QUALITY_OPTIONS = listOf(64 to "720P", 80 to "1080P", 112 to "1080P+", 116 to "1080P60")
private val LIMIT_OPTIONS = listOf(0 to "不限速", 100 to "100 KB/s", 300 to "300 KB/s", 500 to "500 KB/s", 1000 to "1 MB/s")

/**
 * 收藏夹视频多选 + 任务配置页（设计 4.7.3，需求 4、13、14、19、21）。
 *
 * 勾选视频子集（需求 14），配置清晰度/限速/删除/仅 WiFi/引擎/自动上传后
 * 启动 [DownloadService] 开始下载。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoSelectionScreen(
    viewModel: MainViewModel,
    mediaId: Long,
    folderTitle: String,
    onBack: () -> Unit,
    onStartDownload: (List<DownloadService.DownloadRequestDto>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val videos by viewModel.folderVideos.collectAsStateWithLifecycle()
    val loading by viewModel.videosLoading.collectAsStateWithLifecycle()
    val error by viewModel.videosError.collectAsStateWithLifecycle()
    val folderMeta by viewModel.folderMeta.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val engineType by viewModel.engineType.collectAsStateWithLifecycle()
    val gopeedAvailable by viewModel.gopeedAvailable.collectAsStateWithLifecycle()
    val webDavConfig by viewModel.webDavConfig.collectAsStateWithLifecycle()

    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var hint by remember { mutableStateOf<String?>(null) }
    var showMobileDataDialog by remember { mutableStateOf(false) }

    LaunchedEffect(mediaId) {
        viewModel.loadFolderVideos(mediaId, folderTitle)
    }

    val mediaCount = folderMeta?.second ?: videos.size
    val selectAll = videos.isNotEmpty() && selected.size == videos.size

    /** 组装请求并启动下载服务 */
    fun startDownload() {
        val folder = Folder(
            mediaId = mediaId,
            title = folderTitle,
            mediaCount = mediaCount,
        )
        val request = DownloadService.DownloadRequestDto(
            folder = folder,
            selected = selected.toList(),
        )
        onStartDownload(listOf(request))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(folderTitle) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.loadFolderVideos(mediaId, folderTitle, force = true) },
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新视频列表")
                    }
                },
            )
        },
        bottomBar = {
            Column(modifier = Modifier.padding(16.dp)) {
                Button(
                    onClick = {
                        if (selected.isEmpty()) {
                            hint = "请先勾选至少一个视频"
                            return@Button
                        }
                        val network = viewModel.container.networkMonitor.networkState.value
                        when {
                            network == NetworkMonitor.NetworkState.DISCONNECTED ->
                                hint = "当前无网络，请检查网络连接后重试"

                            network == NetworkMonitor.NetworkState.CELLULAR && settings.mobileDataPrompt -> {
                                hint = null
                                showMobileDataDialog = true
                            }

                            else -> {
                                hint = null
                                startDownload()
                            }
                        }
                    },
                    enabled = !loading && videos.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("开始下载（已选 ${selected.size} 个）")
                }
                hint?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
    ) { padding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                loading -> Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.padding(8.dp))
                    Text("加载视频…")
                }
                error != null && videos.isEmpty() -> Text(
                    error ?: "",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp),
                )
                videos.isEmpty() -> Text("收藏夹为空", modifier = Modifier.padding(16.dp))
                else -> {
                    // 整页可上下滑动：配置面板作为列表最后一项，避免底部栏过高被裁掉
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        contentPadding = PaddingValues(bottom = 16.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        item {
                            // 命中缓存时刷新失败：保留缓存列表，仅在上方提示
                            error?.let {
                                Text(
                                    it,
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                )
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("共 $mediaCount 个视频", style = MaterialTheme.typography.bodyMedium)
                                Spacer(Modifier.weight(1f))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("全选")
                                    Checkbox(
                                        checked = selectAll,
                                        onCheckedChange = { checked ->
                                            selected = if (checked) videos.map { it.bvid }.toSet() else emptySet()
                                        },
                                    )
                                }
                            }
                        }
                        items(videos, key = { it.bvid }) { video ->
                            VideoRow(
                                video = video,
                                checked = video.bvid in selected,
                                onToggle = { checked ->
                                    selected = if (checked) selected + video.bvid else selected - video.bvid
                                },
                            )
                        }
                        item {
                            Column(modifier = Modifier.padding(16.dp)) {
                                TaskConfigPanel(
                                    settings = settings,
                                    engineType = engineType,
                                    gopeedAvailable = gopeedAvailable,
                                    autoUpload = webDavConfig.autoUpload,
                                    onLimitChange = { limit -> viewModel.updateSettings { it.copy(limitKbps = limit) } },
                                    onQualityChange = { qn -> viewModel.updateSettings { it.copy(quality = qn) } },
                                    onDeleteChange = { v -> viewModel.updateSettings { it.copy(deleteAfterDownload = v) } },
                                    onWifiOnlyChange = { v -> viewModel.updateSettings { it.copy(wifiOnly = v) } },
                                    onEngineChange = { t -> viewModel.setEngineType(t) },
                                    onAutoUploadChange = { v -> viewModel.setWebDavConfig(webDavConfig.copy(autoUpload = v)) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showMobileDataDialog) {
        AlertDialog(
            onDismissRequest = { showMobileDataDialog = false },
            title = { Text("使用移动数据下载？") },
            text = { Text("当前未连接 WiFi，继续下载将使用移动数据，可能产生流量费用。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showMobileDataDialog = false
                        startDownload()
                    },
                ) {
                    Text("继续")
                }
            },
            dismissButton = {
                TextButton(onClick = { showMobileDataDialog = false }) {
                    Text("取消")
                }
            },
        )
    }
}

@Composable
private fun VideoRow(video: VideoInfo, checked: Boolean, onToggle: (Boolean) -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .toggleable(
                value = checked,
                role = Role.Checkbox,
                onValueChange = onToggle,
            ),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = checked, onCheckedChange = null)
            Text(
                video.title,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}

/** 任务配置面板：清晰度/限速/删除/仅 WiFi/引擎/自动上传 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TaskConfigPanel(
    settings: com.bilifolder.downloader.data.DownloadSettings,
    engineType: DownloadEngineType,
    gopeedAvailable: Boolean,
    autoUpload: Boolean,
    onLimitChange: (Int) -> Unit,
    onQualityChange: (Int) -> Unit,
    onDeleteChange: (Boolean) -> Unit,
    onWifiOnlyChange: (Boolean) -> Unit,
    onEngineChange: (DownloadEngineType) -> Unit,
    onAutoUploadChange: (Boolean) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        DropdownField(
            label = "清晰度",
            value = QUALITY_OPTIONS.firstOrNull { it.first == settings.quality }?.second ?: "1080P",
            options = QUALITY_OPTIONS,
            optionText = { it.second },
            onSelect = { onQualityChange(it.first) },
        )
        Spacer(Modifier.height(8.dp))
        DropdownField(
            label = "限速",
            value = LIMIT_OPTIONS.firstOrNull { it.first == settings.limitKbps }?.second ?: "不限速",
            options = LIMIT_OPTIONS,
            optionText = { it.second },
            onSelect = { onLimitChange(it.first) },
            enabled = engineType != DownloadEngineType.GOPEED,
        )
        if (engineType == DownloadEngineType.GOPEED) {
            Text(
                "Gopeed 引擎不支持带宽限速（需求 5）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(4.dp))
        DropdownField(
            label = "下载引擎",
            value = engineType.displayName,
            options = listOf(DownloadEngineType.BUILTIN, DownloadEngineType.GOPEED),
            optionText = { it.displayName },
            onSelect = { onEngineChange(it) },
            enabled = gopeedAvailable || engineType == DownloadEngineType.BUILTIN,
        )
        if (engineType == DownloadEngineType.GOPEED && !gopeedAvailable) {
            Text(
                "未检测到 Gopeed 二进制",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        ConfigSwitch("下载完成后从收藏夹删除", settings.deleteAfterDownload, onDeleteChange)
        ConfigSwitch("仅 WiFi 下下载", settings.wifiOnly, onWifiOnlyChange)
        ConfigSwitch("完成后自动上传 zip（WebDAV）", autoUpload, onAutoUploadChange)
    }
}

@Composable
private fun ConfigSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> DropdownField(
    label: String,
    value: String,
    options: List<T>,
    optionText: (T) -> String,
    onSelect: (T) -> Unit,
    enabled: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded && enabled, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionText(option)) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                )
            }
        }
    }
}
