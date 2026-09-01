package com.bilifolder.downloader.download

import com.bilifolder.downloader.data.model.VideoInfo
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * DownloadManager 纯逻辑单元测试：候选视频过滤（去重 + 勾选子集）。
 * 任务 6.4 核心部分——调度/网络/上传等依赖注入较重的逻辑不在此覆盖。
 */
class DownloadManagerTest {

    private fun v(bvid: String, title: String) = VideoInfo(bvid = bvid, title = title, cid = 1)

    private val videos = listOf(
        v("BV1", "视频一"),
        v("BV2", "视频二"),
        v("BV3", "视频三"),
        v("BV4", "视频四"),
    )

    @Test
    fun `已下载 bvid 被过滤`() {
        val selected = DownloadManager.filterCandidates(
            videos = videos,
            downloadedBvids = setOf("BV1", "BV3"),
            existingFileNames = emptySet(),
            selectedBvids = null,
            safeFileName = { it },
        )
        assertEquals(listOf("BV2", "BV4"), selected.map { it.bvid })
    }

    @Test
    fun `已存在文件名被过滤`() {
        val selected = DownloadManager.filterCandidates(
            videos = videos,
            downloadedBvids = emptySet(),
            existingFileNames = setOf("视频二"),
            selectedBvids = null,
            safeFileName = { it },
        )
        assertEquals(listOf("BV1", "BV3", "BV4"), selected.map { it.bvid })
    }

    @Test
    fun `文件名过滤使用安全化后的名字`() {
        val selected = DownloadManager.filterCandidates(
            videos = listOf(v("BV1", "A/B:C")),
            downloadedBvids = emptySet(),
            existingFileNames = setOf("A_B_C"),
            selectedBvids = null,
            safeFileName = { it.replace("/", "_").replace(":", "_") },
        )
        assertEquals(0, selected.size)
    }

    @Test
    fun `勾选子集收窄且已下载仍优先排除`() {
        val selected = DownloadManager.filterCandidates(
            videos = videos,
            downloadedBvids = setOf("BV2"),
            existingFileNames = emptySet(),
            selectedBvids = setOf("BV2", "BV4"),
            safeFileName = { it },
        )
        // BV2 虽被勾选但已下载，排除；BV4 保留
        assertEquals(listOf("BV4"), selected.map { it.bvid })
    }

    @Test
    fun `selectedBvids 为空集合时全部排除`() {
        val selected = DownloadManager.filterCandidates(
            videos = videos,
            downloadedBvids = emptySet(),
            existingFileNames = emptySet(),
            selectedBvids = emptySet(),
            safeFileName = { it },
        )
        assertEquals(0, selected.size)
    }

    @Test
    fun `selectedBvids 为 null 时保留全部未下载`() {
        val selected = DownloadManager.filterCandidates(
            videos = videos,
            downloadedBvids = emptySet(),
            existingFileNames = emptySet(),
            selectedBvids = null,
            safeFileName = { it },
        )
        assertEquals(4, selected.size)
    }
}
