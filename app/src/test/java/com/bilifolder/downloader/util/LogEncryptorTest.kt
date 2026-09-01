package com.bilifolder.downloader.util

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.StringWriter
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Security
import java.security.cert.X509Certificate
import java.util.Date

class LogEncryptorTest {

    /** 端到端互操作：证书+GCM 整体加密 → openssl cms -decrypt（默认 SMIME）直接解密还原 */
    @Test
    fun opensslCmsDecryptInterop() {
        assumeTrue("openssl 不可用，跳过互操作测试", opensslAvailable())

        val tmpDir = createTempDir()
        try {
            val kp = generateRsaKeyPair()
            val cert = generateSelfSignedCert(kp)

            // 私钥写 PKCS#8 PEM（模拟用户保管的 private_key.pem）
            val privPem = StringWriter().use { sw ->
                JcaPEMWriter(sw).use { it.writeObject(kp.private) }
                sw.toString()
            }
            val privFile = File(tmpDir, "private.pem").apply { writeText(privPem) }
            // 证书写 PEM（模拟 log_encryption_cert.pem）
            val certPem = StringWriter().use { sw ->
                JcaPEMWriter(sw).use { it.writeObject(cert) }
                sw.toString()
            }

            // 模拟整份导出 txt（多行）
            val plain = buildString {
                append("=== bili debug log export (last 30 min, 5 lines) ===\n")
                repeat(5) { i -> append("[10:00:0$i.123] [D] TAG: line $i 中文日志\n") }
            }

            // 应用端用证书加密，encrypt 直接返回 openssl SMIME 文本
            val enc = LogEncryptor.fromPem(certPem)
            assertNotNull("证书应可解析", enc)
            val smime = enc!!.encrypt(plain)
            assertNotNull(smime)
            assertTrue(smime!!.contains("MIME-Version: 1.0"))
            assertTrue(smime.contains("smime-type=authEnveloped-data"))
            assertTrue(smime.lines().any { it.length == 64 })

            // SMIME 文本 -> 文件 -> openssl cms -decrypt（默认 SMIME 输入格式）
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
            assertEquals(plain, outFile.readText())
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    /** 裸公钥（无证书）也应能加密，输出同样的 SMIME 格式 */
    @Test
    fun publicKeyWithoutCertProducesSmime() {
        val kp = generateRsaKeyPair()
        val pem = StringWriter().use { sw ->
            JcaPEMWriter(sw).use { it.writeObject(kp.public) }
            sw.toString()
        }
        val enc = LogEncryptor.fromPem(pem)
        assertNotNull(enc)
        val smime = enc!!.encrypt("test")
        assertNotNull(smime)
        assertTrue(smime!!.startsWith("MIME-Version: 1.0\n"))
        assertTrue(smime.contains("Content-Transfer-Encoding: base64"))
    }

    /** 解析用户 `openssl rsa -pubout` 风格的 BEGIN PUBLIC KEY 公钥 */
    @Test
    fun fromPemParsesOpenSslStylePublicKey() {
        val kp = generateRsaKeyPair()
        val pem = StringWriter().use { sw ->
            JcaPEMWriter(sw).use { it.writeObject(kp.public) }
            sw.toString()
        }
        assertTrue(pem.contains("BEGIN PUBLIC KEY"))

        val enc = LogEncryptor.fromPem(pem)
        assertNotNull(enc)
        assertNotNull(enc!!.encrypt("test"))
    }

    /** 解析用户 `openssl req -x509` 生成的证书 */
    @Test
    fun fromPemParsesCertificate() {
        val kp = generateRsaKeyPair()
        val cert = generateSelfSignedCert(kp)
        val pem = StringWriter().use { sw ->
            JcaPEMWriter(sw).use { it.writeObject(cert) }
            sw.toString()
        }
        assertTrue(pem.contains("BEGIN CERTIFICATE"))

        val enc = LogEncryptor.fromPem(pem)
        assertNotNull("证书应可解析", enc)
        assertNotNull(enc!!.encrypt("test"))
    }

    /** 无效/占位内容返回 null */
    @Test
    fun fromPemRejectsInvalidContent() {
        assertNull(LogEncryptor.fromPem(""))
        assertNull(LogEncryptor.fromPem("not a pem"))
        assertNull(LogEncryptor.fromPem("-----BEGIN PUBLIC KEY-----\nabc\n-----END PUBLIC KEY-----"))
        assertNull(LogEncryptor.fromPem("-----BEGIN CERTIFICATE-----\nabc\n-----END CERTIFICATE-----"))
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
            // JcaContentSignerBuilder/JcaX509CertificateConverter 需要名为 "BC" 的 Provider
            if (Security.getProvider("BC") == null) {
                Security.addProvider(BouncyCastleProvider())
            }
        }
    }
}
