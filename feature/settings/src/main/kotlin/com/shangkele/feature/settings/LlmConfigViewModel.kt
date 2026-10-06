package com.shangkele.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shangkele.core.ai.llm.LlmConfig
import com.shangkele.core.ai.llm.LlmConfigStore
import com.shangkele.core.ai.llm.LlmCredentialStore
import com.shangkele.core.ai.llm.OpenAiCompatibleClient
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LlmConfigUiState(
    val loaded: Boolean = false,
    val enabled: Boolean = false,
    val baseUrl: String = LlmConfig.DEFAULT_BASE_URL,
    val model: String = "",
    val hasApiKey: Boolean = false,
    val maskedKey: String? = null,
    /** 输入框里的新 Key（未保存前不回显已存的 Key） */
    val apiKeyInput: String = "",
    val models: List<String> = emptyList(),
    val loadingModels: Boolean = false,
    val testing: Boolean = false,
    /** 测试连接的结果或错误，直接展示给用户 */
    val status: String? = null,
    val statusIsError: Boolean = false,
) {
    /** 能否去拿模型列表：地址和 Key 都得有 */
    val canFetchModels: Boolean
        get() = baseUrl.isNotBlank() && (hasApiKey || apiKeyInput.isNotBlank())

    val canTest: Boolean
        get() = canFetchModels && model.isNotBlank()
}

@HiltViewModel
class LlmConfigViewModel @Inject constructor(
    private val configStore: LlmConfigStore,
    private val credentialStore: LlmCredentialStore,
    private val client: OpenAiCompatibleClient,
) : ViewModel() {

    private val _state = MutableStateFlow(LlmConfigUiState())
    val state: StateFlow<LlmConfigUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        val config = configStore.load()
        _state.value = _state.value.copy(
            loaded = true,
            enabled = config.enabled,
            baseUrl = config.baseUrl,
            model = config.model,
            hasApiKey = credentialStore.hasApiKey(),
            maskedKey = credentialStore.maskedApiKey(),
        )
    }

    // 每次改动立即落库。没有「保存」按钮 —— 这类配置改完就走，
    // 忘了保存会导致「明明填了却说不生效」这种很难自查的问题。
    fun setEnabled(value: Boolean) {
        persist(enabled = value)
    }

    fun setBaseUrl(value: String) {
        persist(baseUrl = value)
    }

    fun setModel(value: String) {
        persist(model = value)
    }

    fun setApiKeyInput(value: String) {
        _state.value = _state.value.copy(apiKeyInput = value)
    }

    /** 保存 API Key。加密后落盘，界面只留掩码。 */
    fun saveApiKey() {
        val input = _state.value.apiKeyInput.trim()
        if (input.isBlank()) return
        credentialStore.saveApiKey(input)
        _state.value = _state.value.copy(
            apiKeyInput = "",
            hasApiKey = true,
            maskedKey = credentialStore.maskedApiKey(),
            status = "API Key 已加密保存到本机",
            statusIsError = false,
        )
    }

    fun clearApiKey() {
        credentialStore.clear()
        _state.value = _state.value.copy(
            hasApiKey = false,
            maskedKey = null,
            status = "已删除本机保存的 API Key",
            statusIsError = false,
        )
    }

    /**
     * 从平台拉模型列表，而不是把模型名写死。
     *
     * 聚合平台的模型会增删改名，写死一个名字过几个月就会失效，
     * 而且用户不知道去哪里改。
     */
    fun loadModels() {
        if (_state.value.loadingModels) return
        val apiKey = effectiveApiKey() ?: return
        _state.value = _state.value.copy(loadingModels = true, status = null)

        viewModelScope.launch {
            val result = client.listModels(_state.value.baseUrl, apiKey)
            _state.value = result.fold(
                onSuccess = { models ->
                    _state.value.copy(
                        loadingModels = false,
                        models = models,
                        status = if (models.isEmpty()) {
                            "平台没有返回任何模型"
                        } else {
                            "共 ${models.size} 个模型，点选下面的一项"
                        },
                        statusIsError = models.isEmpty(),
                    )
                },
                onFailure = {
                    _state.value.copy(
                        loadingModels = false,
                        status = it.message ?: "获取模型列表失败",
                        statusIsError = true,
                    )
                },
            )
        }
    }

    fun testConnection() {
        if (_state.value.testing) return
        val apiKey = effectiveApiKey() ?: return
        _state.value = _state.value.copy(testing = true, status = "正在测试…", statusIsError = false)

        viewModelScope.launch {
            val result = client.testConnection(
                baseUrl = _state.value.baseUrl,
                apiKey = apiKey,
                model = _state.value.model,
            )
            _state.value = result.fold(
                onSuccess = {
                    _state.value.copy(
                        testing = false,
                        status = "连接正常，模型回复：${it.take(40)}",
                        statusIsError = false,
                    )
                },
                onFailure = {
                    _state.value.copy(
                        testing = false,
                        status = it.message ?: "测试失败",
                        statusIsError = true,
                    )
                },
            )
        }
    }

    /** 优先用输入框里还没保存的新 Key，这样用户可以先测试再保存。 */
    private fun effectiveApiKey(): String? =
        _state.value.apiKeyInput.trim().takeIf { it.isNotBlank() }
            ?: credentialStore.apiKey()?.takeIf { it.isNotBlank() }

    private fun persist(
        enabled: Boolean? = null,
        baseUrl: String? = null,
        model: String? = null,
    ) {
        val current = _state.value
        val next = LlmConfig(
            baseUrl = baseUrl ?: current.baseUrl,
            model = model ?: current.model,
            enabled = enabled ?: current.enabled,
        )
        configStore.save(next)
        _state.value = current.copy(
            enabled = next.enabled,
            baseUrl = next.baseUrl,
            model = next.model,
        )
    }
}
