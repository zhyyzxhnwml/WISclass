package com.shangkele.core.jwgl

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 教务会话（Cookie）的本地存储。
 *
 * 安全约定（docs/03-教务系统对接.md §九）：
 *  - Cookie 用 Android Keystore 里的 AES-256-GCM 密钥加密后再落盘，**不写明文**
 *  - 只存会话，**绝不存密码**（密码只在原生登录那一次的内存里存在）
 *  - 数据不出本机，不上传任何服务器
 */
@Singleton
class JwglSessionStore @Inject constructor(
    @ApplicationContext context: Context,
) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveSession(cookies: String) {
        val normalized = cookies.trim()
        if (normalized.isEmpty()) return
        prefs.edit().putString(KEY_COOKIE, KeystoreCrypto.encrypt(normalized)).apply()
    }

    fun loadSession(): String? =
        prefs.getString(KEY_COOKIE, null)
            ?.let { stored -> runCatching { KeystoreCrypto.decrypt(stored) }.getOrNull() }
            ?.takeIf { it.isNotBlank() }

    fun hasSession(): Boolean = loadSession() != null

    fun clearSession() {
        prefs.edit().remove(KEY_COOKIE).apply()
    }

    /** 记住学号只是为了下次少打几个字；不是账号体系。 */
    fun saveAccountHint(username: String) {
        prefs.edit().putString(KEY_ACCOUNT_HINT, username).apply()
    }

    fun accountHint(): String? = prefs.getString(KEY_ACCOUNT_HINT, null)

    /**
     * 记住**实测可用**的课表接口地址。
     *
     * 候选列表只是猜测，真正靠谱的是官方页面自己发的那个请求。
     * 抓到一次就存下来，之后的静默刷新直接走它，不用再一个个试。
     */
    fun saveScheduleEndpoint(url: String) {
        if (url.isBlank()) return
        prefs.edit().putString(KEY_SCHEDULE_ENDPOINT, url).apply()
    }

    fun scheduleEndpoint(): String? =
        prefs.getString(KEY_SCHEDULE_ENDPOINT, null)?.takeIf { it.isNotBlank() }

    companion object {
        private const val PREFS_NAME = "jwgl_session"
        private const val KEY_COOKIE = "cookie_v1"
        private const val KEY_ACCOUNT_HINT = "account_hint"
        private const val KEY_SCHEDULE_ENDPOINT = "schedule_endpoint"
    }
}

/**
 * Android Keystore 支撑的 AES-256-GCM 加解密。
 *
 * 存储格式：`base64(iv) + ":" + base64(cipherText)`。
 * GCM 自带完整性校验，密文被篡改会直接解密失败（返回 null，而不是拿到垃圾数据）。
 */
internal object KeystoreCrypto {

    private const val PROVIDER = "AndroidKeyStore"
    private const val ALIAS = "shangkele_jwgl_session_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128

    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val cipherText = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) +
            SEPARATOR +
            Base64.encodeToString(cipherText, Base64.NO_WRAP)
    }

    fun decrypt(stored: String): String? {
        val parts = stored.split(SEPARATOR)
        if (parts.size != 2) return null
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val cipherText = Base64.decode(parts[1], Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
        return String(cipher.doFinal(cipherText), Charsets.UTF_8)
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private const val SEPARATOR = ":"
}
