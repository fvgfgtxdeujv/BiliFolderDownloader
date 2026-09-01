package com.bilifolder.downloader.util

import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LogStoreTest {

    private val store = LogStore(ApplicationProvider.getApplicationContext())

    @After
    fun tearDown() {
        store.shutdown()
    }

    @Test
    fun insertAndQuerySinceReturnsRowsInOrder() {
        val now = System.currentTimeMillis()
        store.insert(now - 10_000, "old line")
        store.insert(now, "line1")
        store.insert(now + 1, "line2")

        val rows = store.querySince(now - 5_000)
        assertEquals(listOf("line1", "line2"), rows)
    }

    @Test
    fun querySinceFiltersByTime() {
        val now = System.currentTimeMillis()
        store.insert(now - 60_000, "too old")
        store.insert(now, "fresh")

        val rows = store.querySince(now - 30_000)
        assertEquals(listOf("fresh"), rows)
    }

    @Test
    fun deleteOlderThanRemovesExpired() {
        val now = System.currentTimeMillis()
        store.insert(now - 60_000, "expired")
        store.insert(now - 5_000, "kept")
        store.insert(now, "kept2")

        // 等待写队列排空
        store.querySince(0)
        store.deleteOlderThan(now - 30_000)
        // 等待删除完成
        store.querySince(0)

        val rows = store.querySince(0)
        assertEquals(2, rows.size)
        assertTrue(rows.none { it == "expired" })
    }
}
