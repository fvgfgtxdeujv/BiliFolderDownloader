package com.bilifolder.downloader

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.core.app.ActivityCompat
import com.bilifolder.downloader.ui.AppNavHost
import com.bilifolder.downloader.ui.theme.AppTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestLegacyStoragePermissionIfNeeded()
        setContent {
            AppTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    AppNavHost(activity = this)
                }
            }
        }
    }

    /**
     * Android 10 及以下：请求写外部存储权限。
     * 成品落在公共 `Movies/BiliFolderDownloader/`，Android 11+ 直接路径写自建媒体文件无需权限，
     * 仅 API 26-29 需要该运行时权限。
     */
    private fun requestLegacyStoragePermissionIfNeeded() {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.Q) return
        val granted = ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                REQUEST_WRITE_STORAGE,
            )
        }
    }

    private companion object {
        const val REQUEST_WRITE_STORAGE = 1001
    }
}
