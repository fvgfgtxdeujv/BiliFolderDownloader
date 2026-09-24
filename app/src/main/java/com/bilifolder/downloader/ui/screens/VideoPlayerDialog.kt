package com.bilifolder.downloader.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import java.io.File

/** 播放器支持的倍速档位 */
private val SPEED_OPTIONS = listOf(0.5f, 1.0f, 1.25f, 1.5f, 2.0f)

/**
 * 全屏视频播放器（Media3 ExoPlayer）。
 *
 * - 默认 PlayerView 控制器提供播放/暂停、进度条拖动与时间显示
 * - 顶部自绘操作条提供倍速切换（0.5x/1.0x/1.25x/1.5x/2.0x）与全屏（横屏）切换
 * - 全屏时切横屏、隐藏系统栏并让内容延伸进刘海/挖孔区（display cutout）
 */
@Composable
fun VideoPlayerDialog(file: File, title: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    var fullscreen by remember { mutableStateOf(false) }
    var speed by remember { mutableStateOf(1.0f) }

    val exoPlayer = remember(file) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            prepare()
            playWhenReady = true
        }
    }

    LaunchedEffect(exoPlayer, speed) {
        exoPlayer.playbackParameters = PlaybackParameters(speed)
    }

    DisposableEffect(exoPlayer) {
        onDispose {
            exoPlayer.release()
            activity?.let { act ->
                act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                act.window?.let { window ->
                    WindowCompat.setDecorFitsSystemWindows(window, true)
                    WindowInsetsControllerCompat(window, window.decorView)
                        .show(WindowInsetsCompat.Type.systemBars())
                }
            }
        }
    }

    // 返回键：全屏时先退出全屏，否则关闭播放器
    BackHandler(enabled = true) {
        if (fullscreen) fullscreen = false else onDismiss()
    }

    Dialog(
        onDismissRequest = { if (fullscreen) fullscreen = false else onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        // Dialog 是独立窗口，全屏属性必须作用在它自己的 window 上
        val dialogView = LocalView.current
        val dialogWindow = remember(dialogView) {
            (dialogView.parent as? DialogWindowProvider)?.window
        }
        LaunchedEffect(fullscreen, dialogWindow) {
            activity?.requestedOrientation = if (fullscreen) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            } else {
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
            (dialogWindow ?: activity?.window)?.let { applyFullscreen(it, fullscreen) }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = exoPlayer
                        useController = true
                        // 保持原比例、尽量铺满，完整显示不裁切（可能留黑边）
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        setShutterBackgroundColor(android.graphics.Color.BLACK)
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        // 隐藏系统自带全屏按钮（由本弹窗自绘全屏控制）
                        findViewById<View>(androidx.media3.ui.R.id.exo_fullscreen)?.visibility = View.GONE
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!fullscreen) {
                    Text(
                        title,
                        color = Color.White,
                        maxLines = 1,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 8.dp),
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                SpeedMenu(speed = speed, onSpeedChange = { speed = it })
                IconButton(onClick = { fullscreen = !fullscreen }) {
                    Icon(
                        imageVector = if (fullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                        contentDescription = if (fullscreen) "退出全屏" else "全屏",
                        tint = Color.White,
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "关闭", tint = Color.White)
                }
            }
        }
    }
}

/**
 * 应用/恢复播放器窗口属性。
 *
 * 无论是否全屏，都把窗口设为 MATCH_PARENT 铺满屏幕，让 PlayerView 的 FIT 缩放
 * 能自适应到整块屏幕（否则窗口高度为 WRAP_CONTENT 时视频区域不会自适应）。
 * 全屏时额外设置 [WindowManager.LayoutParams.layoutInDisplayCutoutMode] = SHORT_EDGES，
 * 让内容延伸进刘海/挖孔区，避免屏幕边缘漏出一条。
 */
@Suppress("DEPRECATION")
private fun applyFullscreen(window: Window, fullscreen: Boolean) {
    val controller = WindowInsetsControllerCompat(window, window.decorView)
    val attrs = window.attributes
    // 始终铺满屏幕，保证视频自适应
    attrs.width = ViewGroup.LayoutParams.MATCH_PARENT
    attrs.height = ViewGroup.LayoutParams.MATCH_PARENT
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        attrs.layoutInDisplayCutoutMode = if (fullscreen) {
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        } else {
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
        }
    }
    window.attributes = attrs
    if (fullscreen) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    } else {
        WindowCompat.setDecorFitsSystemWindows(window, true)
        controller.show(WindowInsetsCompat.Type.systemBars())
    }
}

@Composable
private fun SpeedMenu(speed: Float, onSpeedChange: (Float) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) {
            Text(formatSpeed(speed), color = Color.White)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SPEED_OPTIONS.forEach { option ->
                DropdownMenuItem(
                    text = { Text("${formatSpeed(option)} 倍速") },
                    onClick = {
                        onSpeedChange(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

/** 1.0f -> "1.0x"，1.25f -> "1.25x" */
private fun formatSpeed(speed: Float): String =
    if (speed == speed.toInt().toFloat()) "%.1fx".format(speed) else "${speed}x"

private fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
