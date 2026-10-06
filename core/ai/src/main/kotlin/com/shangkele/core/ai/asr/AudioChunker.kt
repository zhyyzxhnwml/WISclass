package com.shangkele.core.ai.asr

import kotlin.math.sqrt

/**
 * 长音频切段。
 *
 * **为什么必须切**：SenseVoice 的 CTC 输出是 `[T', 25055]` 的 float32。
 * 一节课 90 分钟 → 约 90000 个 LFR 帧 → `90000 × 25055 × 4B ≈ 9GB`，
 * 不分段直接 OOM。切成 28 秒一段后，单段约 50MB，可以放心推理。
 *
 * **怎么切**：不引入 VAD 模型，直接用短时能量找**最安静的位置**切。
 *  - 在目标长度附近的搜索窗里挑能量最低的 20ms 窗口
 *  - 这样切口大概率落在句子之间或换气的停顿上
 *  - 无重叠、无重复，不用做前后文去重的模糊匹配
 *
 * 代价是偶尔会在词中间切开。用「找最低能量点」已经能规避绝大多数情况，
 * 等 W5 接入 VAD 后再换成真正的语音端点切分。
 */
class AudioChunker(
    private val targetSeconds: Double = 28.0,
    /** 允许在目标长度前后多少秒内寻找切口 */
    private val searchSeconds: Double = 5.0,
    private val energyWindowMs: Int = 20,
) {

    data class Segment(val startSample: Int, val endSampleExclusive: Int) {
        val length: Int get() = endSampleExclusive - startSample
        fun durationSeconds(sampleRate: Int): Double = length.toDouble() / sampleRate
        override fun toString(): String = "[$startSample, $endSampleExclusive)"
    }

    fun plan(samples: FloatArray, sampleRate: Int = 16_000): List<Segment> {
        val total = samples.size
        if (total == 0) return emptyList()

        val target = (targetSeconds * sampleRate).toInt().coerceAtLeast(1)
        val search = (searchSeconds * sampleRate).toInt()
        val maxSamples = target + search

        if (total <= maxSamples) return listOf(Segment(0, total))

        val window = (sampleRate * energyWindowMs / 1000).coerceAtLeast(1)
        val energy = energyProfile(samples, window)
        val result = mutableListOf<Segment>()
        var cursor = 0

        while (total - cursor > maxSamples) {
            val loSample = (cursor + target - search).coerceAtLeast(cursor + 1)
            val hiSample = (cursor + target + search).coerceAtMost(total)
            val cut = quietestCut(energy, window, loSample, hiSample, cursor, total)
            result += Segment(cursor, cut)
            cursor = cut
        }
        result += Segment(cursor, total)
        return result
    }

    /** 每个窗口的 RMS。 */
    internal fun energyProfile(samples: FloatArray, window: Int): FloatArray {
        val count = (samples.size + window - 1) / window
        val out = FloatArray(count)
        for (w in 0 until count) {
            val from = w * window
            val to = minOf(from + window, samples.size)
            var sum = 0.0
            for (i in from until to) {
                val v = samples[i].toDouble()
                sum += v * v
            }
            out[w] = sqrt(sum / (to - from)).toFloat()
        }
        return out
    }

    /**
     * 在 `[loSample, hiSample)` 范围内找能量最低窗口的中心作为切口。
     *
     * 找不到（例如全是静音、或范围太窄）就退回目标长度 —— 宁可切在词中间，
     * 也不能切出空段或者死循环。
     */
    private fun quietestCut(
        energy: FloatArray,
        window: Int,
        loSample: Int,
        hiSample: Int,
        cursor: Int,
        total: Int,
    ): Int {
        val fallback = (cursor + (targetSeconds * 16_000).toInt()).coerceIn(cursor + 1, total)

        val loWindow = loSample / window
        val hiWindow = (hiSample / window).coerceAtMost(energy.size - 1)
        if (loWindow > hiWindow) return fallback

        var bestWindow = -1
        var bestValue = Float.MAX_VALUE
        for (w in loWindow..hiWindow) {
            if (energy[w] < bestValue) {
                bestValue = energy[w]
                bestWindow = w
            }
        }
        if (bestWindow < 0) return fallback

        // 用窗口右边界作为切口，保证不丢采样点
        val cut = ((bestWindow + 1) * window).coerceIn(cursor + 1, total)
        return cut
    }

    companion object {
        /** 90 分钟 16kHz 单声道：应切成约 (90*60)/28 ≈ 193 段。 */
        fun estimateSegmentCount(seconds: Double, targetSeconds: Double = 28.0): Int =
            if (seconds <= 0) 0 else kotlin.math.ceil(seconds / targetSeconds).toInt()
    }
}
