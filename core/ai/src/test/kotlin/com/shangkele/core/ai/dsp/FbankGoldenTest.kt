package com.shangkele.core.ai.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * fbank 逐帧对齐基准。
 *
 * 这是本模块**最重要的一组测试**：fbank 是自己手写的（没有可用的 Android 库），
 * 算错不会抛异常，只会让识别结果变成乱码或悄悄变差。
 * 这里直接与 `kaldi_native_fbank`（sherpa-onnx 同源）的输出比对。
 */
class FbankGoldenTest {

    private val options = FbankOptions()

    @Test
    fun `帧数计算与 kaldi 一致`() {
        // 8320 采样点 → 1 + (8320-400)/160 = 50，尾部半帧丢弃
        assertEquals(50, options.numFrames(Golden.NUM_SAMPLES))
        // 不足一帧
        assertEquals(0, options.numFrames(399))
        assertEquals(1, options.numFrames(400))
        assertEquals(1, options.numFrames(559))
        assertEquals(2, options.numFrames(560))
    }

    @Test
    fun `padding 长度与 kaldi 一致`() {
        assertEquals(512, options.paddedLength)
        assertEquals(400, options.frameLength)
        assertEquals(160, options.frameShift)
        assertEquals(257, options.numFftBins)
    }

    @Test
    fun `窗函数与 kaldi 完全一致`() {
        val expected = Golden.floats("golden_window.bin")
        val actual = FbankExtractor(options).windowCoefficients()

        assertEquals(400, expected.size)
        assertEquals(expected.size, actual.size)
        // 对称 hamming：首尾都是 0.54-0.46 = 0.08
        assertEquals(0.08f, actual.first(), 1e-6f)
        assertEquals(0.08f, actual.last(), 1e-6f)

        var maxDiff = 0f
        for (i in expected.indices) maxDiff = maxOf(maxDiff, abs(expected[i] - actual[i]))
        assertTrue("窗函数最大偏差 $maxDiff 过大", maxDiff < 1e-6f)
    }

    @Test
    fun `mel 刻度公式与 kaldi 一致`() {
        // 实测值。注意 Kaldi 用 1127*ln(1+f/700)，不是 HTK 的 2595*log10
        val cases = listOf(
            20.0 to 31.7486,
            100.0 to 150.4899,
            440.0 to 549.6416,
            1000.0 to 999.9907,
            2000.0 to 1521.3674,
            4000.0 to 2146.0757,
            8000.0 to 2840.0378,
        )
        for ((freq, expected) in cases) {
            assertEquals("f=$freq", expected, MelFilterBank.melScale(freq), 1e-3)
        }
        assertEquals(0.0, MelFilterBank.melScale(0.0), 1e-9)
    }

    @Test
    fun `mel 滤波器组矩阵与 kaldi 一致`() {
        val expected = Golden.matrix("golden_mel_matrix.bin", COLS)
        val actual = FbankExtractor(options).melFilterBank()

        assertEquals(80, expected.size)
        assertEquals(80, actual.numBins)
        assertEquals(COLS, actual.numFftBins)

        var maxDiff = 0f
        var compareCount = 0
        for (bin in expected.indices) {
            for (k in 0 until COLS) {
                val diff = abs(expected[bin][k] - actual.weightAt(bin, k))
                if (diff > maxDiff) maxDiff = diff
                compareCount++
            }
        }
        assertEquals(80 * COLS, compareCount)
        // 容差 1e-4：Kotlin 用 double 算完再截断成 float，kaldi 全程 float32，
        // 权重最大 1.0 时 1e-5 量级的差异属于精度范畴
        assertTrue("mel 矩阵最大偏差 $maxDiff 过大", maxDiff < 1e-4f)

        // 守一条容易搞错的约定：kaldi 不做归一化，行和因此各不相同
        val rowSums = (0 until 80).map { bin -> (0 until COLS).sumOf { actual.weightAt(bin, it).toDouble() } }
        assertTrue("最高频 bin 的行和应明显大于最低频 bin（说明没做归一化）", rowSums[79] > rowSums[0] * 2)
    }

    @Test
    fun `FFT 功率谱与 kaldi 一致`() {
        val input = Golden.floats("golden_rfft_input.bin")
        val expected = Golden.floats("golden_rfft_power.bin")
        val actual = FloatArray(expected.size)

        assertEquals(512, input.size)
        assertEquals(257, expected.size)
        RealFft(512).powerSpectrum(input, actual)

        // 峰值功率约 128^2 ≈ 16384，用绝对容差 1.0 相当于相对 6e-5
        var maxDiff = 0f
        for (i in expected.indices) maxDiff = maxOf(maxDiff, abs(expected[i] - actual[i]))
        assertTrue("FFT 功率谱最大偏差 $maxDiff 过大", maxDiff < 1.0f)

        // 第 5 次谐波处应有峰值。幅度 0.5、长度 512 的实数正弦，
        // 其单边谱幅度 = A*N/2 = 128，功率 = 128^2 = 16384。
        assertEquals(16384.0, expected[5].toDouble(), 1.0)
        assertTrue("峰值附近功率应远大于底噪", actual[5] > actual[1] * 1e6f)
    }

    @Test
    fun `fbank 输出逐帧对齐 kaldi`() {
        val expected = Golden.matrix("golden_fbank.bin", 80)
        val actual = FbankExtractor(options).compute(Golden.testSignal())

        assertEquals("帧数不一致", expected.size, actual.size)
        assertEquals(50, actual.size)

        var maxDiff = 0f
        var worstFrame = -1
        for (f in expected.indices) {
            for (m in 0 until 80) {
                val diff = abs(expected[f][m] - actual[f][m])
                if (diff > maxDiff) {
                    maxDiff = diff
                    worstFrame = f
                }
            }
        }
        assertTrue("fbank 最大偏差 $maxDiff（第 $worstFrame 帧）过大", maxDiff < 1e-3f)
    }

    @Test
    fun `输入量纲放大 32768 倍后 CMVN 才落在正确工作点`() {
        // 这条守的是最容易踩的坑：funasr 的 WavFrontend 里有一行 waveform *= 1<<15。
        // 少了它，特征整体平移 -20.79（= -2*ln(32768)），CMVN 后均值会偏掉约 3.0。
        val means = Cmvn.parse(Golden.text("am.mvn")).means
        val scaled = FbankExtractor(options).compute(Golden.testSignal())
        val unscaled = FbankExtractor(options.copy(inputScale = 1.0)).compute(Golden.testSignal())

        val scaledMean = scaled.sumOf { row -> row.sumOf { it.toDouble() } } / (scaled.size * 80)
        val unscaledMean = unscaled.sumOf { row -> row.sumOf { it.toDouble() } } / (unscaled.size * 80)
        val meanOfMeans = means.sumOf { it.toDouble() } / means.size

        // 正确量纲下，特征均值应当落在 -meanOfMeans 附近，CMVN 之后才近似零均值
        assertEquals("特征均值应接近 CMVN 的负均值", -meanOfMeans, scaledMean, 3.0)
        assertEquals("两者应恰好差 2*ln(32768)", 2 * kotlin.math.ln(32768.0), scaledMean - unscaledMean, 1e-3)
    }

    private companion object {
        const val COLS = 257
    }
}
