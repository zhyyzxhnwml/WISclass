package com.shangkele.core.ai.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * 录音体检测试。
 *
 * 这组测试守的是一条很具体的失败模式：**在近乎无声的输入上跑 ASR，
 * 模型会输出一段流畅但完全无关的幻觉文字**。用户看到的是「识别太差了」，
 * 真因却是麦克风没收到声音。所以没人声时必须拦住，不能送进模型。
 */
class AudioQualityTest {

    private val rate = 16_000

    /** 按 duty 比例生成「有声音段 + 静音段」交替的音频。 */
    private fun mixed(seconds: Double, duty: Double, amplitude: Double = 0.5): FloatArray {
        val n = (seconds * rate).toInt()
        val out = FloatArray(n)
        val periodSamples = (rate * 0.5).toInt()  // 每 0.5 秒一个周期
        for (i in 0 until n) {
            val inVoice = (i % periodSamples) < (periodSamples * duty)
            out[i] = if (inVoice) {
                (amplitude * sin(2 * PI * 300 * i / rate)).toFloat()
            } else {
                0f
            }
        }
        return out
    }

    @Test
    fun `空音频判为无人声`() {
        val report = AudioQuality.inspect(FloatArray(0), rate)
        assertEquals(AudioQuality.Verdict.NO_SPEECH, report.verdict)
        assertFalse(report.hasUsableSpeech)
        assertEquals(0.0, report.durationSeconds, 1e-9)
    }

    @Test
    fun `全静音判为无人声且不允许送模型`() {
        val report = AudioQuality.inspect(FloatArray(rate * 30), rate)
        assertEquals(AudioQuality.Verdict.NO_SPEECH, report.verdict)
        assertFalse("没人声必须拦住，否则模型会出幻觉", report.hasUsableSpeech)
        assertTrue("提示里要说清是没收到人声", report.advice.contains("人声"))
    }

    @Test
    fun `持续人声判为良好`() {
        val report = AudioQuality.inspect(mixed(30.0, duty = 0.95), rate)
        assertEquals(AudioQuality.Verdict.GOOD, report.verdict)
        assertTrue(report.hasUsableSpeech)
        assertTrue("人声占比应接近 1", report.speechRatio > 0.8f)
    }

    @Test
    fun `断断续续的人声判为偏差并给出可执行的建议`() {
        val report = AudioQuality.inspect(mixed(30.0, duty = 0.4), rate)
        assertEquals(AudioQuality.Verdict.FAIR, report.verdict)
        // 建议不能只说「质量不好」，要给出用户能做的动作
        assertTrue("要给出可执行的建议，实际=${report.advice}", report.advice.contains("坐"))
        assertTrue("要说明人声占比", report.advice.contains("%"))
    }

    @Test
    fun `人声极稀少判为差`() {
        val report = AudioQuality.inspect(mixed(30.0, duty = 0.1), rate)
        assertEquals(AudioQuality.Verdict.POOR, report.verdict)
        assertTrue(report.hasUsableSpeech)
    }

    @Test
    fun `几乎全是静音但有一点点声音仍判为无人声`() {
        // 30 秒里只有 0.5 秒有声音：典型的是麦克风被挡住
        val report = AudioQuality.inspect(mixed(30.0, duty = 0.02), rate)
        assertEquals(AudioQuality.Verdict.NO_SPEECH, report.verdict)
        assertFalse(report.hasUsableSpeech)
    }

    @Test
    fun `低增益录音不会因为绝对值小就被判成静音`() {
        // 阈值是相对峰值的，不同手机麦克风增益差很多，用固定阈值会误杀
        val quiet = mixed(30.0, duty = 0.95, amplitude = 0.02)
        val report = AudioQuality.inspect(quiet, rate)
        assertTrue(
            "小音量但人声连续，应判为有人声（实际=${report.verdict}）",
            report.hasUsableSpeech,
        )
    }

    @Test
    fun `时长换算正确`() {
        val report = AudioQuality.inspect(mixed(45.0, duty = 0.9), rate)
        assertEquals(45.0, report.durationSeconds, 0.1)
    }

    @Test
    fun `报告的数值都在合理范围`() {
        val report = AudioQuality.inspect(mixed(20.0, duty = 0.7), rate)
        assertTrue(report.speechRatio in 0f..1f)
        assertTrue(report.averageRms >= 0f)
        assertTrue("峰值应不小于均值", report.peakRms >= report.averageRms)
        assertTrue(report.advice.isNotBlank())
    }
}
