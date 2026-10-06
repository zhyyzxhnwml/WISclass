package com.shangkele.core.ai.llm

/**
 * 云端大模型（OpenAI 兼容）配置。
 *
 * **API Key 不在这里**，它单独存在 [LlmCredentialStore]（Keystore 加密）。
 * 两者分开是因为这个对象会被日志、界面状态、DataStore 到处传，
 * 把密钥混进去迟早会漏到某个不该出现的地方。
 */
data class LlmConfig(
    /** 形如 `https://api.agnes-ai.cn/v1`，到 `/v1` 为止，不要带 `/chat/completions` */
    val baseUrl: String = DEFAULT_BASE_URL,
    val model: String = "",
    val enabled: Boolean = false,
) {
    val isUsable: Boolean get() = enabled && baseUrl.isNotBlank() && model.isNotBlank()

    /** 拼出对话端点。允许用户填了带尾斜杠的形式。 */
    val chatCompletionsUrl: String
        get() = baseUrl.trimEnd('/') + "/chat/completions"

    val modelsUrl: String
        get() = baseUrl.trimEnd('/') + "/models"

    companion object {
        /** 默认用 `.cn`：与用户控制台 `platform.agnes-ai.cn` 同域。可在设置里改。 */
        const val DEFAULT_BASE_URL = "https://api.agnes-ai.cn/v1"

        /**
         * 备选地址，设置页给出提示用。
         *
         * 实测四个候选里这两个返回 401（端点存在），
         * `api.agnes-ai.com` 返回 404（不存在）。
         */
        val KNOWN_BASE_URLS = listOf(
            "https://api.agnes-ai.cn/v1",
            "https://apihub.agnes-ai.com/v1",
        )
    }
}
