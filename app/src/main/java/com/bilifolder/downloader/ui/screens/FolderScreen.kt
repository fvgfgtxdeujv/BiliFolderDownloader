package com.bilifolder.downloader.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bilifolder.downloader.BuildConfig
import com.bilifolder.downloader.data.model.Folder
import com.bilifolder.downloader.ui.MainViewModel
import com.bilifolder.downloader.util.DebugDbUploader
import kotlinx.coroutines.launch

/**
 * 收藏夹选择页（设计 4.7.3，需求 3）。
 * 输入用户 MID 拉取收藏夹列表，点击进入视频多选。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderScreen(
    viewModel: MainViewModel,
    onOpenVideos: (Folder) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenLibrary: () -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    val loading by viewModel.foldersLoading.collectAsStateWithLifecycle()
    val error by viewModel.foldersError.collectAsStateWithLifecycle()
    val lastMid by viewModel.lastMid.collectAsStateWithLifecycle()

    var midInput by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(lastMid) {
        if (lastMid > 0) {
            if (midInput.isBlank()) {
                midInput = lastMid.toString()
            }
            // 已登录/已记录 MID 时直接进入即加载，避免用户面对空列表无从下手
            if (!loaded) {
                loaded = true
                viewModel.loadFolders(lastMid)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("我的收藏夹") },
                actions = {
                    IconButton(
                        onClick = {
                            val mid = midInput.toLongOrNull()?.takeIf { it > 0 } ?: lastMid
                            if (mid > 0) {
                                loaded = true
                                viewModel.loadFolders(mid, force = true)
                            }
                        },
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新收藏夹")
                    }
                    IconButton(onClick = onOpenLibrary) {
                        Icon(Icons.Filled.VideoLibrary, contentDescription = "已下载")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "设置")
                    }
                    IconButton(onClick = onLogout) {
                        Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = "退出登录")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = midInput,
                    onValueChange = { midInput = it.filter { c -> c.isDigit() }.take(15) },
                    label = { Text("用户 MID") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.padding(4.dp))
                Button(
                    onClick = {
                        val mid = midInput.toLongOrNull()
                        if (mid != null && mid > 0) {
                            loaded = true
                            viewModel.loadFolders(mid)
                        }
                    },
                    enabled = midInput.isNotBlank(),
                ) {
                    Text("加载")
                }
            }
            Spacer(Modifier.height(8.dp))
            // 开发版专用：一键把当前调试日志 db 上传到内置 WebDAV，便于真机取证
            if (BuildConfig.DEBUG) {
                val context = LocalContext.current
                val scope = rememberCoroutineScope()
                val configured = remember { DebugDbUploader.isConfigured(context) }
                var uploading by remember { mutableStateOf(false) }
                var uploadMsg by remember { mutableStateOf<String?>(null) }
                Button(
                    onClick = {
                        uploading = true
                        uploadMsg = null
                        scope.launch {
                            uploadMsg = DebugDbUploader.uploadDb(context, viewModel.container.webDavClient)
                            uploading = false
                        }
                    },
                    enabled = !uploading && configured,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (uploading) "上传中…" else "上传调试日志 db")
                }
                val hint = uploadMsg ?: if (!configured) "未配置 assets/debug_webdav.properties" else null
                hint?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (it.startsWith("上传成功")) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            when {
                loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                    Text("加载中…")
                }
                folders.isNotEmpty() -> {
                    // 有缓存时即使刷新失败也优先展示缓存，仅在上方提示
                    error?.let {
                        Text(
                            it,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(folders, key = { it.mediaId }) { folder ->
                            FolderItem(folder = folder, onClick = { onOpenVideos(folder) })
                        }
                    }
                }
                error != null -> Text(
                    error ?: "",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
                loaded -> Text("暂无收藏夹")
            }
        }
    }
}

@Composable
private fun FolderItem(folder: Folder, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Folder, contentDescription = null)
            Column(modifier = Modifier.padding(start = 12.dp)) {
                Text(folder.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${folder.mediaCount} 个视频",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
