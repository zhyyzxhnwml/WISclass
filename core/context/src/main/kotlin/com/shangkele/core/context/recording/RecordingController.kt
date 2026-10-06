package com.shangkele.core.context.recording

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 录音控制。
 *
 * 用 `MediaRecorder` 直接录成 **16kHz 单声道 AAC**：
 *  - 体积可控：90 分钟约 32MB（对比原始 PCM 的 172MB）
 *  - 采样率就是 ASR 要的，之后送模型不用重采样
 *
 * 状态放在 [StateFlow] 里，前台服务只负责把它渲染成通知，
 * 避免把 MediaRecorder 交给跨进程的 Binder。
 *
 * 已知局限：录音期间进程被系统杀掉会丢失当前录音（文件不完整但能播）。
 * 断点续录留到后面做。
 */
@Singleton
class RecordingController @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val state: StateFlow<RecordingState> = _state.asStateFlow()

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var startedAtMs: Long = 0L
    /** 累计的已录时长（暂停时结算），用于暂停/恢复后算真实时长 */
    private var accumulatedMs: Long = 0L
    /** 本次「继续录」的起点（elapsedRealtime） */
    private var segmentStartUptime: Long = 0L
    private var ticker: Job? = null

    val isActive: Boolean get() = _state.value is RecordingState.Active

    /**
     * 开始录音。
     * @param noteId 已经落库的笔记 id（先建记录再录音，中途崩了也能看到一条残缺记录）
     */
    fun start(
        noteId: Long,
        outputFile: File,
        courseId: Long?,
        courseName: String?,
    ): Boolean {
        if (isActive) return false

        outputFile.parentFile?.mkdirs()
        val created = try {
            newRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(SAMPLE_RATE)
                setAudioEncodingBitRate(BIT_RATE)
                setAudioChannels(1)
                setOutputFile(outputFile.absolutePath)
                prepare()
                start()
            }
        } catch (e: Exception) {
            runCatching { recorder?.release() }
            recorder = null
            return false
        }

        recorder = created
        this.outputFile = outputFile
        startedAtMs = System.currentTimeMillis()
        accumulatedMs = 0L
        segmentStartUptime = SystemClock.elapsedRealtime()

        _state.value = RecordingState.Active(
            noteId = noteId,
            startedAtMs = startedAtMs,
            elapsedMs = 0L,
            paused = false,
            courseName = courseName,
            courseId = courseId,
        )
        startTicker()
        return true
    }

    fun pause() {
        val current = _state.value as? RecordingState.Active ?: return
        if (current.paused) return
        runCatching { recorder?.pause() }
        accumulatedMs = elapsedMsNow()
        segmentStartUptime = 0L
        _state.value = current.copy(elapsedMs = accumulatedMs, paused = true)
        stopTicker()
    }

    fun resume() {
        val current = _state.value as? RecordingState.Active ?: return
        if (!current.paused) return
        runCatching { recorder?.resume() }
        segmentStartUptime = SystemClock.elapsedRealtime()
        _state.value = current.copy(paused = false)
        startTicker()
    }

    /** 结束录音，返回产物信息。 */
    fun stop(): RecordingResult? {
        val current = _state.value as? RecordingState.Active ?: return null
        val durationMs = elapsedMsNow()

        stopTicker()
        val file = outputFile
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        recorder = null
        outputFile = null
        _state.value = RecordingState.Idle

        return RecordingResult(
            noteId = current.noteId,
            filePath = file?.absolutePath,
            durationMs = durationMs,
            sizeBytes = file?.length() ?: 0L,
        )
    }

    /** 异常情况下的清理，不产出结果。 */
    fun abandon() {
        stopTicker()
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        recorder = null
        outputFile = null
        _state.value = RecordingState.Idle
    }

    private fun elapsedMsNow(): Long {
        if (segmentStartUptime == 0L) return accumulatedMs
        return accumulatedMs + (SystemClock.elapsedRealtime() - segmentStartUptime)
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (true) {
                val current = _state.value as? RecordingState.Active ?: break
                if (!current.paused) {
                    _state.value = current.copy(elapsedMs = elapsedMsNow())
                }
                delay(TICK_MS)
            }
        }
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
    }

    @Suppress("DEPRECATION")
    private fun newRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            MediaRecorder()
        }

    private companion object {
        /** 16kHz 是 ASR 的标准输入采样率，直接录省一次重采样 */
        const val SAMPLE_RATE = 16_000
        const val BIT_RATE = 48_000
        const val TICK_MS = 500L
    }
}
