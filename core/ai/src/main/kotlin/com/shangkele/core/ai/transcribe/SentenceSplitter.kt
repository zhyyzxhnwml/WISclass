package com.shangkele.core.ai.transcribe

/**
 * 把一段（约 28 秒）的转写文本再切成句子，并按字数占比分摊时间。
 *
 * **为什么值得单独做**：切段边界只有 28 秒的粒度，用户点一条转写记录
 * 会跳到一个很粗的位置，体验很差。切到句子级后时间点能精确到几秒内，
 * 「点这句话 → 听回老师当时怎么说的」才真的可用。
 *
 * 时间按**字符数占比**分摊，是估算而非精确值。等 W5 拿到 VAD 的
 * 逐句端点后可以换成真实时间。
 */
object SentenceSplitter {

    /** 中文句末标点 + 英文句末标点 */
    private val SENTENCE_END = charArrayOf('。', '！', '？', '；', '.', '!', '?', ';', '\n')

    data class Sentence(val text: String, val startMs: Long, val endMs: Long)

    fun split(text: String, startMs: Long, endMs: Long): List<Sentence> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()

        // 先把区间归一化：上游给反了（end < start）也要保证 start <= end 这条不变量，
        // 否则 UI 上会出现「负时长」的片段
        val from = minOf(startMs, endMs)
        val to = maxOf(startMs, endMs)
        if (to <= from) return listOf(Sentence(trimmed, from, from))

        val pieces = splitRaw(trimmed)
        if (pieces.size <= 1) return listOf(Sentence(trimmed, from, to))

        val totalChars = pieces.sumOf { it.length }.coerceAtLeast(1)
        val span = to - from
        var cursorMs = from
        val sentences = pieces.map { piece ->
            val share = span * piece.length / totalChars
            val sentenceFrom = cursorMs
            val sentenceTo = (cursorMs + share).coerceAtMost(to)
            cursorMs = sentenceTo
            Sentence(piece, sentenceFrom, sentenceTo)
        }
        // 整数除法会让最后一句的结尾差几十毫秒，直接对齐到段尾
        return sentences.dropLast(1) + sentences.last().copy(endMs = to)
    }

    /** 按句末标点切，标点归给前一句。 */
    internal fun splitRaw(text: String): List<String> {
        val result = mutableListOf<String>()
        val buffer = StringBuilder()
        for (ch in text) {
            if (ch == '\n') {
                if (buffer.isNotBlank()) result += buffer.toString().trim()
                buffer.setLength(0)
                continue
            }
            buffer.append(ch)
            if (ch in SENTENCE_END) {
                val piece = buffer.toString().trim()
                if (piece.isNotEmpty()) result += piece
                buffer.setLength(0)
            }
        }
        if (buffer.isNotBlank()) result += buffer.toString().trim()
        return result
    }
}
