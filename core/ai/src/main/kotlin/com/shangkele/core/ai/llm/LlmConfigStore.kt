package com.shangkele.core.ai.llm

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 云端模型配置的持久化。
 *
 * 存的是**非敏感项**（地址、模型名、开关）；API Key 走 [LlmCredentialStore] 加密存。
 * 两者刻意分开，见 [LlmConfig] 的注释。
 *
 * 用 SharedPreferences 而不是 DataStore：这里只有三个字段、读取在设置页与
 * 转写流程里同步发生，引入一个带协程与 Flow 的依赖不划算。
 */
@Singleton
class LlmConfigStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): LlmConfig = LlmConfig(
        baseUrl = prefs.getString(KEY_BASE_URL, null)?.takeIf { it.isNotBlank() }
            ?: LlmConfig.DEFAULT_BASE_URL,
        model = prefs.getString(KEY_MODEL, null).orEmpty(),
        enabled = prefs.getBoolean(KEY_ENABLED, false),
    )

    fun save(config: LlmConfig) {
        prefs.edit()
            .putString(KEY_BASE_URL, config.baseUrl.trim())
            .putString(KEY_MODEL, config.model.trim())
            .putBoolean(KEY_ENABLED, config.enabled)
            .apply()
    }

    private companion object {
        const val PREFS_NAME = "llm_config"
        const val KEY_BASE_URL = "base_url"
        const val KEY_MODEL = "model"
        const val KEY_ENABLED = "enabled"
    }
}
