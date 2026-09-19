package com.bilifolder.downloader.media

import com.bilifolder.downloader.util.LogUtil
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * zip 打包（设计 4.5，需求 9）。
 *
 * - 通过 `File.listFiles()` 扫描下载目录下全部 `.mp4`，找下一个可用编号（`1.zip`、`2.zip`…）
 * - `ZipOutputStream` 打包（`ZIP_STORED` 不压缩，对应 `1.py#L1039`，MP4 已高度压缩）
 * - 打包后用 `ZipInputStream` 逐条目校验 CRC；[zipFiles] 的 `deleteSources` 控制是否删除源文件
 *   （成品需保留在公共 Movies 目录时为 false）
 * - 校验失败保留源文件，返回失败原因
 */
object ZipHelper {

    private const val TAG = "ZipHelper"

    data class ZipResult(
        val zipFile: File?,
        val fileCount: Int = 0,
        val error: String? = null,
    )

    /** 打包目录下全部 mp4；无 mp4 时返回 null（默认打包后删除源文件） */
    fun zipDirectory(
        directory: File,
        outputDir: File = directory,
        deleteSources: Boolean = true,
    ): ZipResult {
        if (!directory.isDirectory) return ZipResult(null, error = "目录不存在: ${directory.path}")
        val mp4s = directory.listFiles { f -> f.isFile && f.name.endsWith(".mp4", ignoreCase = true) }
            ?.sortedBy { it.name.lowercase() }
            .orEmpty()
        return zipFiles(mp4s, outputDir, deleteSources)
    }

    /**
     * 打包指定的文件列表（通常为本次任务新下载的成品）。
     * @param deleteSources 打包并校验成功后是否删除源文件；成品需保留在公共目录时为 false
     */
    fun zipFiles(
        sources: List<File>,
        outputDir: File,
        deleteSources: Boolean = true,
    ): ZipResult {
        val mp4s = sources.filter { it.isFile && it.name.endsWith(".mp4", ignoreCase = true) }
            .sortedBy { it.name.lowercase() }
        if (mp4s.isEmpty()) {
            LogUtil.d(TAG, "zipFiles: 无 mp4，跳过打包")
            return ZipResult(null)
        }
        if (!outputDir.isDirectory) outputDir.mkdirs()
        LogUtil.d(TAG, "zipFiles: 打包 ${mp4s.size} 个 mp4 到 ${outputDir.path}")

        val zipFile = nextZipFile(outputDir)
        LogUtil.d(TAG, "zipFiles: 目标 $zipFile")
        try {
            FileOutputStream(zipFile).use { fos ->
                ZipOutputStream(fos).use { zos ->
                    zos.setMethod(ZipOutputStream.STORED)
                    for (mp4 in mp4s) {
                        val entry = ZipEntry(mp4.name).apply {
                            size = mp4.length()
                            crc = computeCrc(mp4)
                            time = mp4.lastModified()
                        }
                        zos.putNextEntry(entry)
                        FileInputStream(mp4).use { it.copyTo(zos) }
                        zos.closeEntry()
                        LogUtil.d(TAG, "zipFiles: 已写入 ${mp4.name} (${mp4.length()}B)")
                    }
                }
            }
        } catch (e: Exception) {
            LogUtil.e(TAG, "打包失败: ${e.message}", e)
            runCatching { zipFile.delete() }
            return ZipResult(null, error = "打包失败: ${e.message}")
        }

        // CRC 校验：全部通过后删除源 MP4
        val crcError = verifyCrc(zipFile, mp4s)
        if (crcError != null) {
            LogUtil.e(TAG, "打包后校验失败: $crcError")
            return ZipResult(zipFile, error = crcError)
        }
        if (deleteSources) {
            for (mp4 in mp4s) {
                runCatching { mp4.delete() }
            }
        }
        LogUtil.d(TAG, "打包完成: ${zipFile.name} 共 ${mp4s.size} 个文件")
        return ZipResult(zipFile, fileCount = mp4s.size)
    }

    /** 找下一个可用编号：1.zip、2.zip… */
    fun nextZipFile(directory: File): File {
        var index = 1
        while (File(directory, "$index.zip").exists()) index++
        return File(directory, "$index.zip")
    }

    private fun computeCrc(file: File): Long {
        val crc = java.util.zip.CRC32()
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                crc.update(buffer, 0, read)
            }
        }
        return crc.value
    }

    /** 校验 zip 内条目 CRC 与源文件一致；失败返回原因 */
    private fun verifyCrc(zipFile: File, sources: List<File>): String? {
        val sourceByName = sources.associateBy { it.name }
        return try {
            val zis = ZipInputStream(FileInputStream(zipFile))
            try {
                var entry: ZipEntry? = zis.nextEntry
                var checked = 0
                while (entry != null) {
                    val src = sourceByName[entry.name]
                    if (src != null) {
                        val actual = computeCrc(src)
                        if (actual != entry.crc) {
                            return "CRC 校验失败: ${entry.name}"
                        }
                        checked++
                    }
                    // 顺带消费流以推进
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (zis.read(buffer) != -1) { /* drain */ }
                    entry = zis.nextEntry
                }
                if (checked != sources.size) "zip 条目数与源文件数不一致" else null
            } finally {
                zis.close()
            }
        } catch (e: Exception) {
            "校验异常: ${e.message}"
        }
    }

    private const val DEFAULT_BUFFER_SIZE = 8192
}
