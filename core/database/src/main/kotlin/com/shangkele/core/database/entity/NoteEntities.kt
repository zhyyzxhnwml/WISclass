package com.shangkele.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** 课堂笔记（一次录音）。见 docs/05-数据模型.md §2.4 */
@Entity(
    tableName = "note",
    indices = [Index(value = ["courseId"]), Index(value = ["semesterId", "dateEpochDay"])],
    foreignKeys = [
        ForeignKey(
            entity = CourseEntity::class,
            parentColumns = ["id"],
            childColumns = ["courseId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
)
data class NoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val courseId: Long?,
    val semesterId: Long,
    val weekIndex: Int,
    val dateEpochDay: Long,
    val startedAtMs: Long,
    val durationMs: Long,
    val audioPath: String?,
    val audioSizeBytes: Long,
    val audioKept: Boolean,
    val transcriptText: String?,
    val liveTranscriptText: String?,
    /** RECORDING / RECORDED / REFINING / SUMMARIZING / DONE / FAILED */
    val status: String,
    val title: String?,
    val createdAt: Long,
)

/** 转写片段（带时间戳，用于回跳原声）。见 docs/05-数据模型.md §2.5 */
@Entity(
    tableName = "transcript_segment",
    indices = [Index(value = ["noteId", "startMs"])],
    foreignKeys = [
        ForeignKey(
            entity = NoteEntity::class,
            parentColumns = ["id"],
            childColumns = ["noteId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class TranscriptSegmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val noteId: Long,
    val index: Int,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    /** 人工修正后的文本，非空则优先使用 */
    val correctedText: String?,
    val isCorrected: Boolean,
    val emphasisScore: Float,
)

/** 优先使用人工修正后的文本。放在实体外部，避免 Room 把计算属性当成列。 */
fun TranscriptSegmentEntity.displayText(): String =
    correctedText?.takeIf { it.isNotBlank() } ?: text

/** 结构化摘要。见 docs/05-数据模型.md §2.6 */
@Entity(
    tableName = "summary",
    indices = [Index(value = ["noteId"], unique = true)],
    foreignKeys = [
        ForeignKey(
            entity = NoteEntity::class,
            parentColumns = ["id"],
            childColumns = ["noteId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class SummaryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val noteId: Long,
    val modelName: String,
    val overview: String,
    val keyPointsJson: String,
    val termsJson: String,
    val confusionsJson: String,
    val todosJson: String,
    val quizJson: String,
    /** LLM / TEMPLATE / EXTERNAL */
    val tier: String,
    val createdAt: Long,
)

/**
 * 挂在笔记上的照片。
 *
 * `offsetMs` 是**相对本次录音开始的时间轴位置**：上课时拍一张板书，
 * 回看时就能知道「拍这张的时候老师正在讲什么」。课后再补拍的照片为 null。
 * 索引按 (noteId, offsetMs) 建 —— 详情页就是按时间轴顺序读的。
 */
@Entity(
    tableName = "photo",
    indices = [Index(value = ["noteId", "offsetMs"])],
    foreignKeys = [
        ForeignKey(
            entity = NoteEntity::class,
            parentColumns = ["id"],
            childColumns = ["noteId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class PhotoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val noteId: Long,
    val semesterId: Long,
    val courseId: Long?,
    /** 相对录音开始的毫秒偏移；null = 不挂时间轴 */
    val offsetMs: Long?,
    val takenAtMs: Long,
    val path: String,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
    val createdAt: Long,
)

/** 从课堂内容抽取的作业/待办。见 docs/05-数据模型.md §2.9 */
@Entity(
    tableName = "assignment",
    indices = [Index(value = ["courseId", "dueEpochDay"])],
)
data class AssignmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val courseId: Long?,
    val noteId: Long?,
    val title: String,
    val dueEpochDay: Long?,
    /** 老师的原话，如「下周三前」 */
    val dueRawText: String?,
    val confidence: Float,
    val done: Boolean,
    val remindAtMs: Long?,
)
