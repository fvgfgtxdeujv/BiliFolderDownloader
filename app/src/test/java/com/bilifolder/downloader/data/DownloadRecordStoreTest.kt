package com.bilifolder.downloader.data

import androidx.test.core.app.ApplicationProvider
import com.bilifolder.downloader.data.model.DownloadEngineType
import com.bilifolder.downloader.data.model.TaskHistory
import com.bilifolder.downloader.data.model.WebDavConfig
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DownloadRecordStoreTest {

    private lateinit var store: DownloadRecordStore

    @Before
    fun setUp() {
        store = DownloadRecordStore(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun `已下载 bvid 持久化与去重`() = runBlocking {
        store.addDownloadedBvids(setOf("BV1", "BV2"))
        store.addDownloadedBvids(setOf("BV2", "BV3"))
        val all = store.downloadedBvids.first()
        assertEquals(setOf("BV1", "BV2", "BV3"), all)
    }

    @Test
    fun `设置读写默认值`() = runBlocking {
        val initial = store.settings.first()
        assertEquals(100, initial.limitKbps)
        assertEquals(80, initial.quality)
        assertFalse(initial.deleteAfterDownload)
        assertFalse(initial.wifiOnly)
        assertEquals(2, initial.retryCount)

        store.updateSettings { it.copy(limitKbps = 0, quality = 112, deleteAfterDownload = true, wifiOnly = true, retryCount = 0) }
        val updated = store.settings.first()
        assertEquals(0, updated.limitKbps)
        assertEquals(112, updated.quality)
        assertTrue(updated.deleteAfterDownload)
        assertTrue(updated.wifiOnly)
        assertEquals(0, updated.retryCount)
    }

    @Test
    fun `引擎类型与 WebDAV 配置持久化`() = runBlocking {
        store.setEngineType(DownloadEngineType.GOPEED)
        assertEquals(DownloadEngineType.GOPEED, store.engineType.first())

        val config = WebDavConfig(url = "https://dav.example.com", username = "u", autoUpload = true)
        store.setWebDavConfig(config)
        assertEquals(config, store.webDavConfig.first())
    }

    @Test
    fun `任务历史最新在前且限量 50`() = runBlocking {
        repeat(60) { i ->
            store.addTaskHistory(TaskHistory(timestamp = i.toLong(), folderName = "f$i", total = 1, success = 1, failed = 0))
        }
        val history = store.taskHistory.first()
        assertEquals(50, history.size)
        assertEquals("f59", history.first().folderName)
    }

    @Test
    fun `任务历史按时间戳删除`() = runBlocking {
        store.addTaskHistory(TaskHistory(timestamp = 1L, folderName = "a", total = 1, success = 1, failed = 0))
        store.addTaskHistory(TaskHistory(timestamp = 2L, folderName = "b", total = 1, success = 1, failed = 0))
        store.deleteTaskHistory(1L)
        val history = store.taskHistory.first()
        assertEquals(1, history.size)
        assertEquals("b", history.first().folderName)
    }

    @Test
    fun `合规标记与 MID`() = runBlocking {
        assertFalse(store.onboardingAgreed.first())
        store.setOnboardingAgreed(true)
        assertTrue(store.onboardingAgreed.first())

        store.setLastMid(123456L)
        assertEquals(123456L, store.lastMid.first())
    }
}
