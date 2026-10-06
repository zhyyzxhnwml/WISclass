package com.shangkele.core.ai.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 切段逻辑测试。
 *
 * 这里最怕的两类错：**丢采样点**（切完接起来少了一截，转写会莫名缺内容）
 * 和**死循环**（切口没前进，直接把 App 卡死）。所以重点验证覆盖完整性。
 */
class AudioChunkerTest {

    private val chunker = AudioChunker()

    /** 生成 `seconds` 秒音频：`quietAt` 指定的秒数附近人为压低音量。 */
    private fun signal(seconds: Double, quietAt: List<Double> = emptyList()): FloatArray {
        val n = (seconds * 16_000).toInt()
        val out = FloatArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / 16_000
            var amp = 0.3
            for (q in quietAt) {
                if (kotlin.math.abs(t - q) < 0.5) amp = 0.0001
            }
            out[i] = (amp * kotlin.math.sin(2 * Math.PI * 440 * t)).toFloat()
        }
        return out
    }

    @Test
    fun `空音频返回空`() {
        assertTrue(chunker.plan(FloatArray(0)).isEmpty())
    }

    @Test
    fun `短音频不切分`() {
        val short = signal(10.0)
        val segments = chunker.plan(short)
        assertEquals(1, segments.size)
        assertEquals(0, segments[0].startSample)
        assertEquals(short.size, segments[0].endSampleExclusive)
    }

    @Test
    fun `恰好一个目标长度的音频不切分`() {
        val s = signal(28.0)
        assertEquals(1, chunker.plan(s).size)
    }

    @Test
    fun `长音频会被切段且段数合理`() {
        val s = signal(120.0)
        val segments = chunker.plan(s)
        // 28 秒一段 + 5 秒搜索窗 → 120 秒约 4~5 段
        assertTrue("段数 ${segments.size} 不合理", segments.size in 3..6)
    }

    @Test
    fun `切段必须完整覆盖且不重叠不遗漏`() {
        val s = signal(300.0)
        val segments = chunker.plan(s)

        assertEquals("必须从 0 开始", 0, segments.first().startSample)
        assertEquals("必须到末尾结束", s.size, segments.last().endSampleExclusive)

        for (i in 1 until segments.size) {
            assertEquals(
                "第 ${i - 1} 段结尾必须紧接第 $i 段开头（否则会丢采样点）",
                segments[i - 1].endSampleExclusive,
                segments[i].startSample,
            )
        }
        for (seg in segments) {
            assertTrue("出现空段 $seg", seg.length > 0)
        }

        val totalCovered = segments.sumOf { it.length }
        assertEquals("总长度必须等于原始长度", s.size, totalCovered)
    }

    @Test
    fun `优先在安静处切开`() {
        // 在 26 秒处放一段静音，正好落在第 1 段的搜索窗（23~33 秒）内
        val s = signal(70.0, quietAt = listOf(26.0))
        val segments = chunker.plan(s)

        assertTrue(segments.size >= 2)
        val firstCutSeconds = segments[0].endSampleExclusive.toDouble() / 16_000
        assertEquals("应切在静音处附近", 26.0, firstCutSeconds, 1.0)
    }

    @Test
    fun `全静音也不死循环且正常切分`() {
        val silence = FloatArray(16_000 * 200)
        val segments = chunker.plan(silence)
        assertEquals("必须覆盖全长度", silence.size, segments.sumOf { it.length })
        assertTrue(segments.size >= 5)
        for (seg in segments) assertTrue(seg.length > 0)
    }

    @Test
    fun `能量剖面计算正确`() {
        // 恒定幅度 0.5 的正弦，RMS 应为 0.5/sqrt(2) ≈ 0.3536
        val n = 16_000
        val s = FloatArray(n) { (0.5 * kotlin.math.sin(2 * Math.PI * 100 * it / 16_000.0)).toFloat() }
        val energy = chunker.energyProfile(s, 320)
        assertEquals(50, energy.size)
        assertEquals(0.3536f, energy[5], 0.01f)

        val zeros = chunker.energyProfile(FloatArray(1000), 100)
        assertEquals(10, zeros.size)
        assertTrue(zeros.all { it == 0f })
    }

    @Test
    fun `段数估算`() {
        assertEquals(0, AudioChunker.estimateSegmentCount(0.0))
        assertEquals(1, AudioChunker.estimateSegmentCount(20.0))
        // 90 分钟一节课
        assertEquals(193, AudioChunker.estimateSegmentCount(90 * 60.0))
    }

    @Test
    fun `单段时长不会远超目标长度`() {
        val s = signal(600.0)
        for (seg in chunker.plan(s)) {
            val seconds = seg.durationSeconds(16_000)
            assertTrue("段长 ${"%.1f".format(seconds)}s 超过上限", seconds <= 34.0)
        }
    }
}
