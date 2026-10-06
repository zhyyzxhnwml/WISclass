package com.shangkele.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 校园 POI：教室位置，用于「该出发了」。见 docs/05-数据模型.md §2.11 */
@Entity(
    tableName = "campus_poi",
    indices = [Index(value = ["roomKey"], unique = true)],
)
data class CampusPoiEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val roomKey: String,
    val building: String?,
    val floor: Int?,
    val roomNo: String?,
    val lat: Double?,
    val lng: Double?,
    /** 从校门步行分钟数（用户校准） */
    val walkMinutesFromGate: Int?,
    /** 从宿舍步行分钟数（用户校准） */
    val walkMinutesFromDorm: Int?,
    val note: String?,
)

/** 个人热词库：ASR 纠错与语境偏置。见 docs/04-端侧AI与模型清单.md §六 */
@Entity(
    tableName = "user_term",
    indices = [Index(value = ["term"], unique = true)],
)
data class UserTermEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val term: String,
    val pinyin: String,
    val weight: Float,
    /** USER / CORRECTION / COURSE_SYLLABUS */
    val source: String,
    val createdAt: Long,
)

/** 端侧模型资产。见 docs/08-全内置打包方案.md §二 */
@Entity(
    tableName = "model_asset",
    indices = [Index(value = ["kind"])],
)
data class ModelAssetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    /** VAD / ASR_LIVE / ASR_REFINE / LLM / EMBED / OCR */
    val kind: String,
    val fileName: String,
    val sha256: String,
    val sizeBytes: Long,
    val quant: String?,
    val version: String?,
    val installedAt: Long,
    val enabled: Boolean,
)

/** 长任务队列状态，支撑中断续跑。见 docs/04-端侧AI与模型清单.md §5.3 */
@Entity(
    tableName = "note_job",
    indices = [Index(value = ["noteId"], unique = true)],
)
data class NoteJobEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val noteId: Long,
    /** ASR_REFINE / SUMMARIZE_CHUNK / MERGE / KNOWLEDGE / OCR */
    val stage: String,
    val cursorChunk: Int,
    val totalChunk: Int,
    /** QUEUED / RUNNING / PAUSED / DONE / FAILED */
    val state: String,
    val attempts: Int,
    val lastError: String?,
    val updatedAt: Long,
)

/** 成绩。见 docs/05-数据模型.md §2.11 */
@Entity(
    tableName = "grade",
    indices = [Index(value = ["semesterId"])],
)
data class GradeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val semesterId: Long,
    val courseId: Long?,
    val courseName: String,
    val credit: Float,
    val score: Float?,
    val gpa: Float?,
    val type: String?,
    val fetchedAt: Long,
)

/** 课程中枢条目：资料 / 考试 / 成绩等聚合。见 docs/05-数据模型.md §2.11 */
@Entity(
    tableName = "course_space_item",
    indices = [Index(value = ["courseId", "type"])],
)
data class CourseSpaceItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val courseId: Long,
    /** NOTE / FILE / EXAM / GRADE */
    val type: String,
    val title: String,
    val uri: String?,
    val metaJson: String?,
    val createdAt: Long,
)
