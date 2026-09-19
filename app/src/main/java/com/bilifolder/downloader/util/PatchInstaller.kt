package com.bilifolder.downloader.util

import android.content.Context
import android.net.Uri
import com.tencent.tinker.lib.tinker.TinkerInstaller
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Tinker 补丁包提交（设置页"选择补丁包"入口，需求 21 后续扩展）。
 *
 * 流程：用户在设置里经 SAF 选择本地 Tinker 补丁 apk → 复制到约定路径
 * `filesDir/tinker_local/patch.apk` → 交给 [TinkerInstaller.onReceiveUpgradePatch] 合成。
 *
 * 路径与 [com.bilifolder.downloader.BiliAppLike] 冷启动检查保持一致：即使本次提交中断，
 * 下次冷启动仍会自动拾取该文件。补丁合成由 Tinker 的 `:tinker` 进程完成，重启应用后生效。
 */
object PatchInstaller {

    private const val TAG = "PatchInstaller"
    private const val PATCH_REL_DIR = "tinker_local"
    private const val PATCH_FILE = "patch.apk"

    /** 本地补丁文件（与 [com.bilifolder.downloader.BiliAppLike] 冷启动检查约定一致） */
    fun patchFile(context: Context): File =
        File(File(context.filesDir, PATCH_REL_DIR), PATCH_FILE)

    /**
     * 复制所选补丁包到约定路径并提交合成。
     * @return 面向用户的结果描述
     */
    suspend fun submit(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        val target = patchFile(context)
        try {
            target.parentFile?.mkdirs()
            val copied = context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
                target.length()
            } ?: return@withContext "无法读取所选文件"
            if (copied <= 0L) return@withContext "补丁包为空，已忽略"

            LogUtil.d(TAG, "submit: 已复制 ${target.absolutePath} size=$copied，提交 Tinker 合成")
            TinkerInstaller.onReceiveUpgradePatch(context.applicationContext, target.absolutePath)
            "补丁已提交合成（${copied / 1024} KB），完成后重启应用生效"
        } catch (e: Exception) {
            LogUtil.e(TAG, "submit: 提交失败", e)
            "补丁提交失败：${e.message}"
        }
    }
}
