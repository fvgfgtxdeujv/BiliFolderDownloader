package com.bilifolder.downloader.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 密钥提供者：默认 Android Keystore 实现；单元测试可注入 JVM 密钥替代。
 */
fun interface SecretKeyProvider {
    fun getOrCreateKey(): SecretKey
}

/** Android Keystore 托管的 AES-GCM 密钥提供者 */
class AndroidKeyStoreKeyProvider : SecretKeyProvider {
    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply {
        load(null)
    }

    override fun getOrCreateKey(): SecretKey {
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "bili_cookie_key"
    }
}

/**
 * Cookie 与 WebDAV 密码的加密存储（设计 9.5，需求 2、20）。
 *
 * 使用 Android Keystore 托管的 AES-256-GCM 密钥（别名 [AndroidKeyStoreKeyProvider.KEY_ALIAS]），
 * 密文以 `base64(iv).base64(ciphertext)` 形式存入 SharedPreferences，
 * 存储内容不包含任何明文凭证。
 */
class CookieStore(
    context: Context,
    private val keyProvider: SecretKeyProvider = AndroidKeyStoreKeyProvider(),
) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 登录 Cookie 与 WebDAV 密码的存储键（设计 9.5 存储键表） */
    object Keys {
        const val SESSDATA = "cookie_sessdata"
        const val BILI_JCT = "cookie_bili_jct"
        const val MID = "cookie_mid"
        const val WEBDAV_PASSWORD = "webdav_password"
    }

    fun saveSession(sessdata: String, biliJct: String, mid: String) {
        save(Keys.SESSDATA, sessdata)
        save(Keys.BILI_JCT, biliJct)
        save(Keys.MID, mid)
    }

    fun sessdata(): String? = read(Keys.SESSDATA)
    fun biliJct(): String? = read(Keys.BILI_JCT)
    fun mid(): String? = read(Keys.MID)

    fun saveWebDavPassword(password: String) {
        if (password.isBlank()) {
            prefs.edit().remove(Keys.WEBDAV_PASSWORD).apply()
        } else {
            save(Keys.WEBDAV_PASSWORD, password)
        }
    }

    fun webDavPassword(): String? = read(Keys.WEBDAV_PASSWORD)

    fun clearSession() {
        prefs.edit()
            .remove(Keys.SESSDATA)
            .remove(Keys.BILI_JCT)
            .remove(Keys.MID)
            .apply()
    }

    fun clearWebDavPassword() {
        prefs.edit().remove(Keys.WEBDAV_PASSWORD).apply()
    }

    private fun save(key: String, plain: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val ivBase64 = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val cipherBase64 = Base64.encodeToString(encrypted, Base64.NO_WRAP)
        prefs.edit().putString(key, "$ivBase64.$cipherBase64").apply()
    }

    private fun read(key: String): String? {
        val stored = prefs.getString(key, null) ?: return null
        return try {
            val parts = stored.split(".", limit = 2)
            if (parts.size != 2) {
                // 数据格式异常，视为损坏并清理
                prefs.edit().remove(key).apply()
                null
            } else {
                val iv = Base64.decode(parts[0], Base64.NO_WRAP)
                val cipherText = Base64.decode(parts[1], Base64.NO_WRAP)
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
                String(cipher.doFinal(cipherText), Charsets.UTF_8)
            }
        } catch (e: AEADBadTagException) {
            // 数据被篡改或密钥失效：清除该键（设计 9.5 异常处理）
            prefs.edit().remove(key).apply()
            null
        } catch (e: Exception) {
            // 解密失败统一按失效处理，避免崩溃
            prefs.edit().remove(key).apply()
            null
        }
    }

    private fun getOrCreateKey(): SecretKey = keyProvider.getOrCreateKey()

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val PREFS_NAME = "bili_secure_prefs"
        @Suppress("unused")
        val RANDOM = SecureRandom()
    }
}
