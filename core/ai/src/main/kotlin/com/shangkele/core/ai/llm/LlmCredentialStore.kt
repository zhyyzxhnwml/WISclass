package com.shangkele.core.ai.llm

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
 * API Key 的密态存储。
 *
 * **为什么不能直接放 SharedPreferences**：手机上任何一个拿得到 root 的 App、
 * 或者一次 adb backup，就能把明文 Key 读走。而 Key 泄露等于别人能花你的额度。
 *
 * 所以：AES-256-GCM 加密，密钥由 **Android Keystore** 持有 —— 密钥材料本身
 * 不出安全硬件/系统密钥库，应用只能"请求它帮忙解密"，读不到密钥。
 *
 * 注意这不是绝对安全（root 设备上仍可让系统代为解密），但足以挡住
 * 「翻一下 SharedPreferences 文件」这个级别的泄露，是自用 App 的合理标准。
 */
@Singleton
class LlmCredentialStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveApiKey(apiKey: String) {
        if (apiKey.isBlank()) {
            clear()
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(apiKey.toByteArray(Charsets.UTF_8))
        prefs.edit()
            .putString(KEY_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
            .putString(KEY_CIPHER, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .apply()
    }

    /**
     * 读取。**解密失败返回 null 而不是抛异常** ——
     * 换机、恢复备份、清除凭据都可能导致 Keystore 里的密钥没了，
     * 那时应该表现为「需要重新填 Key」，而不是让整个设置页崩掉。
     */
    fun apiKey(): String? {
        val ivText = prefs.getString(KEY_IV, null) ?: return null
        val cipherText = prefs.getString(KEY_CIPHER, null) ?: return null
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(GCM_TAG_BITS, Base64.decode(ivText, Base64.NO_WRAP)),
            )
            String(cipher.doFinal(Base64.decode(cipherText, Base64.NO_WRAP)), Charsets.UTF_8)
        }.getOrNull()
    }

    fun hasApiKey(): Boolean = !apiKey().isNullOrBlank()

    /** 界面上展示用的掩码，例如 `sk-4qR8****Ko0`。 */
    fun maskedApiKey(): String? {
        val key = apiKey() ?: return null
        if (key.length <= 12) return "****"
        return key.take(8) + "****" + key.takeLast(4)
    }

    fun clear() {
        prefs.edit().remove(KEY_IV).remove(KEY_CIPHER).apply()
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFS_NAME = "llm_credentials"
        const val KEY_IV = "api_key_iv"
        const val KEY_CIPHER = "api_key_cipher"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "shangkele_llm_api_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
    }
}
