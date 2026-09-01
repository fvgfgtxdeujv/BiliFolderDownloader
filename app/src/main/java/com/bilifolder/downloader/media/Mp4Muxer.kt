package com.bilifolder.downloader.media

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import com.bilifolder.downloader.util.LogUtil
import java.io.File
import java.nio.ByteBuffer

/**
 * 音视频流复制合并（设计 4.3，需求 4）。
 *
 * MediaExtractor + MediaMuxer 纯流复制，等效 ffmpeg `-c:v copy -c:a copy`：
 * - 输入：视频流 MP4 + 音频流 MP4（可为空）
 * - 输出：合并后的 MP4，写旋转角（KEY_ROTATION）
 * - 无音频流时仅写视频轨（对应 `1.py#L867-L871` 分支）
 * - 合并成功后由调用方删除临时 `_video.mp4`/`_audio.mp4`（对应 `1.py#L879-L882`）
 *
 * 实现采用"先视频轨全部样本，再音频轨全部样本"的顺序写入，
 * 各轨内部时间戳保持单调递增，避免 MediaMuxer 对乱序写入报错。
 */
object Mp4Muxer {

    private const val TAG = "Mp4Muxer"

    class MuxException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /**
     * 合并音视频。
     * @param videoPath 视频流文件
     * @param audioPath 音频流文件（不存在或为空时跳过音频轨）
     * @param outputPath 输出成品 mp4
     */
    fun mux(videoPath: String, audioPath: String?, outputPath: String) {
        val videoFile = File(videoPath)
        if (!videoFile.exists() || videoFile.length() == 0L) {
            throw MuxException("视频流文件缺失或为空: $videoPath")
        }
        val audioFile = audioPath?.let { File(it) }?.takeIf { it.exists() && it.length() > 0 }
        LogUtil.d(TAG, "mux: 开始合并 video=$videoPath(${videoFile.length()}B) audio=${audioFile?.length() ?: 0}B -> $outputPath")

        val videoExtractor = MediaExtractor()
        val audioExtractor = audioFile?.let { MediaExtractor() }
        var muxer: MediaMuxer? = null
        try {
            videoExtractor.setDataSource(videoPath)
            val videoTrackIndex = findTrackIndex(videoExtractor, MediaFormat.MIMETYPE_VIDEO_AVC)
                ?: findFirstTrackOfType(videoExtractor, "video")
                ?: throw MuxException("视频流中未找到视频轨道")
            videoExtractor.selectTrack(videoTrackIndex)
            val videoFormat = videoExtractor.getTrackFormat(videoTrackIndex)

            if (audioExtractor != null) {
                audioExtractor.setDataSource(audioFile.absolutePath)
            }
            val audioTrackIndex = audioExtractor?.let { findTrackIndex(it, MediaFormat.MIMETYPE_AUDIO_AAC) }
                ?: audioExtractor?.let { findFirstTrackOfType(it, "audio") }
            val audioFormat = if (audioTrackIndex != null && audioExtractor != null) {
                audioExtractor.selectTrack(audioTrackIndex)
                audioExtractor.getTrackFormat(audioTrackIndex)
            } else {
                null
            }
            LogUtil.d(TAG, "mux: 视频轨 index=$videoTrackIndex mime=${videoFormat.getString(MediaFormat.KEY_MIME)} 音频轨 index=$audioTrackIndex")

            muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            // 旋转角：B 站 DASH 视频常带 90/270 旋转，须写入输出（对应 1.py 旋转角处理）
            val rotation = try {
                videoFormat.getInteger(MediaFormat.KEY_ROTATION)
            } catch (e: Exception) {
                0
            }
            if (rotation != 0) {
                LogUtil.d(TAG, "mux: 写入旋转角 $rotation")
                runCatching { muxer.setOrientationHint(rotation) }
            }

            val videoMuxTrack = muxer.addTrack(videoFormat)
            val audioMuxTrack = audioFormat?.let { muxer.addTrack(it) }

            muxer.start()

            // 先写视频轨全部样本
            copyTrack(videoExtractor, muxer, videoMuxTrack)
            // 再写音频轨全部样本
            if (audioExtractor != null && audioMuxTrack != null) {
                copyTrack(audioExtractor, muxer, audioMuxTrack)
            }

            muxer.stop()
            LogUtil.d(TAG, "mux: 合并完成 $outputPath")
        } catch (e: MuxException) {
            LogUtil.e(TAG, "mux: 合并失败 $outputPath", e)
            throw e
        } catch (e: Exception) {
            LogUtil.e(TAG, "mux: 合并异常 $outputPath", e)
            throw MuxException("合并失败: ${e.message}", e)
        } finally {
            runCatching { videoExtractor.release() }
            audioExtractor?.let { runCatching { it.release() } }
            muxer?.let { runCatching { it.release() } }
        }
    }

    /** 优先匹配指定 MIME 类型的轨道 */
    private fun findTrackIndex(extractor: MediaExtractor, mime: String): Int? {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mimeType = format.getString(MediaFormat.KEY_MIME)
            if (mimeType != null && mimeType.startsWith(mime.substringBefore("/"))) {
                return i
            }
        }
        return null
    }

    /** 按类型前缀（video/audio）找第一个轨道 */
    private fun findFirstTrackOfType(extractor: MediaExtractor, type: String): Int? {
        for (i in 0 until extractor.trackCount) {
            val mimeType = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
            if (mimeType != null && mimeType.startsWith(type)) {
                return i
            }
        }
        return null
    }

    /** 将 extractor 当前选中轨道全部样本写入 muxer 指定轨道 */
    private fun copyTrack(extractor: MediaExtractor, muxer: MediaMuxer, muxTrack: Int) {
        val buffer = ByteBuffer.allocateDirect(SAMPLE_BUFFER_SIZE)
        val bufferInfo = MediaCodec.BufferInfo()
        while (true) {
            val sampleSize = extractor.readSampleData(buffer, 0)
            if (sampleSize < 0) break
            bufferInfo.size = sampleSize
            bufferInfo.offset = 0
            bufferInfo.presentationTimeUs = extractor.sampleTime
            bufferInfo.flags = extractor.sampleFlags
            muxer.writeSampleData(muxTrack, buffer, bufferInfo)
            if (!extractor.advance()) break
        }
    }

    private const val SAMPLE_BUFFER_SIZE = 2 * 1024 * 1024
}
