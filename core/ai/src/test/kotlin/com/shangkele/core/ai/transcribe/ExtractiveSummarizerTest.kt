package com.shangkele.core.ai.transcribe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 机械抽取摘要测试。
 *
 * 重点验证的是「**要点必须按上课顺序返回**」——
 * 按分值排序输出的话，用户读到的是逻辑断裂的句子，
 * 而摘要的价值恰恰在于还原那节课的脉络。
 */
class ExtractiveSummarizerTest {

    private fun sentences(vararg texts: String): List<ExtractiveSummarizer.Sentence> =
        texts.mapIndexed { index, text ->
            ExtractiveSummarizer.Sentence(
                text = text,
                startMs = index * 10_000L,
                endMs = (index + 1) * 10_000L,
            )
        }

    @Test
    fun `空输入不崩溃`() {
        val result = ExtractiveSummarizer.summarize(emptyList(), 0L)
        assertTrue(result.keyPoints.isEmpty())
        assertFalse(result.hasContent)
        assertTrue(result.overview.isNotBlank())
    }

    @Test
    fun `强调句会被优先选为要点`() {
        val result = ExtractiveSummarizer.summarize(
            sentences(
                "呃这个我们上节课说过了大家还记得吧",
                "好我们接着往下看这个例子比较长先放一放",
                "这里要特别注意秩等于列空间的维数这个是必考的",
                "嗯对然后我们看下一个知识点",
                "行吧今天先到这",
            ),
            durationMs = 50_000L,
        )
        assertTrue(
            "带「必考/注意」的句子必须在要点里，实际=${result.keyPoints}",
            result.keyPoints.any { it.contains("必考") || it.contains("注意") },
        )
        assertEquals(1, result.emphasisHits)
    }

    @Test
    fun `要点按上课顺序返回而不是按分值`() {
        val result = ExtractiveSummarizer.summarize(
            sentences(
                "这一节的重点是极限的定义",
                "随便说点无关的过渡话",
                "还有一个重点要注意连续与可导的关系",
                "又是一段无关内容",
                "最后重点总结一下今天的三个结论",
            ),
            durationMs = 100_000L,
        )
        val positions = result.keyPoints.map { point ->
            listOf(
                "这一节的重点是极限的定义",
                "随便说点无关的过渡话",
                "还有一个重点要注意连续与可导的关系",
                "又是一段无关内容",
                "最后重点总结一下今天的三个结论",
            ).indexOf(point)
        }
        assertEquals("顺序必须单调递增", positions.sorted(), positions)
    }

    @Test
    fun `要点数量有上下限`() {
        val many = (1..60).map { "这是第${it}句内容，讲的是同一个知识点的不同侧面。" }
        val result = ExtractiveSummarizer.summarize(sentences(*many.toTypedArray()), 600_000L)
        assertTrue("要点不能超过 8 条，实际=${result.keyPoints.size}", result.keyPoints.size <= 8)
        assertTrue("要点至少 3 条，实际=${result.keyPoints.size}", result.keyPoints.size >= 3)
    }

    @Test
    fun `句子少于三条时全部保留`() {
        val result = ExtractiveSummarizer.summarize(
            sentences("第一句内容还算完整。", "第二句也还行。"),
            20_000L,
        )
        assertEquals(2, result.keyPoints.size)
    }

    @Test
    fun `统计信息正确`() {
        // 两句分别是 8 字与 10 字，合计 18 字
        val result = ExtractiveSummarizer.summarize(
            sentences("第一句话十二个字", "第二句话也是十二个字"),
            durationMs = 60_000L,
        )
        assertEquals(2, result.stats.sentenceCount)
        assertEquals(18, result.stats.charCount)
        assertEquals(60_000L, result.stats.durationMs)
        // 18 字 / 1 分钟 = 18 字每分钟
        assertEquals(18, result.stats.charsPerMinute)
    }

    @Test
    fun `语速过快会给出提示`() {
        val longText = "这是一句很长很长的内容用来把语速拉高".repeat(3)
        val result = ExtractiveSummarizer.summarize(sentences(longText), durationMs = 6_000L)
        assertTrue(
            "语速异常时 overview 应提示，实际=${result.overview}",
            result.overview.contains("语速偏快"),
        )
    }

    @Test
    fun `没有强调词时如实说明`() {
        val result = ExtractiveSummarizer.summarize(
            sentences("第一句普通的内容。", "第二句普通的内容。", "第三句普通的内容。"),
            30_000L,
        )
        assertEquals(0, result.emphasisHits)
        assertTrue(result.overview.contains("没有听到明显的强调"))
    }

    @Test
    fun `术语抽取需要重复出现`() {
        // 出现 4 次 → 应被抽出；只出现 1 次的不抽
        val text = "线性代数的矩阵很重要。矩阵的秩是关键。矩阵可以做初等变换。矩阵的乘法要熟练。今天天气不错。"
        val terms = ExtractiveSummarizer.extractTerms(text, limit = 5, minCount = 3)
        assertTrue("「矩阵」应被抽出，实际=$terms", terms.any { it.contains("矩阵") })
    }

    @Test
    fun `术语不会同时给出互为子串的重复项`() {
        val text = "图书馆很大。图书馆很安静。图书馆有三层。图书馆在城东。"
        val terms = ExtractiveSummarizer.extractTerms(text, limit = 5, minCount = 3)
        val hasLibrary = terms.any { it == "图书馆" }
        if (hasLibrary) {
            assertFalse("不该再单独给出「图书」这种子串", terms.any { it == "图书" })
        }
    }

    @Test
    fun `短语料抽不出术语时不报错`() {
        val terms = ExtractiveSummarizer.extractTerms("只有一句话。", limit = 5, minCount = 3)
        assertTrue(terms.isEmpty())
    }

    @Test
    fun `过短的句子不会被选为要点`() {
        val result = ExtractiveSummarizer.summarize(
            sentences("对", "嗯", "是", "好", "这个内容是本节课的核心结论请务必掌握"),
            50_000L,
        )
        assertTrue(
            "「对」「嗯」这类不该进要点，实际=${result.keyPoints}",
            result.keyPoints.none { it.length < 5 },
        )
    }
}
