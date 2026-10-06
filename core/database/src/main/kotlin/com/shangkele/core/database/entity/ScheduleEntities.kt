package com.shangkele.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 学期。见 docs/05-数据模型.md §2.1 */
@Entity(tableName = "semester")
data class SemesterEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val xnm: String,
    val xqm: String,
    val name: String,
    /** 第一周周一的 epochDay，用户校准 */
    val startDateEpochDay: Long,
    val totalWeeks: Int,
    val isActive: Boolean,
)

/** 课程（一条记录 = 课表上的一个时间格）。见 docs/05-数据模型.md §2.2 */
@Entity(
    tableName = "course",
    indices = [
        Index(value = ["semesterId", "weekday"]),
        Index(value = ["stableKey"], unique = true),
    ],
)
data class CourseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val semesterId: Long,
    val name: String,
    val teacher: String,
    val roomRaw: String,
    val roomKey: String?,
    val campus: String?,
    val credits: Float,
    val courseType: String?,
    val teachingClass: String?,
    val classGroup: String?,
    /** 1 = 周一 … 7 = 周日 */
    val weekday: Int,
    val startSection: Int,
    val endSection: Int,
    /** JSON 数组文本，如 "[1,2,3,4]" */
    val weeksJson: String,
    val colorSeed: Int,
    /** API / WEB / MANUAL / DEMO */
    val source: String,
    val stableKey: String,
    val rawJson: String?,
    val updatedAt: Long,
)

/** 作息表。见 docs/05-数据模型.md §2.3 */
@Entity(
    tableName = "time_slot",
    indices = [Index(value = ["semesterId", "section"], unique = true)],
)
data class TimeSlotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val semesterId: Long,
    val section: Int,
    val startMinutes: Int,
    val endMinutes: Int,
)

/** 课表变更记录。见 docs/05-数据模型.md §2.11 */
@Entity(tableName = "change_log")
data class ChangeLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val semesterId: Long,
    val courseStableKey: String,
    /** ADD / REMOVE / TIME / ROOM / TEACHER */
    val changeType: String,
    val beforeJson: String?,
    val afterJson: String?,
    val createdAt: Long,
    val read: Boolean = false,
)
