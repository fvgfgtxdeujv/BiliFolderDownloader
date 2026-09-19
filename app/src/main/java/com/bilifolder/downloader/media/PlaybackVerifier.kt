package com.bilifolder.downloader.media

import android.media.MediaExtractor
import android.media.MediaFormat
import com.bilifolder.downloader.util.LogUtil
import java.io.File

/**
 * 可播放性校验（设计 4.4，需求 4）。
 *
 * 替代原 ffmpeg 解码 3 秒方案（`1.py#L884-L897`）：
 * MediaExtractor 打开输出文件，循环读取样本直至时间戳超过 3 秒，
 * 读取过程无异常且样本数大于 0 则判定通过。
 *
 * 校验失败时该视频计为下载失败，由调用方保留文件并记录日志。
 */
object PlaybackVerifier {

    private const val TAG = "PlaybackVerifier"

    private const val VERIFY_WINDOW_US = 3_000_000L // 3 秒

    /** @return 校验通过返回 null，失败返回原因描述 */
    fun verify(path: String): String? {
        val file = File(path)
        if (!file.exists() || file.length() == 0L) {
            LogUtil.w(TAG, "verify: 文件缺失或为空 $path")
            return "文件缺失或为空"
        }
        LogUtil.d(TAG, "verify: 开始校验 $path (${file.length()}B)")
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(path)
            if (extractor.trackCount == 0) {
                LogUtil.w(TAG, "verify: 无媒体轨道 $path")
                return "无媒体轨道"
            }
            // MediaExtractor 必须先选中轨道，readSampleData 才会返回数据（否则恒为 -1）
            val videoTrack = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("video/") == true
            }
            val trackIndex = videoTrack ?: 0
            extractor.selectTrack(trackIndex)
            LogUtil.d(TAG, "verify: 选中轨道 $trackIndex/${extractor.trackCount}")
            val maxInput = runCatching {
                extractor.getTrackFormat(trackIndex).getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
            }.getOrDefault(0)
            val bufferSize = maxInput.coerceIn(1 shl 20, 16 shl 20)
            val buffer = java.nio.ByteBuffer.allocateDirect(bufferSize)
            var sampleCount = 0
            var lastTimeUs = 0L
            while (true) {
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                sampleCount++
                lastTimeUs = extractor.sampleTime
                if (lastTimeUs >= VERIFY_WINDOW_US) break
                if (!extractor.advance()) break
            }
            if (sampleCount == 0) {
                LogUtil.w(TAG, "verify: 无法读取样本 $path")
                return "无法读取样本"
            }
            LogUtil.d(TAG, "verify: 校验通过 $path samples=$sampleCount t=$lastTimeUs")
            return null
        } catch (e: Exception) {
            LogUtil.w(TAG, "verify: 校验异常 $path", e)
            return "解析异常: ${e.message}"
        } finally {
            runCatching { extractor.release() }
        }
    }
}
