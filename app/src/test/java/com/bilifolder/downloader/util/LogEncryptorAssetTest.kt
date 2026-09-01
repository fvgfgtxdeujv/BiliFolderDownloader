package com.bilifolder.downloader.util

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 验证 assets 中用户配置的真实加密证书/公钥可被正确解析与加密 */
class LogEncryptorAssetTest {

    @Test
    fun assetCertParsesAndEncrypts() {
        // 直接读取源资产文件（生产环境由 AssetManager 加载同一文件）
        val pem = File("src/main/assets/log_encryption_cert.pem")
            .takeIf { it.exists() }
            ?.readText()
        assertNotNull("assets/log_encryption_cert.pem 应存在", pem)

        val enc = LogEncryptor.fromPem(pem!!)
        assertNotNull("证书应可解析（LogEncryptor.fromPem）", enc)

        val smime = enc!!.encrypt("test message")
        assertNotNull("加密应成功", smime)
        assertTrue(smime!!.startsWith("MIME-Version: 1.0"))
        assertTrue(smime.contains("smime-type=authEnveloped-data"))
    }

    @Test
    fun assetPublicKeyStillParsesAsFallback() {
        val pem = File("src/main/assets/rsa_public_key.pem")
            .takeIf { it.exists() }
            ?.readText()
        assertNotNull("assets/rsa_public_key.pem 应存在", pem)

        val enc = LogEncryptor.fromPem(pem!!)
        assertNotNull("公钥应可解析（回退路径）", enc)
        assertNotNull(enc!!.encrypt("test message"))
    }
}
