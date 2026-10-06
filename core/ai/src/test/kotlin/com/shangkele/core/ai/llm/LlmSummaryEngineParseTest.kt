package com.shangkele.core.ai.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LLM 输出解析测试。
 *
 * 这组测试针对的是**真实会发生的脏输出**：模型即使被要求「只输出 JSON」，
 * 也经常套一层 ```json 代码块，或者前面加一句「好的，以下是结果：」。
 * 解析器脆弱的话，用户看到的是「生成失败」，而其实内容完好。
 *
 * 这些函数不联网，可以直接测。
 */
class LlmSummaryEngineParseTest {

    private val engine = LlmSummaryEngine(OpenAiCompatibleClient())

    @Test
    fun `裸 JSON 能解析`() {
        val output = engine.parse(
            """{"overview":"讲了矩阵的秩","keyPoints":["秩等于列空间维数"],"terms":["秩"],"confusions":[],"todos":[],"quiz":[]}""",
        )
        assertEquals("讲了矩阵的秩", output.overview)
        assertEquals(listOf("秩等于列空间维数"), output.keyPoints)
        assertEquals(listOf("秩"), output.terms)
        assertTrue(output.confusions.isEmpty())
    }

    @Test
    fun `被 markdown 代码块包住的 JSON 能解析`() {
        // 这是最常见的一种：模型自作主张加了 ```json
        val raw = """
            好的，以下是整理结果：

            ```json
            {"overview":"讲了极限","keyPoints":["定义"],"terms":[],"confusions":[],"todos":[],"quiz":[]}
            ```

            希望对你有帮助。
        """.trimIndent()
        val output = engine.parse(raw)
        assertEquals("讲了极限", output.overview)
        assertEquals(listOf("定义"), output.keyPoints)
    }

    @Test
    fun `前后有废话也能解析`() {
        val raw = "根据你的要求，我整理如下：{" +
            "\"overview\":\"o\",\"keyPoints\":[],\"terms\":[],\"confusions\":[],\"todos\":[],\"quiz\":[]} 以上。"
        assertEquals("o", engine.parse(raw).overview)
    }

    @Test
    fun `缺少字段时降级为空而不是崩溃`() {
        val output = engine.parse("""{"overview":"只有概述"}""")
        assertEquals("只有概述", output.overview)
        assertTrue(output.keyPoints.isEmpty())
        assertTrue(output.quiz.isEmpty())
    }

    @Test
    fun `数组里混入空串或空白会被过滤`() {
        val output = engine.parse(
            """{"overview":"o","keyPoints":["要点一","","   ","要点二"],"terms":[],"confusions":[],"todos":[],"quiz":[]}""",
        )
        assertEquals(listOf("要点一", "要点二"), output.keyPoints)
    }

    @Test
    fun `完全没有 JSON 时抛异常而不是静默给空结果`() {
        // 静默返回空摘要会让用户以为「这节课没内容」，比直接报错更糟
        val failure = runCatching { engine.parse("抱歉，我无法处理这个请求。") }
        assertTrue("必须抛异常", failure.isFailure)
        assertTrue(
            "错误信息要带上原文片段便于排查",
            failure.exceptionOrNull()?.message?.contains("抱歉") == true,
        )
    }

    @Test
    fun `只有左括号没有右括号时返回 null`() {
        assertNull(engine.extractJsonObject("{\"overview\":\"o\""))
    }

    @Test
    fun `嵌套对象里的花括号不会截断`() {
        // 按第一个 { 到最后一个 } 截取，而不是简单匹配到第一个 }
        val raw = """前言 {"overview":"o","keyPoints":["含 { 括号的要点"],"terms":[],"confusions":[],"todos":[],"quiz":[]} 后记"""
        val output = engine.parse(raw)
        assertEquals(listOf("含 { 括号的要点"), output.keyPoints)
    }

    @Test
    fun `短文本不切块`() {
        val short = "这是一段很短的转写。"
        val chunks = engine.chunkBySentence(short, maxChars = 6000)
        assertEquals(1, chunks.size)
        assertEquals(short, chunks[0])
    }

    @Test
    fun `长文本按句子边界切块且不丢内容`() {
        val sentence = "这是一句大约二十个字用来测试切块逻辑的句子。"
        val long = sentence.repeat(500)  // 约 11000 字，应切成 2~3 块
        val chunks = engine.chunkBySentence(long, maxChars = 6000)

        assertTrue("应该切出多块，实际=${chunks.size}", chunks.size >= 2)
        for (chunk in chunks) {
            assertTrue("单块不能超过上限太多，实际=${chunk.length}", chunk.length <= 6000 + sentence.length)
        }
        assertEquals("切块不能丢内容", long, chunks.joinToString(""))
    }

    @Test
    fun `切块不会把一句话劈成两半`() {
        val text = buildString {
            repeat(600) { append("第${it}句话内容到此结束。") }
        }
        val chunks = engine.chunkBySentence(text, maxChars = 1000)
        // 每块都应以句末标点结尾，而不是切在句子中间
        for (chunk in chunks.dropLast(1)) {
            assertTrue(
                "块应以句号结尾，实际结尾=${chunk.takeLast(8)}",
                chunk.trimEnd().endsWith("。"),
            )
        }
    }
}
