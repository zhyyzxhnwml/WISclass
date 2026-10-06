package com.shangkele.core.ai.transcribe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SentenceSplitterTest {

    @Test
    fun `空文本返回空`() {
        assertTrue(SentenceSplitter.split("", 0, 1000).isEmpty())
        assertTrue(SentenceSplitter.split("   ", 0, 1000).isEmpty())
    }

    @Test
    fun `单句不切分且保留原时间范围`() {
        val result = SentenceSplitter.split("这是一整句话。", 1000, 5000)
        assertEquals(1, result.size)
        assertEquals(1000, result[0].startMs)
        assertEquals(5000, result[0].endMs)
        assertEquals("这是一整句话。", result[0].text)
    }

    @Test
    fun `按句末标点切分且标点归前一句`() {
        val result = SentenceSplitter.split("第一句。第二句！第三句？", 0, 3000)
        assertEquals(3, result.size)
        assertEquals("第一句。", result[0].text)
        assertEquals("第二句！", result[1].text)
        assertEquals("第三句？", result[2].text)
    }

    @Test
    fun `时间首尾必须严格覆盖原范围且单调递增`() {
        val result = SentenceSplitter.split(
            "这是比较长的一句话，用来验证时间分摊。第二句也不短，继续验证。第三句收尾。",
            10_000,
            40_000,
        )
        assertEquals(3, result.size)
        assertEquals("起点必须等于段起点", 10_000, result[0].startMs)
        assertEquals("终点必须等于段终点", 40_000, result.last().endMs)

        for (i in 1 until result.size) {
            assertEquals(
                "相邻句子必须首尾相接，不能有空洞或重叠",
                result[i - 1].endMs,
                result[i].startMs,
            )
            assertTrue("时间必须单调不减", result[i].endMs >= result[i].startMs)
        }
    }

    @Test
    fun `时间按字数占比分摊`() {
        // 两句等长 → 各占一半
        val result = SentenceSplitter.split("第一句啊。第二句啊。", 0, 1000)
        assertEquals(2, result.size)
        assertEquals(500, result[0].endMs)

        // 长句占比更大
        val uneven = SentenceSplitter.split("短。这是一句特别特别长的话用来验证分摊。", 0, 1000)
        assertEquals(2, uneven.size)
        assertTrue(
            "长句分到的时间应明显更多",
            (uneven[1].endMs - uneven[1].startMs) > (uneven[0].endMs - uneven[0].startMs),
        )
    }

    @Test
    fun `英文句号与问号也能切分`() {
        val result = SentenceSplitter.split("Hello world. How are you? I am fine!", 0, 3000)
        assertEquals(3, result.size)
        assertEquals("Hello world.", result[0].text)
    }

    @Test
    fun `换行作为强切分点`() {
        val result = SentenceSplitter.split("第一行内容\n第二行内容", 0, 2000)
        assertEquals(2, result.size)
        assertEquals("第一行内容", result[0].text)
        assertEquals("第二行内容", result[1].text)
    }

    @Test
    fun `末尾没有标点也会作为最后一句保留`() {
        val result = SentenceSplitter.split("有标点的句子。没有标点的结尾", 0, 2000)
        assertEquals(2, result.size)
        assertEquals("没有标点的结尾", result[1].text)
        assertEquals(2000, result[1].endMs)
    }

    @Test
    fun `时间为零或反向时不崩溃且保持 start 不晚于 end`() {
        // 零长度：不切分，整段作为一个零长片段
        val zero = SentenceSplitter.split("一句话。另一句。", 500, 500)
        assertEquals(1, zero.size)
        assertEquals(500, zero[0].startMs)
        assertEquals(500, zero[0].endMs)
        assertTrue(zero.all { it.startMs <= it.endMs })

        // 反向区间：自动归一化，绝不产出「负时长」片段
        val reversed = SentenceSplitter.split("第一句。第二句。", 5000, 1000)
        assertTrue("必须满足 start <= end", reversed.all { it.startMs <= it.endMs })
        assertEquals(1000, reversed.first().startMs)
        assertEquals(5000, reversed.last().endMs)
        assertEquals("归一化后仍应正常切句", 2, reversed.size)
    }

    @Test
    fun `连续标点不会切出空句`() {
        val result = SentenceSplitter.split("第一句。。。第二句！", 0, 1000)
        assertTrue("不能出现空文本片段", result.all { it.text.isNotBlank() })
    }
}
