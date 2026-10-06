package com.shangkele.core.ai.dsp

import kotlin.math.ln

/**
 * Kaldi 风格三角 mel 滤波器组。
 *
 * 两个必须照抄的细节（否则整体偏移，且不会报错）：
 *  1. **mel 刻度用 `1127 * ln(1 + f/700)`**，不是 HTK 的 `2595*log10(1+f/700)`。
 *     实测 kaldi `mel_scale(1000) = 999.9907`，与前者吻合。
 *  2. **不做归一化**。实测 kaldi 的滤波器组行和在 0.64~8.29 之间浮动，
 *     各 bin 宽度不同所以行和本来就不同 —— 归一化反而是错的。
 */
class MelFilterBank(
    val numBins: Int,
    val numFftBins: Int,
    /** 行优先，`weights[bin * numFftBins + k]` */
    private val weights: FloatArray,
) {
    /**
     * @param power 功率谱，长度 ≥ [numFftBins]
     * @param out 长度 ≥ [numBins]
     */
    fun compute(power: FloatArray, out: FloatArray) {
        for (bin in 0 until numBins) {
            val base = bin * numFftBins
            var sum = 0f
            for (k in 0 until numFftBins) {
                sum += weights[base + k] * power[k]
            }
            out[bin] = sum
        }
    }

    fun weightAt(bin: Int, k: Int): Float = weights[bin * numFftBins + k]

    companion object {
        fun melScale(freq: Double): Double = 1127.0 * ln(1.0 + freq / 700.0)

        fun inverseMelScale(mel: Double): Double = 700.0 * (Math.exp(mel / 1127.0) - 1.0)

        /**
         * 按 kaldi `MelBanks` 的构造逻辑生成。
         *
         * 滤波器中心在 mel 域等距排布，共 `numBins + 1` 段；
         * 每个 bin 是跨 (left, center, right) 的三角。
         */
        fun build(
            sampleRate: Int,
            fftSize: Int,
            numBins: Int,
            lowFreq: Double,
            highFreq: Double,
        ): MelFilterBank {
            val numFftBins = fftSize / 2 + 1
            val nyquist = 0.5 * sampleRate
            val low = if (lowFreq <= 0.0) 0.0 else lowFreq
            val high = if (highFreq <= 0.0 || highFreq > nyquist) nyquist else highFreq

            val fftBinWidth = sampleRate.toDouble() / fftSize
            val melLow = melScale(low)
            val melHigh = melScale(high)
            val melDelta = (melHigh - melLow) / (numBins + 1)

            val weights = FloatArray(numBins * numFftBins)
            for (bin in 0 until numBins) {
                val leftMel = melLow + bin * melDelta
                val centerMel = melLow + (bin + 1) * melDelta
                val rightMel = melLow + (bin + 2) * melDelta
                val base = bin * numFftBins
                for (k in 0 until numFftBins) {
                    val mel = melScale(fftBinWidth * k)
                    if (mel > leftMel && mel < rightMel) {
                        weights[base + k] = if (mel <= centerMel) {
                            ((mel - leftMel) / (centerMel - leftMel)).toFloat()
                        } else {
                            ((rightMel - mel) / (rightMel - centerMel)).toFloat()
                        }
                    }
                }
            }
            return MelFilterBank(numBins, numFftBins, weights)
        }
    }
}
