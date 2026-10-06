package com.shangkele.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shangkele.core.ai.model.AsrModelSpec
import com.shangkele.core.ai.model.SenseVoiceModelManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ModelManagerUiState(
    val loaded: Boolean = false,
    val asrReady: Boolean = false,
    val downloading: Boolean = false,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = AsrModelSpec.ASR_MODEL.sizeBytes,
) {
    val fraction: Float
        get() = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f

    val percent: Int get() = (fraction * 100).toInt()

    val downloadedLabel: String get() = mb(downloadedBytes)

    val totalLabel: String get() = mb(totalBytes)

    val statusLabel: String
        get() = when {
            asrReady -> "已就绪"
            downloading -> "下载中 $percent%（$downloadedLabel / $totalLabel）"
            downloadedBytes > 0 -> "已下载 $percent%，未完成"
            else -> "未下载"
        }

    private fun mb(bytes: Long): String = "%.0f MB".format(bytes / 1024.0 / 1024.0)
}

@HiltViewModel
class ModelManagerViewModel @Inject constructor(
    private val modelManager: SenseVoiceModelManager,
) : ViewModel() {

    private val _state = MutableStateFlow(ModelManagerUiState())
    val state: StateFlow<ModelManagerUiState> = _state.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private var downloadJob: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        val ready = modelManager.isAsrModelReady()
        _state.value = _state.value.copy(
            loaded = true,
            asrReady = ready,
            downloadedBytes = if (ready) {
                AsrModelSpec.ASR_MODEL.sizeBytes
            } else {
                modelManager.downloadedBytes()
            },
            totalBytes = AsrModelSpec.ASR_MODEL.sizeBytes,
        )
    }

    fun download() {
        if (downloadJob?.isActive == true) return
        _state.value = _state.value.copy(downloading = true)
        downloadJob = viewModelScope.launch {
            val result = modelManager.ensureAsrModel { done, total ->
                _state.value = _state.value.copy(
                    downloadedBytes = done,
                    totalBytes = total,
                )
            }
            _message.value = result.fold(
                onSuccess = { "语音模型已就绪，可以去笔记里转写了" },
                onFailure = { "下载失败：${it.message ?: "网络不可用"}" },
            )
            refresh()
            _state.value = _state.value.copy(downloading = false)
        }
    }

    /** 中断下载。已下载的部分会保留，下次从这里续传。 */
    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        _state.value = _state.value.copy(downloading = false)
        _message.value = "已暂停下载。已下的部分会保留，下次可以续传。"
        refresh()
    }

    fun delete() {
        modelManager.deleteAsrModel()
        _message.value = "已删除语音模型，释放约 230MB"
        refresh()
    }

    fun consumeMessage() {
        _message.value = null
    }
}
