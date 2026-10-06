package com.shangkele.core.ai.audio

import kotlin.math.sqrt

/**
 * 录音质量体检。
 *
 * **为什么必须有这个**：SenseVoice 这类模型在「几乎没有人声」的输入上会
 * **产生幻觉** —— 吐出一段流畅但完全无关的文字，而不是报错或返回空。
 * 用户看到这种结果只会觉得「识别太差了」，但真正的问题是麦克风根本没收到声音。
 *
 * 所以转写前先体检：
 *  - 判断这段音频到底有没有人声（没人声就别浪费算力、更别输出幻觉）
 *  - 有声音时给出可读的结论，把「识别不准」归因清楚
 *
 * 全部基于能量统计，不需要额外模型。
 */
object AudioQuality {

    enum class Verdict {
        /** 人声充足，识别结果可信 */
        GOOD,

        /** 有人声但偏弱（坐得远 / 环境吵），识别率会下降 */
        FAIR,

        /** 人声稀少，结果很可能不可靠 */
        POOR,

        /** 基本没有人声：不要送模型，会出幻觉 */
        NO_SPEECH,
    }

    data class Report(
        val durationSeconds: Double,
        /** 有声音的时间占比 0~1 */
        val speechRatio: Float,
        /** 整段的平均 RMS（0~1） */
        val averageRms: Float,
        /** 最响的 1% 帧的平均 RMS，代表人声峰值 */
        val peakRms: Float,
        val verdict: Verdict,
        val advice: String,
    ) {
        val hasUsableSpeech: Boolean get() = verdict != Verdict.NO_SPEECH
    }

    private const val FRAME_MS = 20

    /**
     * @param samples 16kHz 单声道浮点 PCM
     * @param speechThresholdRatio 相对峰值的判定阈值：帧能量低于
     *   `peak * ratio` 视为静音
     */
    fun inspect(
        samples: FloatArray,
        sampleRate: Int = 16_000,
        speechThresholdRatio: Float = 0.06f,
    ): Report {
        if (samples.isEmpty()) {
            return Report(0.0, 0f, 0f, 0f, Verdict.NO_SPEECH, "录音是空的")
        }

        val window = (sampleRate * FRAME_MS / 1000).coerceAtLeast(1)
        val frameCount = (samples.size + window - 1) / window
        val rms = FloatArray(frameCount)
        for (f in 0 until frameCount) {
            val from = f * window
            val to = minOf(from + window, samples.size)
            var sum = 0.0
            for (i in from until to) {
                val v = samples[i].toDouble()
                sum += v * v
            }
            rms[f] = sqrt(sum / (to - from)).toFloat()
        }

        val average = rms.average().toFloat()
        val peak = percentile(rms, 0.99f)

        // 相对峰值定阈值：不同手机麦克风增益差很多，用绝对值会误判
        val threshold = maxOf(peak * speechThresholdRatio, ABSOLUTE_FLOOR)
        val speechFrames = rms.count { it > threshold }
        val speechRatio = speechFrames.toFloat() / frameCount
        val durationSeconds = samples.size.toDouble() / sampleRate

        val verdict = when {
            peak < ABSOLUTE_FLOOR -> Verdict.NO_SPEECH
            speechRatio < 0.05f -> Verdict.NO_SPEECH
            speechRatio < 0.20f -> Verdict.POOR
            speechRatio < 0.45f -> Verdict.FAIR
            else -> Verdict.GOOD
        }

        return Report(
            durationSeconds = durationSeconds,
            speechRatio = speechRatio,
            averageRms = average,
            peakRms = peak,
            verdict = verdict,
            advice = adviceFor(verdict, peak, speechRatio),
        )
    }

    private fun adviceFor(verdict: Verdict, peak: Float, speechRatio: Float): String = when (verdict) {
        Verdict.GOOD -> "人声充足，识别结果可信"

        Verdict.FAIR ->
            "人声占比 ${(speechRatio * 100).toInt()}%，识别率会受一些影响。" +
                "如果上次坐在后排，下次可以往前坐几排"

        Verdict.POOR ->
            "只有 ${(speechRatio * 100).toInt()}% 的时间检测到人声，识别结果可能不可靠。" +
                "建议确认手机有没有被挡住，或者离讲台更近一些"

        Verdict.NO_SPEECH ->
            "这段录音里几乎没有检测到人声，所以**没有做识别** —— " +
                "强行识别会得到一段与课堂无关的幻觉文字。" +
                "请检查麦克风是否被遮挡或权限是否正常"
    }

    /** 取第 p 分位数（p 传 0.99 即最响的 1% 的门槛）。 */
    private fun percentile(values: FloatArray, p: Float): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sortedArray()
        val index = ((sorted.size - 1) * p).toInt().coerceIn(0, sorted.size - 1)
        return sorted[index]
    }

    /** 绝对下限：低于这个能量认为整段就是静音，与相对阈值无关。 */
    private const val ABSOLUTE_FLOOR = 0.0015f
}
