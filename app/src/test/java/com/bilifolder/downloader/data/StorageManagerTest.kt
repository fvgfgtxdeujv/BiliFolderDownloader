package com.bilifolder.downloader.data

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StorageManagerTest {

    private lateinit var storage: StorageManager

    @Before
    fun setUp() {
        storage = StorageManager(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun `安全化替换非法字符`() {
        assertEquals("a_b", storage.safeFileName("a/b"))
        assertEquals("a_b", storage.safeFileName("a\\b"))
        assertEquals("a_b", storage.safeFileName("a:b"))
        assertEquals("a_b", storage.safeFileName("a*b"))
        assertEquals("a_b", storage.safeFileName("a?b"))
        assertEquals("a_b", storage.safeFileName("a\"b"))
        assertEquals("a_b", storage.safeFileName("a<b"))
        assertEquals("a_b", storage.safeFileName("a>b"))
        assertEquals("a_b", storage.safeFileName("a|b"))
    }

    @Test
    fun `安全化折叠连续下划线并去首尾`() {
        assertEquals("a_b", storage.safeFileName("a___b"))
        assertEquals("a_b", storage.safeFileName(" _a_b_ "))
        assertEquals("a", storage.safeFileName("a"))
    }

    @Test
    fun `安全化空白标题回退`() {
        assertEquals("untitled", storage.safeFileName(""))
        assertEquals("untitled", storage.safeFileName("///"))
    }

    @Test
    fun `安全化截断超长标题`() {
        val long = "x".repeat(300)
        assertEquals(120, storage.safeFileName(long).length)
    }

    @Test
    fun `成品目录位于公共 Movies 下且可创建`() {
        val dir = storage.downloadDir
        assertEquals("BiliFolderDownloader", dir.name)
        assertTrue(dir.absolutePath.contains("Movies"))
        assertEquals(true, dir.isDirectory)
    }

    @Test
    fun `临时分片目录与应用专属 zip 目录可创建`() {
        assertEquals("downloads_tmp", storage.tempDir.name)
        assertEquals(true, storage.tempDir.isDirectory)
        assertEquals("zips", storage.zipDir.name)
        assertEquals(true, storage.zipDir.isDirectory)
    }
}
