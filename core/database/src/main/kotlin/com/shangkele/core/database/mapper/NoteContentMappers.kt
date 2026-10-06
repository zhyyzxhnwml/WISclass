package com.shangkele.core.database.mapper

import com.shangkele.core.database.entity.SummaryEntity
import com.shangkele.core.database.entity.TranscriptSegmentEntity
import com.shangkele.core.model.NoteSummary
import com.shangkele.core.model.SummaryTier
import com.shangkele.core.model.TranscriptSegment
import kotlinx.serialization.json.Json

fun TranscriptSegmentEntity.toDomain(): TranscriptSegment = TranscriptSegment(
    id = id,
    noteId = noteId,
    index = index,
    startMs = startMs,
    endMs = endMs,
    // 人工修正过的文本优先，用户改过的错字不该被原文盖回去
    text = correctedText?.takeIf { it.isNotBlank() } ?: text,
    isCorrected = isCorrected,
)

fun SummaryEntity.toDomain(): NoteSummary = NoteSummary(
    noteId = noteId,
    modelName = modelName,
    overview = overview,
    keyPoints = decodeList(keyPointsJson),
    terms = decodeList(termsJson),
    confusions = decodeList(confusionsJson),
    todos = decodeList(todosJson),
    quiz = decodeList(quizJson),
    tier = runCatching { SummaryTier.valueOf(tier) }.getOrDefault(SummaryTier.TEMPLATE),
)

/**
 * 列表字段存的是 JSON 数组文本。
 *
 * **解析失败必须降级为空列表而不是抛异常**：这些字段是模型/抽取器的产物，
 * 格式出问题的可能性真实存在，为了一个次要字段把整个笔记页打崩不值得。
 */
private fun decodeList(json: String?): List<String> {
    if (json.isNullOrBlank()) return emptyList()
    return runCatching { Json.decodeFromString<List<String>>(json) }.getOrDefault(emptyList())
}
