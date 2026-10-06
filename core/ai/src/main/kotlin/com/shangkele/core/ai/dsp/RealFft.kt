package com.shangkele.core.ai.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 定长实数 FFT（基 2，迭代实现）。
 *
 * 刻意不复刻 kaldi `SplitRadixRealFft` 的输出打包格式 —— 那是 C++ 的实现细节。
 * 我们只要求**功率谱**一致，所以输出直接用常规的 `[0..n/2]` 频点排列。
 *
 * 只支持 2 的幂长度（本场景固定 512），因为长度固定，位反转表和旋转因子
 * 在构造时算一次即可，避免每帧重复计算三角函数。
 */
class RealFft(val size: Int) {

    private val cosTable = FloatArray(size / 2)
    private val sinTable = FloatArray(size / 2)
    private val bitReverse = IntArray(size)
    private val re = FloatArray(size)
    private val im = FloatArray(size)

    init {
        require(size > 0 && (size and (size - 1)) == 0) {
            "FFT 长度必须是 2 的幂，收到 $size"
        }
        for (i in 0 until size / 2) {
            val angle = -2.0 * PI * i / size
            cosTable[i] = cos(angle).toFloat()
            sinTable[i] = sin(angle).toFloat()
        }
        val bits = Integer.numberOfTrailingZeros(size)
        for (i in 0 until size) {
            bitReverse[i] = Integer.reverse(i) ushr (32 - bits)
        }
    }

    /**
     * 计算功率谱 `|X[k]|^2`，k = 0..size/2（共 size/2+1 个频点）。
     *
     * @param input 长度必须等于 [size]
     * @param out 长度必须 ≥ size/2+1
     */
    fun powerSpectrum(input: FloatArray, out: FloatArray) {
        require(input.size >= size) { "输入长度 ${input.size} < FFT 长度 $size" }
        val half = size / 2

        System.arraycopy(input, 0, re, 0, size)
        java.util.Arrays.fill(im, 0f)

        // 位反转重排
        for (i in 0 until size) {
            val j = bitReverse[i]
            if (j > i) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }

        // 蝶形运算
        var len = 2
        while (len <= size) {
            val halfLen = len / 2
            val step = size / len
            var i = 0
            while (i < size) {
                var j = i
                var k = 0
                while (j < i + halfLen) {
                    val l = j + halfLen
                    val wr = cosTable[k]
                    val wi = sinTable[k]
                    val tr = re[l] * wr - im[l] * wi
                    val ti = re[l] * wi + im[l] * wr
                    re[l] = re[j] - tr
                    im[l] = im[j] - ti
                    re[j] += tr
                    im[j] += ti
                    j++
                    k += step
                }
                i += len
            }
            len = len shl 1
        }

        for (k in 0..half) {
            out[k] = re[k] * re[k] + im[k] * im[k]
        }
    }

    /** 便于单测：返回 0..size/2 的实部/虚部。 */
    fun spectrum(input: FloatArray): Pair<FloatArray, FloatArray> {
        val power = FloatArray(size / 2 + 1)
        powerSpectrum(input, power)
        return re.copyOf(size / 2 + 1) to im.copyOf(size / 2 + 1)
    }
}
