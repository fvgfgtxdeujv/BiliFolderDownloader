package com.bilifolder.downloader.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bilifolder.downloader.data.VideoLibrary
import com.bilifolder.downloader.ui.MainViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 已下载视频库（设计 4.7.3，需求 18）。
 *
 * - 成品 MP4 列表（标题、大小、时间），点击用 Media3 ExoPlayer 播放，支持删除
 * - 下载历史记录展示（时间、收藏夹、成功/失败数、zip 路径）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoLibraryScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onRerun: (com.bilifolder.downloader.data.model.Folder) -> Unit,
    modifier: Modifier = Modifier,
) {
    val videos by viewModel.libraryVideos.collectAsStateWithLifecycle()
    val history by viewModel.taskHistory.collectAsStateWithLifecycle()
    var playingFile by remember { mutableStateOf<VideoLibrary.VideoItem?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("已下载") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(padding),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item {
                Text(
                    "视频（${videos.size}）",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            if (videos.isEmpty()) {
                item { Text("暂无已下载视频", modifier = Modifier.padding(16.dp)) }
            }
            items(videos, key = { it.file.absolutePath }) { item ->
                VideoLibraryRow(
                    item = item,
                    onPlay = { playingFile = item },
                    onDelete = { viewModel.deleteVideo(item) },
                )
            }
            item {
                Text(
                    "下载历史",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            if (history.isEmpty()) {
                item { Text("暂无历史记录", modifier = Modifier.padding(16.dp)) }
            }
            items(history, key = { it.timestamp }) { entry ->
                HistoryRow(
                    entry = entry,
                    onRerun = {
                        entry.folder?.let { onRerun(it) } ?: viewModel.deleteHistory(entry)
                    },
                    onDelete = { viewModel.deleteHistory(entry) },
                )
            }
        }
    }

    playingFile?.let { item ->
        VideoPlayerDialog(
            file = item.file,
            title = item.title,
            onDismiss = { playingFile = null },
        )
    }
}

@Composable
private fun VideoLibraryRow(
    item: VideoLibrary.VideoItem,
    onPlay: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(onClick = onPlay, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(item.title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    formatSize(item.sizeBytes) + " · " + formatTime(item.lastModified),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "删除")
            }
        }
    }
}

@Composable
private fun HistoryRow(
    entry: com.bilifolder.downloader.data.model.TaskHistory,
    onRerun: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Text(
            "${formatTime(entry.timestamp)} · ${entry.folderName}",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "成功 ${entry.success}，失败 ${entry.failed}" +
                (entry.zipPath?.let { " · zip: ${it.substringAfterLast('/')}" } ?: ""),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onDelete, enabled = true) {
                Text("删除")
            }
            TextButton(
                onClick = onRerun,
                enabled = entry.folder != null,
            ) {
                Text("重新运行")
            }
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 * 1024 -> "%.2f GB".format(bytes / 1024.0 / 1024 / 1024)
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024)
    else -> "%.0f KB".format(bytes / 1024.0)
}

private fun formatTime(ts: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ts))
