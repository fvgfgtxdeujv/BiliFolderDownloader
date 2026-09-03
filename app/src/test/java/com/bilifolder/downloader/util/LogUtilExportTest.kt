package com.bilifolder.downloader.util

import androidx.test.core.app.ApplicationProvider
import com.bilifolder.downloader.BuildConfig
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.StringWriter
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Security
import java.security.cert.X509Certificate
import java.util.Date

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LogUtilExportTest {

    /** 开发版：日志入库后导出文件包含详细日志行（仅 debug 变体；release 导出为加密 SMIME） */
    @Test
    fun exportProducesFileWithRecentLogs() {
        // release 变体 BuildConfig.DEBUG=false，导出走加密路径且无加密器时返回 null，
        // 明文导出语义由 debug 变体验证，加密导出由 releaseEncryptedExportDecryptsWithOpenSsl 覆盖
        assumeTrue("开发版明文导出用例，release 变体跳过", BuildConfig.DEBUG)

        val dir = createTempDir()
        try {
            LogUtil.initLogStore(ApplicationProvider.getApplicationContext(), dir)
            LogUtil.d("TAG", "export me")
            LogUtil.w("TAG", "warn too")
            LogUtil.e("TAG", "boom", RuntimeException("err"))

            val file = LogUtil.exportLogs()
            assertNotNull("应能导出日志文件", file)
            val content = file!!.readText()
            assertTrue("应包含 debug 消息", content.contains("export me"))
            assertTrue("应包含 warn 消息", content.contains("warn too"))
            assertTrue("应包含异常消息", content.contains("boom"))
            assertTrue("应包含级别标记", content.contains("[D]"))
        } finally {
            LogUtil.initLogStore(ApplicationProvider.getApplicationContext(), createTempDir())
            dir.deleteRecursively()
        }
    }

    /** 正式版导出核心：整个文本加密为 openssl SMIME，`openssl cms -decrypt` 直接还原 */
    @Test
    fun releaseEncryptedExportDecryptsWithOpenSsl() {
        assumeTrue("openssl 不可用，跳过互操作测试", opensslAvailable())

        val tmpDir = createTempDir()
        try {
            val kp = generateRsaKeyPair()
            val cert = generateSelfSignedCert(kp)
            val certPem = StringWriter().use { sw ->
                JcaPEMWriter(sw).use { it.writeObject(cert) }
                sw.toString()
            }
            val privPem = StringWriter().use { sw ->
                JcaPEMWriter(sw).use { it.writeObject(kp.private) }
                sw.toString()
            }
            val privFile = File(tmpDir, "private.pem").apply { writeText(privPem) }

            val enc = LogEncryptor.fromPem(certPem)
            assertNotNull("证书应可解析", enc)
            val fullText = "=== bili debug log export ===\n[10:00:00.001] [D] TAG: line 中文\n"
            val smime = LogUtil.encryptExport(fullText, enc!!)
            assertNotNull("正式版加密导出应成功", smime)
            assertTrue(smime!!.startsWith("MIME-Version: 1.0"))

            val encFile = File(tmpDir, "bili_debug_export.txt").apply { writeText(smime) }
            val outFile = File(tmpDir, "out.txt")
            val proc = ProcessBuilder(
                "openssl", "cms", "-decrypt",
                "-in", encFile.absolutePath,
                "-inkey", privFile.absolutePath,
                "-out", outFile.absolutePath,
            ).redirectErrorStream(true).start()
            val err = proc.inputStream.bufferedReader().readText()
            assertEquals("openssl 解密失败: $err", 0, proc.waitFor())
            assertEquals(fullText, outFile.readText())
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    private fun generateRsaKeyPair() = KeyPairGenerator.getInstance("RSA").apply {
        initialize(2048)
    }.generateKeyPair()

    /** 生成 SHA256withRSA 自签证书（与用户 `openssl req -new -x509` 等价） */
    private fun generateSelfSignedCert(keyPair: KeyPair): X509Certificate {
        val now = System.currentTimeMillis()
        val name = X500Name("CN=bili-debug-export-test")
        val holder = JcaX509v3CertificateBuilder(
            name,
            BigInteger.valueOf(now),
            Date(now - 1000),
            Date(now + 86_400_000L),
            name,
            keyPair.public,
        ).build(JcaContentSignerBuilder("SHA256withRSA").setProvider("BC").build(keyPair.private))
        return JcaX509CertificateConverter().setProvider("BC").getCertificate(holder)
    }

    private fun opensslAvailable(): Boolean = runCatching {
        ProcessBuilder("openssl", "version").redirectErrorStream(true).start().waitFor() == 0
    }.getOrDefault(false)

    companion object {
        init {
            if (Security.getProvider("BC") == null) {
                Security.addProvider(BouncyCastleProvider())
            }
        }
    }
}

