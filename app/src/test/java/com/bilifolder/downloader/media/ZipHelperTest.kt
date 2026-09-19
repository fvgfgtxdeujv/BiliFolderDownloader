package com.bilifolder.downloader.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipInputStream

class ZipHelperTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun createMp4(dir: File, name: String, size: Int = 1000): File {
        val f = File(dir, name)
        f.writeBytes(ByteArray(size) { (it % 251).toByte() })
        return f
    }

    @Test
    fun `打包生成递增编号并删除源文件`() {
        val dir = tempFolder.root
        createMp4(dir, "a.mp4")
        createMp4(dir, "b.mp4")

        val first = ZipHelper.zipDirectory(dir)
        assertNotNull(first.zipFile)
        assertEquals("1.zip", first.zipFile?.name)
        assertEquals(2, first.fileCount)
        assertTrue(File(dir, "1.zip").exists())
        assertFalse(File(dir, "a.mp4").exists())
        assertFalse(File(dir, "b.mp4").exists())

        // 第二次打包 → 2.zip
        createMp4(dir, "c.mp4")
        val second = ZipHelper.zipDirectory(dir)
        assertNotNull(second.zipFile)
        assertEquals("2.zip", second.zipFile?.name)
        assertEquals(1, second.fileCount)
        assertFalse(File(dir, "c.mp4").exists())
    }

    @Test
    fun `zip 内容可完整读取且条目 CRC 正确`() {
        val dir = tempFolder.root
        val src = createMp4(dir, "v.mp4", 5000)
        val expectedBytes = src.readBytes() // 打包后源文件会被删除，先取内容
        val result = ZipHelper.zipDirectory(dir)
        assertNull(result.error)
        val zipFile = result.zipFile!!
        ZipInputStream(zipFile.inputStream()).use { zis ->
            val entry = zis.nextEntry
            assertNotNull(entry)
            assertEquals("v.mp4", entry.name)
            // 逐字节读取源文件计算一致性
            val bytes = zis.readBytes()
            assertEquals(expectedBytes.toList(), bytes.toList())
        }
    }

    @Test
    fun `空目录返回 null`() {
        val dir = tempFolder.root
        val result = ZipHelper.zipDirectory(dir)
        assertNull(result.zipFile)
        assertEquals(0, result.fileCount)
    }

    @Test
    fun `目录不存在返回错误`() {
        val missing = File(tempFolder.root, "missing")
        val result = ZipHelper.zipDirectory(missing)
        assertNull(result.zipFile)
        assertTrue(result.error != null)
    }

    @Test
    fun `zipFiles 指定源且不删除源文件时成品保留`() {
        val dir = tempFolder.root
        val out = File(dir, "out").apply { mkdirs() }
        val a = createMp4(dir, "a.mp4")
        val b = createMp4(dir, "b.mp4")

        val result = ZipHelper.zipFiles(listOf(a, b), out, deleteSources = false)

        assertNotNull(result.zipFile)
        assertEquals(2, result.fileCount)
        assertNull(result.error)
        assertTrue(File(out, "1.zip").exists())
        assertTrue(a.exists())
        assertTrue(b.exists())
    }

    @Test
    fun `nextZipFile 编号跳过已存在`() {
        val dir = tempFolder.root
        File(dir, "1.zip").writeBytes(byteArrayOf(1))
        val next = ZipHelper.nextZipFile(dir)
        assertEquals(File(dir, "2.zip"), next)
    }
}
