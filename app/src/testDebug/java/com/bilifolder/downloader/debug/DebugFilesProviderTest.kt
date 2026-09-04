package com.bilifolder.downloader.debug

import android.database.Cursor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileNotFoundException

/**
 * DebugFilesProvider（仅 debug 变体存在，release 不含）的 SAF/DocumentsProvider 行为测试。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DebugFilesProviderTest {

    private lateinit var context: android.content.Context
    private lateinit var provider: DebugFilesProvider
    private lateinit var packageName: String

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        packageName = context.packageName
        // 手动构造 ProviderInfo（authority/权限等），模拟系统解析 debug 合并 manifest 后的结果
        val info = android.content.pm.ProviderInfo()
        info.authority = "$packageName.debugfiles"
        info.exported = true
        info.grantUriPermissions = true
        info.readPermission = "android.permission.MANAGE_DOCUMENTS"
        info.writePermission = "android.permission.MANAGE_DOCUMENTS"
        provider = DebugFilesProvider()
        provider.attachInfo(context, info)
    }

    private fun cursorString(cursor: Cursor, column: String): List<String> {
        val values = mutableListOf<String>()
        val index = cursor.getColumnIndex(column)
        while (cursor.moveToNext()) {
            values.add(cursor.getString(index))
        }
        cursor.close()
        return values
    }

    @Test
    fun queryRoots_returnsSingleRootNamedByPackage() {
        val cursor = provider.queryRoots(null)!!
        assertTrue(cursor.moveToFirst())
        assertEquals(packageName, cursor.getString(cursor.getColumnIndex(Root.COLUMN_ROOT_ID)))
        assertEquals(packageName, cursor.getString(cursor.getColumnIndex(Root.COLUMN_DOCUMENT_ID)))
        cursor.close()
    }

    @Test
    fun queryChildDocuments_rootListsDataVirtualDirectory() {
        val cursor = provider.queryChildDocuments(packageName, null, null as String?)!!
        val names = cursorString(cursor, Document.COLUMN_DISPLAY_NAME)
        assertTrue("根目录应含 data 虚拟目录，实际: $names", names.contains("data"))
    }

    @Test
    fun queryChildDocuments_bundleOverload_rootAlsoListsData() {
        // Android 15+ 系统经 Bundle(queryArgs) 重载访问
        val cursor = provider.queryChildDocuments(packageName, null, android.os.Bundle())!!
        val names = cursorString(cursor, Document.COLUMN_DISPLAY_NAME)
        assertTrue("Bundle 重载根目录应含 data 虚拟目录，实际: $names", names.contains("data"))
    }

    @Test
    fun queryChildDocuments_dataDirListsExistingSubdirectories() {
        // files 目录由 Robolectric context 保证存在
        val dataDocId = "$packageName/data"
        val cursor = provider.queryChildDocuments(dataDocId, null, null as String?)!!
        val names = cursorString(cursor, Document.COLUMN_DISPLAY_NAME)
        assertTrue("data 下应含 files 目录，实际: $names", names.contains("files"))
    }

    @Test
    fun openDocument_readsBackWrittenFileContent() {
        val target = File(context.filesDir, "debug_probe.txt")
        target.writeText("hello-debug-provider")
        val docId = "$packageName/data/files/debug_probe.txt"

        assertEquals("application/octet-stream", provider.getDocumentType(docId))
        val cursor = provider.queryDocument(docId, null)!!
        assertTrue(cursor.moveToFirst())
        val pathIndex = cursor.getColumnIndex("mt_path")
        if (pathIndex >= 0) {
            assertEquals(target.absolutePath, cursor.getString(pathIndex))
        }
        cursor.close()

        provider.openDocument(docId, "r", null).use { pfd ->
            assertTrue(pfd.statSize > 0)
        }
    }

    @Test
    fun createWriteAndDeleteDocument_roundTrip() {
        val parentDocId = "$packageName/data/files"
        val newDocId = provider.createDocument(parentDocId, "text/plain", "roundtrip.txt")
        val created = File(context.filesDir, "roundtrip.txt")
        assertTrue(created.exists())

        provider.openDocument(newDocId, "w", null).use { pfd ->
            pfd.fileDescriptor?.let { fd ->
                java.io.FileOutputStream(fd).use { it.write("data".toByteArray()) }
            }
        }
        assertEquals("data", created.readText())

        provider.deleteDocument(newDocId)
        assertTrue(!created.exists())
    }

    @Test
    fun unknownDocument_throwsNotFound() {
        assertThrows(FileNotFoundException::class.java) {
            provider.openDocument("$packageName/data/nonexistent-file.xyz", "r", null)
        }
        assertThrows(FileNotFoundException::class.java) {
            provider.openDocument("other.package/data/x", "r", null)
        }
    }

    @Test
    fun getDocumentType_returnsDirectoryMimeForDataRoot() {
        assertEquals(Document.MIME_TYPE_DIR, provider.getDocumentType("$packageName/data"))
        assertNotNull(provider.queryDocument("$packageName/data", null))
    }
}
