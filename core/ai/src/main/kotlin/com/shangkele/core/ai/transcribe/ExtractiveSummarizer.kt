package com.shangkele.core.ai.transcribe

/**
 * 机械抽取式摘要（离线兜底档）。
 *
 * **它不是 AI 摘要，也不假装是。** 真正的摘要要等本地 LLM（W6）。
 * 但这一档是**永久保留**的，因为设计里本来就有三档降级：
 * ```
 *   端侧 LLM  →  机械抽取（本文件）  →  只有原文
 * ```
 * 模型没下载、内存不够、推理失败时，用户至少还能拿到「这节课讲了哪几句重点」，
 * 而不是一整片没有结构的转写文本。
 *
 * 做法是经典的抽取式：给每个句子打分，挑最高的几句**按原顺序**输出。
 * 打分的依据只有三个，且都不依赖模型：
 *  - **强调词**：老师说什么「重点/必考/注意」，那就是重点（权重最高）
 *  - **句子长度**：太短没信息（「对」「是的」），太长是废话连篇
 *  - **位置**：开头和结尾更可能是总起与总结
 *
 * 术语用字频 n-gram 统计，**质量有限**（中文没分词器），
 * 所以界面上必须标明这是机械抽取的结果。
 */
object ExtractiveSummarizer {

    data class Sentence(val text: String, val startMs: Long, val endMs: Long)

    data class Stats(
        val sentenceCount: Int,
        val charCount: Int,
        val durationMs: Long,
        val charsPerMinute: Int,
    )

    data class Result(
        val overview: String,
        val keyPoints: List<String>,
        val terms: List<String>,
        val stats: Stats,
        /** 命中了强调词的句子数，用来告诉用户「这一档到底靠不靠谱」 */
        val emphasisHits: Int,
    ) {
        val hasContent: Boolean get() = keyPoints.isNotEmpty() || terms.isNotEmpty()
    }

    /** 老师讲课时表示「这里重要」的说法。权重 4 的几乎是明示考点。 */
    private val EMPHASIS: Map<String, Float> = mapOf(
        "必考" to 4f, "考点" to 4f, "要考" to 4f, "考试" to 3f, "期末" to 3f,
        "重点" to 3f, "记住" to 3f, "容易错" to 3f, "易错" to 3f,
        "关键" to 2f, "核心" to 2f, "必须" to 2f, "注意" to 2f, "不要" to 2f,
        "总结" to 2f, "概括" to 2f, "定义" to 1.5f, "定理" to 1.5f, "公式" to 1.5f,
        "因此" to 1f, "所以" to 1f, "首先" to 1f, "其次" to 1f, "最后" to 1f,
    )

    /**
     * 要点分数线。基准分是 1.0，长度合适的句子拿 +0.8；
     * 门槛设在 0.5 意味着「长度太短被扣分又不带强调词」的句子直接淘汰。
     */
    private const val MIN_SCORE = 0.5f

    private val TERM_STOP = setOf(
        "这个", "那个", "我们", "你们", "他们", "什么", "就是", "然后", "所以",
        "但是", "因为", "可以", "这个", "一个", "现在", "这样", "那样", "如果",
        "还是", "已经", "没有", "一样", "这种", "时候", "东西", "问题", "时候",
    )

    fun summarize(sentences: List<Sentence>, durationMs: Long): Result {
        val texts = sentences.map { it.text }.filter { it.isNotBlank() }
        val fullText = texts.joinToString("")
        val stats = Stats(
            sentenceCount = texts.size,
            charCount = fullText.length,
            durationMs = durationMs,
            charsPerMinute = if (durationMs > 0) {
                (fullText.length * 60_000L / durationMs).toInt()
            } else {
                0
            },
        )

        if (texts.isEmpty()) {
            return Result("这段录音没有识别出文字。", emptyList(), emptyList(), stats, 0)
        }

        val scored = texts.mapIndexed { index, text -> Triple(index, text, score(text, index, texts.size)) }
        val emphasisHits = texts.count { countEmphasis(it) > 0f }

        val keep = (texts.size / 8).coerceIn(3, 8).coerceAtMost(texts.size)
        val ranked = scored.sortedByDescending { it.third }

        // 先过分数门槛，再取前 keep 条。
        // 不过滤的话，句子少时会拿「对」「嗯」「是」这类零信息的话来凑数 ——
        // 那比只给一条要点更糟：噪声冒充要点，用户得自己分辨哪条是真的。
        // 一条都不过门槛时才退回问分最高的那句（总比空手强）。
        val candidates = ranked.filter { it.third >= MIN_SCORE }.ifEmpty { ranked.take(1) }

        val points = candidates
            .take(keep)
            .sortedBy { it.first }          // 还原成上课顺序，读起来才有逻辑
            .map { it.second }

        val terms = extractTerms(fullText, limit = 10)

        return Result(
            overview = buildOverview(stats, points.size, emphasisHits),
            keyPoints = points,
            terms = terms,
            stats = stats,
            emphasisHits = emphasisHits,
        )
    }

    private fun buildOverview(stats: Stats, pointCount: Int, emphasisHits: Int): String {
        val minutes = stats.durationMs / 60_000
        val seconds = (stats.durationMs % 60_000) / 1000
        val duration = if (minutes > 0) "${minutes}分${seconds}秒" else "${seconds}秒"
        val pace = when {
            stats.charsPerMinute <= 0 -> ""
            stats.charsPerMinute < 120 -> "，语速偏慢（讲得细）"
            stats.charsPerMinute > 260 -> "，语速偏快（可能讲得快、漏得多）"
            else -> ""
        }
        val emphasis = if (emphasisHits > 0) {
            "；其中 $emphasisHits 句带「重点/必考」一类强调"
        } else {
            "；没有听到明显的强调用语"
        }
        return "时长 $duration，共识别 ${stats.sentenceCount} 句 / ${stats.charCount} 字$pace。已挑出 $pointCount 句要点$emphasis。"
    }

    /** 句子打分：强调词为主，长度与位置为辅。 */
    private fun score(text: String, index: Int, total: Int): Float {
        var s = 1f
        s += countEmphasis(text) * 1.6f

        // 8~40 字信息量最合适；过短无信息，过长多半是一串语气词
        val length = text.length
        s += when {
            length < 6 -> -1.2f
            length in 8..40 -> 0.8f
            length in 41..70 -> 0.4f
            else -> -0.3f
        }

        // 开头与结尾更可能是总起 / 总结
        if (total > 3) {
            if (index == 0) s += 0.3f
            if (index >= total - 2) s += 0.2f
        }
        return s
    }

    private fun countEmphasis(text: String): Float {
        var sum = 0f
        for ((word, weight) in EMPHASIS) {
            if (text.contains(word)) sum += weight
        }
        return sum
    }

    /**
     * 术语抽取：统计 2~4 字的连续汉字片段出现次数。
     *
     * 没有分词器，所以只能这么糙。用一个「最长优先」的贪心把
     * 「图书馆」和「图书」这种互为子串的重复项去掉。
     */
    internal fun extractTerms(text: String, limit: Int, minCount: Int = 3): List<String> {
        val runs = text.replace(Regex("[^\\u4e00-\\u9fa5]"), " ").split(' ')
        val counts = HashMap<String, Int>()
        for (run in runs) {
            if (run.length < 2) continue
            for (n in 2..4) {
                if (run.length < n) break
                for (i in 0..run.length - n) {
                    val gram = run.substring(i, i + n)
                    if (gram in TERM_STOP) continue
                    counts[gram] = (counts[gram] ?: 0) + 1
                }
            }
        }

        val ranked = counts.entries
            .filter { it.value >= minCount }
            .sortedWith(
                compareByDescending<Map.Entry<String, Int>> { it.value * it.key.length }
                    .thenByDescending { it.key.length },
            )
            .map { it.key }

        val picked = mutableListOf<String>()
        for (term in ranked) {
            // 已经选过更长的词，就不要再选它的子串
            if (picked.any { it.contains(term) }) continue
            picked += term
            if (picked.size >= limit) break
        }
        return picked
    }
}
