package com.shangkele.core.ai.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CTC 贪心解码测试。
 *
 * 词表刻意做成小数组：`<unk>` 在第 0 位（和真词表一致），
 * 这样能守住「blank 不是词表第 0 项」这条容易搞错的约定。
 */
class CtcDecoderTest {

    // id:  0      1     2     3     4          5            6
    //      <unk>  a     b     c     ▁hello     <|zh|>       <|withitn|>
    private val vocab = AsrVocabulary.of(
        listOf("<unk>", "a", "b", "c", "\u2581hello", "<|zh|>", "<|withitn|>"),
    )
    private val decoder = CtcDecoder(vocab)

    /** 用 id 序列造出「每帧 argmax 就是该 id」的 logits。 */
    private fun logitsOf(ids: List<Int>): Array<FloatArray> =
        Array(ids.size) { f ->
            FloatArray(vocab.size) { i -> if (i == ids[f]) 1f else 0f }
        }

    @Test
    fun `连续重复的帧只出一个字`() {
        assertEquals("a", decoder.decode(logitsOf(listOf(1, 1, 1, 1))))
        assertEquals("abc", decoder.decode(logitsOf(listOf(1, 1, 2, 2, 3))))
    }

    @Test
    fun `blank 不出字`() {
        assertEquals("abc", decoder.decode(logitsOf(listOf(0, 1, 0, 2, 0, 3, 0))))
    }

    @Test
    fun `中间隔着 blank 的相同字要输出两次`() {
        // 这正是「先去重再删 blank」的原因：
        // 若顺序反了，[a, blank, a] 会被误判成重复而丢字
        assertEquals("aa", decoder.decode(logitsOf(listOf(1, 0, 1))))
        assertEquals("aba", decoder.decode(logitsOf(listOf(1, 2, 1))))
    }

    @Test
    fun `词表第 0 项是 unk 而不是 blank`() {
        assertEquals("<unk>", vocab[0])
        assertFalse("blank 不应被当作普通词输出", decoder.decode(logitsOf(listOf(1, 1))).contains("<unk>"))
    }

    @Test
    fun `控制标记会被剥掉`() {
        val text = decoder.decode(logitsOf(listOf(5, 6, 1, 2)))
        assertEquals("ab", text)
    }

    @Test
    fun `句首标记转成空格且不额外插入空格`() {
        // SentencePiece 里 ▁ 表示「词的开始」，没有 ▁ 的 token 是同一个词的续写。
        // token 4 = "▁hello"、token 1 = "a"（无 ▁），所以这里是同一个词 → "helloa"。
        // 解码只做 ▁→空格 和首尾去空白，绝不能自作主张分词（会切坏英文单词）
        assertEquals("helloa", decoder.decode(logitsOf(listOf(4, 1))))
        assertEquals("hello", decoder.decode(logitsOf(listOf(4))))
    }

    @Test
    fun `空白输入返回空串`() {
        assertEquals("", decoder.decode(emptyArray()))
        assertEquals("", decoder.decode(logitsOf(listOf(0, 0, 0))))
    }

    @Test
    fun `frameCount 之外的帧被忽略`() {
        val logits = logitsOf(listOf(1, 2, 3))
        // 模型实际只前 2 帧有效
        assertEquals("ab", decoder.decode(logits, frameCount = 2))
        assertEquals("abc", decoder.decode(logits, frameCount = 3))
        // 越界的 frameCount 必须被夹住，不能抛异常
        assertEquals("abc", decoder.decode(logits, frameCount = 99))
        assertEquals("", decoder.decode(logits, frameCount = 0))
    }

    @Test
    fun `扁平缓冲解码与二维结果一致`() {
        val ids = listOf(1, 1, 0, 2, 3, 3, 0)
        val matrix = logitsOf(ids)
        val flat = FloatArray(ids.size * vocab.size)
        for (f in matrix.indices) {
            System.arraycopy(matrix[f], 0, flat, f * vocab.size, vocab.size)
        }

        val expected = decoder.decode(matrix)
        assertEquals(expected, decoder.decode(flat, frames = ids.size))
        assertEquals(expected, decoder.decode(flat, frames = ids.size, frameCount = ids.size))
    }

    @Test
    fun `控制标记识别正确`() {
        assertTrue(vocab.isControlToken(5))
        assertTrue(vocab.isControlToken(6))
        assertFalse(vocab.isControlToken(1))
        assertFalse(vocab.isControlToken(0))
    }

    @Test
    fun `词表长度不符时必须报错而不是静默截断`() {
        val failure = runCatching { AsrVocabulary.parse("""["a","b"]""", expectedSize = 25055) }
        assertTrue("长度不符必须抛异常", failure.isFailure)
    }

    @Test
    fun `tokens_json 能正常解析`() {
        val vocab2 = AsrVocabulary.parse("""["<unk>","<s>","</s>","the"]""", expectedSize = 4)
        assertEquals(4, vocab2.size)
        assertEquals("<unk>", vocab2[0])
        assertEquals("the", vocab2[3])
    }

    @Test
    fun `特殊标记与句首标记的归一化`() {
        assertEquals(
            "开放时间早上9点",
            SenseVoiceSpec.normalizeText("<|zh|><|NEUTRAL|>\u2581开放时间早上9点"),
        )
        assertEquals("hello world", SenseVoiceSpec.normalizeText("\u2581hello\u2581world"))
        assertEquals("", SenseVoiceSpec.normalizeText("<|zh|><|NEUTRAL|>"))
    }
}
