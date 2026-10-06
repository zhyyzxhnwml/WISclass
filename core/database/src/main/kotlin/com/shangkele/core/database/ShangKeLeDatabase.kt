package com.shangkele.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.shangkele.core.database.converter.Converters
import com.shangkele.core.database.dao.AssignmentDao
import com.shangkele.core.database.dao.CampusPoiDao
import com.shangkele.core.database.dao.ChangeLogDao
import com.shangkele.core.database.dao.CourseDao
import com.shangkele.core.database.dao.NoteDao
import com.shangkele.core.database.dao.SemesterDao
import com.shangkele.core.database.dao.TimeSlotDao
import com.shangkele.core.database.entity.AssignmentEntity
import com.shangkele.core.database.entity.CampusPoiEntity
import com.shangkele.core.database.entity.ChangeLogEntity
import com.shangkele.core.database.entity.CourseEntity
import com.shangkele.core.database.entity.CourseSpaceItemEntity
import com.shangkele.core.database.entity.GradeEntity
import com.shangkele.core.database.entity.KnowledgeNodeEntity
import com.shangkele.core.database.entity.KnowledgeNodeRefEntity
import com.shangkele.core.database.entity.ModelAssetEntity
import com.shangkele.core.database.entity.NoteEntity
import com.shangkele.core.database.entity.NoteJobEntity
import com.shangkele.core.database.entity.PhotoEntity
import com.shangkele.core.database.entity.QuizCardEntity
import com.shangkele.core.database.entity.SemesterEntity
import com.shangkele.core.database.entity.SummaryEntity
import com.shangkele.core.database.entity.TimeSlotEntity
import com.shangkele.core.database.entity.TranscriptSegmentEntity
import com.shangkele.core.database.entity.UserTermEntity

/**
 * 全库唯一入口。实体清单与 docs/05-数据模型.md 对齐。
 *
 * `exportSchema = true` + `schemas/` 目录：从写下第一个 Migration 起就必须开。
 * 手写迁移最怕「和 Room 生成的定义差一点点」—— 那种错只在**老机器升级时**才炸
 * （`Migration didn't properly handle`），新装机器永远复现不出来。
 * 导出的 json 就是用来逐字比对迁移 SQL 的。
 */
@Database(
    entities = [
        SemesterEntity::class,
        CourseEntity::class,
        TimeSlotEntity::class,
        ChangeLogEntity::class,
        NoteEntity::class,
        TranscriptSegmentEntity::class,
        PhotoEntity::class,
        SummaryEntity::class,
        AssignmentEntity::class,
        KnowledgeNodeEntity::class,
        KnowledgeNodeRefEntity::class,
        QuizCardEntity::class,
        CampusPoiEntity::class,
        UserTermEntity::class,
        ModelAssetEntity::class,
        NoteJobEntity::class,
        GradeEntity::class,
        CourseSpaceItemEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class ShangKeLeDatabase : RoomDatabase() {

    abstract fun semesterDao(): SemesterDao

    abstract fun courseDao(): CourseDao

    abstract fun timeSlotDao(): TimeSlotDao

    abstract fun changeLogDao(): ChangeLogDao

    abstract fun noteDao(): NoteDao

    abstract fun assignmentDao(): AssignmentDao

    abstract fun campusPoiDao(): CampusPoiDao

    companion object {
        const val NAME = "shangkele.db"
    }
}
