package com.shangkele.core.ai.dsp

import kotlin.math.roundToInt
/**
 * kaldi 风格 fbank 参数。
 *
 * 默认值全部照抄 SenseVoiceSmall 的 `config.yaml` → `frontend_conf`，
 * **不要随手改**：任何一项变了，模型看到的就是分布外特征，
 * 不报错，只是识别结果变差。
 *
 * @param inputScale 输入浮点采样的放大倍数。
 *   **这是最容易踩的一个坑**：kaldi 的 fbank 工作在 int16 量纲，
 *   funasr 的 `WavFrontend` 里有一行 `waveform = waveform * (1 << 15)`。
 *   少了它，CMVN 的工作点会偏掉（实测 `(x+means)*vars` 的均值从 0.08 掉到 -3.0），
 *   识别结果看着还行，实际已经退化。
 */
data class FbankOptions(
    val sampleRate: Int = 16_000,
    val frameLengthMs: Double = 25.0,
    val frameShiftMs: Double = 10.0,
    val numMelBins: Int = 80,
    val lowFreq: Double = 20.0,
    /** 0 或负数表示取 Nyquist */
    val highFreq: Double = 0.0,
    val preemphCoeff: Double = 0.97,
    val removeDcOffset: Boolean = true,
    /** true = 不补齐尾部残帧，帧数由 `(N - frameLength) / frameShift + 1` 决定 */
    val snipEdges: Boolean = true,
    val roundToPowerOfTwo: Boolean = true,
    /** kaldi 的 `log_floor`，等于 `std::numeric_limits<float>::epsilon()` */
    val logFloor: Float = 1.19209290e-07f,
    val inputScale: Double = 32768.0,
) {
    /** kaldi 用截断而不是四舍五入：`static_cast<int32>(samp_freq * 0.001 * ms)` */
    val frameLength: Int = (sampleRate * 0.001 * frameLengthMs).toInt()

    val frameShift: Int = (sampleRate * 0.001 * frameShiftMs).toInt()

    val paddedLength: Int = if (roundToPowerOfTwo) nextPowerOfTwo(frameLength) else frameLength

    /** 功率谱长度，= padded/2 + 1 */
    val numFftBins: Int = paddedLength / 2 + 1

    init {
        require(frameLength > 0 && frameShift > 0) { "帧长/帧移必须为正" }
        require(numMelBins > 0) { "mel 维度必须为正" }
    }

    /**
     * 帧数。与 kaldi 的 `NumFrames()` 一致。
     *
     * 注意是**整数除法**：8320 个采样点 → `1 + (8320-400)/160 = 50` 帧，
     * 尾部那 0.5 帧被丢弃。这和 golden 数据实测的 50 帧吻合。
     */
    fun numFrames(numSamples: Int): Int =
        if (numSamples < frameLength) 0
        else 1 + (numSamples - frameLength) / frameShift

    companion object {
        /** kaldi 的 `RoundUpToNearestPowerOfTwo`：400 → 512 */
        fun nextPowerOfTwo(n: Int): Int {
            require(n > 0) { "n 必须为正" }
            var v = n - 1
            v = v or (v shr 1)
            v = v or (v shr 2)
            v = v or (v shr 4)
            v = v or (v shr 8)
            v = v or (v shr 16)
            return v + 1
        }
    }
}

/** 便于计算：把毫秒时长换算成采样点。 */
internal fun Double.msToSamples(sampleRate: Int): Int = (sampleRate * this / 1000.0).roundToInt()
