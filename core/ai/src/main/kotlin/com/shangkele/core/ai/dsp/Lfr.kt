package com.shangkele.core.ai.dsp

import kotlin.math.ceil

/**
 * LFR（Low Frame Rate）拼帧。
 *
 * SenseVoice 的 `frontend_conf` 是 `lfr_m=7, lfr_n=6`：把连续 7 帧 80 维拼成 1 帧 560 维，
 * 每 6 帧输出一次。模型输入就是这个 560 维。
 *
 * **尾部不足 7 帧时用最后一帧重复补齐**，这是 funasr 的原逻辑，
 * 改成补零会让末尾几帧特征突变，导致最后一句话识别错误。
 */
class Lfr(
    private val m: Int = 7,
    private val n: Int = 6,
) {
    init {
        require(m > 0 && n > 0) { "lfr_m / lfr_n 必须为正" }
    }

    fun outputFrames(inputFrames: Int): Int =
        if (inputFrames <= 0) 0 else ceil(inputFrames.toDouble() / n).toInt()

    /**
     * @param frames `T x D`
     * @return `T' x (D*m)`，其中 `T' = ceil(T / n)`
     */
    fun apply(frames: Array<FloatArray>): Array<FloatArray> {
        if (frames.isEmpty()) return emptyArray()
        val srcDim = frames[0].size
        val t = frames.size
        val tLfr = outputFrames(t)
        val leftPad = (m - 1) / 2

        // 左补 leftPad 帧（用第 0 帧），与 funasr 一致
        val padded = Array(t + leftPad) { idx ->
            if (idx < leftPad) frames[0] else frames[idx - leftPad]
        }
        val paddedLen = padded.size
        val last = padded[paddedLen - 1]

        return Array(tLfr) { i ->
            val out = FloatArray(srcDim * m)
            val start = i * n
            var written = 0
            for (k in 0 until m) {
                val srcIdx = start + k
                val src = if (srcIdx < paddedLen) padded[srcIdx] else last
                System.arraycopy(src, 0, out, written, srcDim)
                written += srcDim
            }
            out
        }
    }
}
