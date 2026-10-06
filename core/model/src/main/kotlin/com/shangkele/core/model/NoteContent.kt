package com.shangkele.core.model

/** 一条转写片段（句子级），带时间戳用于回跳原声。 */
data class TranscriptSegment(
    val id: Long,
    val noteId: Long,
    val index: Int,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val isCorrected: Boolean,
) {
    val timestampLabel: String get() = formatTimestamp(startMs)
}

/** 摘要的来源档位。三级降级的落点。 */
enum class SummaryTier {
    /** 端侧 LLM 生成 */
    LLM,

    /** 机械抽取（离线兜底，没有模型时用） */
    TEMPLATE,

    /** 外部大模型（可选开关） */
    EXTERNAL,
}

/**
 * 一条笔记的结构化摘要。
 *
 * 字段刻意做成列表而不是一大段文本：后续的复习、自测、考点权重都要按条目消费，
 * 一大段文字没法用。
 */
data class NoteSummary(
    val noteId: Long,
    val modelName: String,
    val overview: String,
    val keyPoints: List<String>,
    val terms: List<String>,
    val confusions: List<String>,
    val todos: List<String>,
    val quiz: List<String>,
    val tier: SummaryTier,
) {
    /** 机械抽取的结果必须在界面上标明，不能冒充 AI 摘要。 */
    val isTemplate: Boolean get() = tier == SummaryTier.TEMPLATE

    val hasAnything: Boolean
        get() = keyPoints.isNotEmpty() || terms.isNotEmpty() ||
            confusions.isNotEmpty() || todos.isNotEmpty() || quiz.isNotEmpty()
}

/** 把毫秒转成 `12:34` / `1:02:34`，用于时间轴展示。 */
fun formatTimestamp(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}
