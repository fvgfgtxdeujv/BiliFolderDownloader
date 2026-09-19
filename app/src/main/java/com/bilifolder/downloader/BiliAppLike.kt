package com.bilifolder.downloader

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import com.bilifolder.downloader.data.AppContainer
import com.bilifolder.downloader.util.LogUtil
import com.tencent.tinker.entry.ApplicationLike
import com.tencent.tinker.lib.tinker.TinkerInstaller
import java.io.File

/**
 * Tinker delegate：由 [BiliApp]（TinkerApplication）在 attachBaseContext 时反射实例化，
 * 之后 Application 的全部生命周期回调经 loader 转到本类。
 *
 * 进程分工：
 * - 主进程：安装 Tinker → 构建 [AppContainer] → 延迟检查本地补丁目录
 * - `:tinker` 补丁合成进程：只跑 Tinker 合成 service，不构建容器（避免日志库/存储被双开）
 *
 * 本地补丁加载约定：把补丁包命名为 `patch.apk` 放入 `filesDir/tinker_local/`
 * （正式版可在设置页经 SAF 选择后由 `PatchInstaller` 写入），
 * 冷启动后自动提交给 Tinker 合成加载，无需重装 APK。
 */
class BiliAppLike(
    application: Application,
    tinkerFlags: Int,
    tinkerLoadVerifyFlag: Boolean,
    applicationStartElapsedTime: Long,
    applicationStartMillisTime: Long,
    tinkerResultIntent: Intent,
) : ApplicationLike(
    application,
    tinkerFlags,
    tinkerLoadVerifyFlag,
    applicationStartElapsedTime,
    applicationStartMillisTime,
    tinkerResultIntent,
) {

    override fun onBaseContextAttached(base: Context) {
        super.onBaseContextAttached(base)
        // 安装 Tinker（默认 LoadReporter / PatchReporter / PatchListener / ResultService / UpgradePatch）。
        // install 须在最早时机完成，保证补丁 dex 能介入类加载。
        TinkerInstaller.install(this)
    }

    override fun onCreate() {
        super.onCreate()
        val app = getApplication()
        if (!isMainProcess(app)) {
            LogUtil.d(TAG, "非主进程（补丁合成等），跳过容器初始化")
            return
        }
        val container = AppContainer(app)
        BiliApp.attachContainer(container)
        container.start()
        scheduleLocalPatchCheck(app)
    }

    /** 冷启动后延迟检查本地补丁目录，存在 patch.apk 即提交 Tinker 加载 */
    private fun scheduleLocalPatchCheck(app: Application) {
        Handler(Looper.getMainLooper()).postDelayed({
            runCatching {
                val patchFile = File(File(app.filesDir, LOCAL_PATCH_REL_DIR), LOCAL_PATCH_FILE)
                if (!patchFile.isFile) {
                    LogUtil.d(TAG, "本地补丁检查: 无补丁文件 ${patchFile.absolutePath}")
                    return@runCatching
                }
                LogUtil.w(
                    TAG,
                    "本地补丁检查: 发现 ${patchFile.absolutePath} size=${patchFile.length()}，提交合成",
                )
                TinkerInstaller.onReceiveUpgradePatch(app, patchFile.absolutePath)
            }.onFailure { e ->
                LogUtil.e(TAG, "本地补丁检查失败", e)
            }
        }, LOCAL_PATCH_CHECK_DELAY_MS)
    }

    /** 仅主进程（进程名 == applicationId）才初始化业务容器 */
    private fun isMainProcess(app: Application): Boolean {
        val packageName = app.packageName
        val current = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Application.getProcessName()
        } else {
            @Suppress("DEPRECATION")
            val am = app.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            am?.runningAppProcesses?.firstOrNull { it.pid == Process.myPid() }?.processName
        }
        return current == null || current == packageName
    }

    private companion object {
        const val TAG = "BiliAppLike"
        const val LOCAL_PATCH_REL_DIR = "tinker_local"
        const val LOCAL_PATCH_FILE = "patch.apk"
        const val LOCAL_PATCH_CHECK_DELAY_MS = 5_000L
    }
}
