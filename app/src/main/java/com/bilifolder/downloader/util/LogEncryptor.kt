package com.bilifolder.downloader.util

import org.bouncycastle.asn1.ASN1Encoding
import org.bouncycastle.asn1.ASN1Primitive
import org.bouncycastle.asn1.ASN1OutputStream
import org.bouncycastle.asn1.pkcs.RSAPublicKey
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cms.CMSAlgorithm
import org.bouncycastle.cms.CMSAuthEnvelopedDataGenerator
import org.bouncycastle.cms.CMSProcessableByteArray
import org.bouncycastle.cms.jcajce.JceCMSContentEncryptorBuilder
import org.bouncycastle.cms.jcajce.JceKeyTransRecipientInfoGenerator
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openssl.PEMKeyPair
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import org.bouncycastle.operator.OutputAEADEncryptor
import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.security.KeyFactory
import java.security.PublicKey
import java.security.cert.X509Certificate
import java.security.spec.RSAPublicKeySpec
import java.util.Base64

/**
 * 调试日志加密器（正式版「调试日志」开关使用）。
 *
 * 使用与 `openssl cms -encrypt -recip cert.pem -aes-256-gcm` 完全兼容的
 * **CMS AuthEnvelopedData（RFC 5083）**：整个日志文本用随机密钥 AES-256-GCM 加密，
 * 内容密钥用证书/公钥中的 RSA 密钥加密。
 *
 * [encrypt] 返回 `openssl cms -decrypt` 默认可直接读取的 SMIME 文本
 * （MIME 头 + base64 每 64 字符换行），保存为导出文件后解密命令为：
 *
 * ```
 * openssl cms -decrypt -in bili_debug_export.txt -inkey private_key.pem -out log.txt
 * ```
 *
 * 说明：BC 的普通 [org.bouncycastle.cms.CMSEnvelopedDataGenerator] 搭配 GCM 会生成
 * openssl 无法正确解密的非标准结构，必须使用 [CMSAuthEnvelopedDataGenerator]（RFC 5083）。
 */
class LogEncryptor private constructor(
    private val certificate: X509Certificate?,
    private val publicKey: PublicKey,
) {

    /**
     * 把 [message] 整体加密一次，返回 openssl SMIME 多行文本（含结尾换行）；
     * 失败返回 null。
     */
    fun encrypt(message: String): String? = runCatching {
        val generator = CMSAuthEnvelopedDataGenerator()
        val recipient = if (certificate != null) {
            JceKeyTransRecipientInfoGenerator(certificate).setProvider(BC_PROVIDER)
        } else {
            val ski = org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils()
                .createSubjectKeyIdentifier(publicKey).keyIdentifier
            JceKeyTransRecipientInfoGenerator(ski, publicKey).setProvider(BC_PROVIDER)
        }
        generator.addRecipientInfoGenerator(recipient)
        // 注意：必须强转为 OutputAEADEncryptor（GCM/CCM 等 AEAD 算法），
        // 普通 OutputEncryptor 会生成非标准 EnvelopedData
        val encryptor = JceCMSContentEncryptorBuilder(CMSAlgorithm.AES256_GCM)
            .setProvider(BC_PROVIDER)
            .build() as OutputAEADEncryptor
        val cms = generator.generate(CMSProcessableByteArray(message.toByteArray(Charsets.UTF_8)), encryptor)
        toSmime(forceDer(cms.encoded))
    }.getOrNull()

    companion object {
        /** 显式使用独立的 BC Provider 实例，避免依赖/污染 Android 内置旧版 BC */
        private val BC_PROVIDER: BouncyCastleProvider by lazy { BouncyCastleProvider() }

        /** base64 行宽（openssl SMIME 使用 64 字符/行） */
        private const val LINE_WIDTH = 64

        /** 把 DER 包装为 openssl SMIME 文本（MIME 头 + base64 换行，结尾带换行） */
        private fun toSmime(der: ByteArray): String {
            val b64 = Base64.getEncoder().encodeToString(der)
            val sb = StringBuilder(b64.length + b64.length / LINE_WIDTH + 160)
            sb.append("MIME-Version: 1.0\n")
            sb.append("Content-Disposition: attachment; filename=\"smime.p7m\"\n")
            sb.append("Content-Type: application/pkcs7-mime; smime-type=authEnveloped-data; name=\"smime.p7m\"\n")
            sb.append("Content-Transfer-Encoding: base64\n\n")
            var i = 0
            while (i < b64.length) {
                val end = minOf(i + LINE_WIDTH, b64.length)
                sb.append(b64, i, end).append('\n')
                i = end
            }
            return sb.toString()
        }

        /** BC 默认输出 BER（不定长编码），openssl SMIME 读取器要求 DER，这里强制转 DER */
        private fun forceDer(ber: ByteArray): ByteArray {
            val prim = ASN1Primitive.fromByteArray(ber)
            val bos = ByteArrayOutputStream()
            val os = ASN1OutputStream.create(bos, ASN1Encoding.DER)
            os.writeObject(prim)
            os.close()
            return bos.toByteArray()
        }

        /**
         * 从 PEM 文本解析证书或 RSA 公钥，创建加密器。
         * 支持：
         * - `openssl req -x509 ...` 生成的证书（`-----BEGIN CERTIFICATE-----`）——首选
         * - `openssl rsa -pubout` 产物（`-----BEGIN PUBLIC KEY-----`，SubjectPublicKeyInfo）
         * - `-----BEGIN RSA PUBLIC KEY-----`（PKCS#1）
         * - `-----BEGIN PRIVATE KEY-----` / `BEGIN RSA PRIVATE KEY`（自动取公钥）
         * 无法解析（无有效密钥）时返回 null，调用方按「未配置密钥」处理。
         */
        fun fromPem(pem: String): LogEncryptor? = runCatching {
            val obj = PEMParser(StringReader(pem)).readObject() ?: return null
            val converter = JcaPEMKeyConverter().setProvider(BC_PROVIDER)
            when (obj) {
                is X509CertificateHolder -> {
                    val cert = JcaX509CertificateConverter().setProvider(BC_PROVIDER)
                        .getCertificate(obj)
                    LogEncryptor(cert, cert.publicKey)
                }
                is SubjectPublicKeyInfo -> LogEncryptor(null, converter.getPublicKey(obj))
                is PEMKeyPair -> LogEncryptor(null, converter.getKeyPair(obj).public)
                is RSAPublicKey -> LogEncryptor(
                    null,
                    KeyFactory.getInstance("RSA").generatePublic(
                        RSAPublicKeySpec(obj.modulus, obj.publicExponent)
                    )
                )
                else -> return null
            }
        }.getOrNull()
    }
}
