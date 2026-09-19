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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bilifolder.downloader.download.DownloadManager
import com.bilifolder.downloader.ui.MainViewModel
import java.util.Locale

/**
 * 下载进度页（设计 4.7.3，需求 10）。
 *
 * 展示总体进度（done/total + 当前视频标题）、自动滚动日志区与停止按钮。
 * 事件来自 [DownloadManager.events]（SharedFlow，带 200 条回放）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onStop: () -> Unit,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val manager = viewModel.container.downloadManager
    var logs by remember { mutableStateOf<List<String>>(emptyList()) }
    var progress by remember { mutableStateOf<Pair<Int, Int>?>(null) } // done, total
    var currentTitle by remember { mutableStateOf<String?>(null) }
    var fileProgress by remember { mutableStateOf<FileProgressUi?>(null) }
    val listState = rememberLazyListState()

    // 任务从运行中转为结束（自然完成或主动停止后收尾）→ 自动返回主页面
    LaunchedEffect(Unit) {
        var wasRunning = false
        manager.runningState.collect { r ->
            if (r) {
                wasRunning = true
            } else if (wasRunning) {
                onFinished()
            }
        }
    }

    LaunchedEffect(Unit) {
        manager.events.collect { event ->
            when (event) {
                is DownloadManager.DownloadEvent.Log -> {
                    logs = (logs + event.line).takeLast(500)
                }
                is DownloadManager.DownloadEvent.Progress -> {
                    progress = if (event.total > 0) event.done to event.total else null
                    currentTitle = event.currentTitle
                    // 切换视频时清空上一条文件的字节进度
                    fileProgress = null
                }
                is DownloadManager.DownloadEvent.FileProgress -> {
                    fileProgress = FileProgressUi(event.label, event.downloaded, event.total)
                }
                is DownloadManager.DownloadEvent.Finished -> {
                    logs = (logs + "全部完成：成功 ${event.success}，失败 ${event.failed}，跳过 ${event.skipped}").takeLast(500)
                }
            }
        }
    }

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) listState.scrollToItem(logs.size - 1)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("下载任务") },
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
                .padding(16.dp),
        ) {
            val (done, total) = progress ?: (0 to 0)
            if (total > 0) {
                Text("总体进度：$done / $total", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { done.toFloat() / total },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            currentTitle?.let {
                Spacer(Modifier.height(8.dp))
                Text("当前：$it", style = MaterialTheme.typography.bodyMedium)
            }
            fileProgress?.let { fp ->
                Spacer(Modifier.height(12.dp))
                if (fp.total > 0) {
                    val fraction = (fp.downloaded.toDouble() / fp.total).coerceIn(0.0, 1.0).toFloat()
                    Text(
                        "${fp.label}：${formatBytes(fp.downloaded)} / ${formatBytes(fp.total)}" +
                            "（${(fraction * 100).toInt()}%）",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Text("${fp.label}：正在下载…", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
            Spacer(Modifier.height(16.dp))
            if (logs.isEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                    Text("任务已提交，等待执行…")
                }
            } else {
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    items(logs) { line ->
                        Text(
                            line,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = onStop,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("停止任务")
            }
        }
    }
}

/** 下载页展示用的当前文件字节进度 */
private data class FileProgressUi(val label: String, val downloaded: Long, val total: Long)

/** 字节数格式化：B / KB / MB / GB */
private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024)
    else -> String.format(Locale.US, "%.2f GB", bytes / 1024.0 / 1024 / 1024)
}
