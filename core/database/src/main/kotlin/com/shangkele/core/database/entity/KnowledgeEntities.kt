package com.shangkele.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** 知识点节点（跨周合并后）。见 docs/05-数据模型.md §2.7 */
@Entity(
    tableName = "knowledge_node",
    indices = [Index(value = ["courseId", "weight"]), Index(value = ["courseId"])],
    foreignKeys = [
        ForeignKey(
            entity = CourseEntity::class,
            parentColumns = ["id"],
            childColumns = ["courseId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class KnowledgeNodeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val courseId: Long,
    val title: String,
    val aliasesJson: String,
    /** bge-small-zh 向量（float32 小端序列化），W7 才写入 */
    val embedding: ByteArray?,
    /** 考点权重 0~1 */
    val weight: Float,
    val triggerScore: Float,
    val repeatScore: Float,
    val ocrScore: Float,
    val questionScore: Float,
    val firstWeek: Int,
    val lastWeek: Int,
    val mentionCount: Int,
    val createdAt: Long,
    val updatedAt: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is KnowledgeNodeEntity) return false
        return id == other.id && title == other.title
    }

    override fun hashCode(): Int = 31 * id.hashCode() + title.hashCode()
}

/** 节点 ↔ 转写片段的引用，支撑「点击知识点回跳原声」。见 docs/05-数据模型.md §2.8 */
@Entity(
    tableName = "knowledge_node_ref",
    indices = [Index(value = ["nodeId"]), Index(value = ["segmentId"])],
)
data class KnowledgeNodeRefEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val nodeId: Long,
    val noteId: Long,
    val segmentId: Long,
    val weekIndex: Int,
)

/** 自测卡与错题。见 docs/05-数据模型.md §2.10 */
@Entity(
    tableName = "quiz_card",
    indices = [Index(value = ["courseId", "dueEpochDay"]), Index(value = ["nodeId"])],
)
data class QuizCardEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val nodeId: Long,
    val courseId: Long,
    /** single / multi / short */
    val type: String,
    val question: String,
    val optionsJson: String?,
    val answer: String,
    val explain: String?,
    val sourceSegmentId: Long?,
    val wrongCount: Int,
    /** 简化 SM-2 */
    val ease: Float,
    val intervalDays: Int,
    val dueEpochDay: Long,
    val lastReviewedAt: Long?,
)
