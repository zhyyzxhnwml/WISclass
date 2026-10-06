package com.shangkele.core.ai.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * LFR 拼帧与 CMVN 对齐基准，并验证整条前端流水线。
 */
class LfrCmvnGoldenTest {

    private val frontend = AsrFrontend()

    @Test
    fun `AM_MVN 能正确解析`() {
        val cmvn = Cmvn.parse(Golden.text("am.mvn"))

        assertEquals("维度必须是 80 mel * 7 = 560", 560, cmvn.dim)
        assertEquals(cmvn.dim, cmvn.scales.size)
        // 实测前三个 AddShift 值
        assertEquals(-8.311879f, cmvn.means[0], 1e-5f)
        assertEquals(-8.600912f, cmvn.means[1], 1e-5f)
        // Rescale 是 1/std，全部为正
        assertTrue("Rescale 应全为正", cmvn.scales.all { it > 0f })
        assertTrue("AddShift 应全为负", cmvn.means.all { it < 0f })
    }

    @Test
    fun `LFR 帧数计算正确`() {
        val lfr = Lfr(7, 6)
        assertEquals(0, lfr.outputFrames(0))
        assertEquals(1, lfr.outputFrames(1))
        assertEquals(1, lfr.outputFrames(6))
        assertEquals(2, lfr.outputFrames(7))
        // 50 帧 fbank → ceil(50/6) = 9
        assertEquals(9, lfr.outputFrames(50))
    }

    @Test
    fun `LFR 输出维度与拼帧顺序正确`() {
        // 每帧内容 = 帧号，这样能直接看出 7 帧是否按顺序拼进来
        val frames = Array(9) { f -> FloatArray(3) { f.toFloat() } }
        val out = Lfr(7, 6).apply(frames)

        assertEquals(2, out.size)
        assertEquals(21, out[0].size)

        // 左补 3 帧（都用第 0 帧）后序列为 [0,0,0 | 0,1,2,3,4,5,6,7,8]（每项展开成 3 个分量）
        // 第 0 组取前 7 项 → 0,0,0,0,1,2,3
        val expected0 = floatArrayOf(
            0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f,
            1f, 1f, 1f, 2f, 2f, 2f, 3f, 3f, 3f,
        )
        for (i in expected0.indices) {
            assertEquals("第 0 组第 $i 位", expected0[i], out[0][i], 1e-6f)
        }

        // 第 1 组从下标 6 开始取 7 项 → 帧 3,4,5,6,7,8,[越界用最后一帧 8]
        assertEquals(3f, out[1][0], 1e-6f)    // 第 1 项 = 帧 3
        assertEquals(7f, out[1][12], 1e-6f)   // 第 5 项 = 帧 7
        assertEquals(8f, out[1][15], 1e-6f)   // 第 6 项 = 帧 8
        assertEquals("越界应复制最后一帧", 8f, out[1][18], 1e-6f)
    }

    @Test
    fun `LFR 在帧数不足时用最后一帧补齐`() {
        val frames = Array(2) { f -> FloatArray(2) { f.toFloat() } }
        val out = Lfr(7, 6).apply(frames)

        assertEquals(1, out.size)
        assertEquals(14, out[0].size)

        // 左补 3 帧 → 序列 [0,0,0,0,1]，需要 7 项，后 2 项复制最后一帧（值 1）
        val flat = out[0]
        assertEquals(0f, flat[0], 1e-6f)
        assertEquals(0f, flat[6], 1e-6f)   // 第 4 项（值 0）
        assertEquals(1f, flat[8], 1e-6f)   // 第 5 项（值 1，真实最后一帧）
        assertEquals(1f, flat[10], 1e-6f)  // 第 6 项（复制最后一帧）
        assertEquals(1f, flat[12], 1e-6f)  // 第 7 项（复制最后一帧）
    }

    @Test
    fun `LFR 与 kaldi 逐帧对齐`() {
        val expected = Golden.matrix("golden_lfr_head.bin", 560)
        val fbank = FbankExtractor().compute(Golden.testSignal())
        val actual = Lfr(7, 6).apply(fbank)

        assertEquals("LFR 帧数不一致", 9, actual.size)
        assertEquals(560, actual[0].size)

        var maxDiff = 0f
        for (f in expected.indices) {
            for (d in 0 until 560) {
                maxDiff = maxOf(maxDiff, abs(expected[f][d] - actual[f][d]))
            }
        }
        assertTrue("LFR 最大偏差 $maxDiff 过大", maxDiff < 1e-3f)
    }

    @Test
    fun `CMVN 之后与 kaldi 逐帧对齐`() {
        val expected = Golden.matrix("golden_cmvn_head.bin", 560)
        val cmvn = Cmvn.parse(Golden.text("am.mvn"))
        val actual = frontend.extract(Golden.testSignal(), cmvn)

        assertEquals(9, actual.size)

        var maxDiff = 0f
        for (f in expected.indices) {
            for (d in 0 until 560) {
                maxDiff = maxOf(maxDiff, abs(expected[f][d] - actual[f][d]))
            }
        }
        assertTrue("CMVN 最大偏差 $maxDiff 过大", maxDiff < 1e-3f)
    }

    @Test
    fun `前端输出维度是模型期望的 560`() {
        assertEquals(560, frontend.featureDim)
        val cmvn = Cmvn.parse(Golden.text("am.mvn"))
        val feats = frontend.extract(Golden.testSignal(), cmvn)
        // 50 帧 fbank → ceil(50/6) = 9 帧 LFR
        assertEquals(9, feats.size)
        assertEquals(560, feats[0].size)
        assertEquals(9, frontend.numFramesFor(Golden.NUM_SAMPLES))
    }

    @Test
    fun `CMVN 维度不匹配时立刻报错而不是静默产生垃圾`() {
        val wrong = Cmvn(FloatArray(80), FloatArray(80))
        val failure = runCatching { frontend.extract(Golden.testSignal(), wrong) }
        assertTrue("维度不符必须抛异常", failure.isFailure)
    }

    @Test
    fun `空音频不崩溃`() {
        val cmvn = Cmvn.parse(Golden.text("am.mvn"))
        assertTrue(frontend.extract(FloatArray(0), cmvn).isEmpty())
        assertTrue(frontend.extract(FloatArray(100), cmvn).isEmpty())
    }
}
