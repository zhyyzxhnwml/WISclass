package com.shangkele.core.database.seed

import androidx.room.withTransaction
import com.shangkele.core.database.ShangKeLeDatabase
import com.shangkele.core.database.dao.CourseDao
import com.shangkele.core.database.dao.SemesterDao
import com.shangkele.core.database.dao.TimeSlotDao
import com.shangkele.core.database.entity.SemesterEntity
import com.shangkele.core.database.mapper.toEntity
import com.shangkele.core.model.Course
import com.shangkele.core.model.CourseSource
import com.shangkele.core.model.SchoolDefaults
import com.shangkele.core.model.Semester
import javax.inject.Inject
import javax.inject.Singleton

/**
 * W1 的假数据播种器：让课表 UI 在没有教务对接的情况下先跑起来。
 *
 * W2 接入真实教务数据后，这个类会在导入成功时被跳过（`seedIfEmpty` 只认空库），
 * 并保留为开发期的「一键恢复演示数据」入口。
 */
@Singleton
class DemoDataSeeder @Inject constructor(
    private val db: ShangKeLeDatabase,
    private val semesterDao: SemesterDao,
    private val courseDao: CourseDao,
    private val timeSlotDao: TimeSlotDao,
) {

    suspend fun seedIfEmpty() {
        if (semesterDao.count() > 0) return
        seed()
    }

    suspend fun seed() {
        val now = System.currentTimeMillis()
        db.withTransaction {
            semesterDao.clearActive()
            val semesterId = semesterDao.insert(
                SemesterEntity(
                    xnm = DEMO_XNM,
                    xqm = DEMO_XQM,
                    name = Semester.buildName(DEMO_XNM, DEMO_XQM),
                    startDateEpochDay = SchoolDefaults.DEFAULT_START_DATE_EPOCH_DAY,
                    totalWeeks = SchoolDefaults.SEMESTER_TOTAL_WEEKS,
                    isActive = true,
                ),
            )
            timeSlotDao.insertAll(SchoolDefaults.TIME_SLOTS.map { it.toEntity(semesterId) })
            courseDao.insertAll(DEMO_COURSES.map { it.copy(semesterId = semesterId).toEntity(now) })
        }
    }

    companion object {
        const val DEMO_XNM = "2026"
        const val DEMO_XQM = "3"

        /**
         * 演示课表。故意混入「单周课」与「短周次实验课」，用于验证周次解析与灰显逻辑。
         */
        private val DEMO_COURSES: List<Course> = listOf(
            demo("高等数学A(上)", "张明", "教学楼A-101", 1, 1, 2, 1..16, 5.0f, "必修"),
            demo("大学英语(三)", "李静", "教学楼B-203", 1, 3, 4, 1..16, 3.0f, "必修"),
            demo("数据结构", "王海", "工科楼C-305", 2, 1, 2, 1..16, 4.0f, "必修"),
            demo("线性代数", "陈丽", "教学楼A-201", 2, 3, 4, 1..16, 3.0f, "必修"),
            demo("数据结构实验", "王海", "实验楼E-201", 2, 5, 6, 5..12, 1.0f, "必修"),
            demo("大学物理", "刘伟", "综合楼D-101", 3, 1, 2, 1..16, 4.0f, "必修"),
            demo("程序设计基础", "赵强", "实验楼E-402", 3, 5, 6, 1..16, 3.5f, "必修"),
            demo("计算机网络", "孙娜", "工科楼C-401", 4, 1, 2, 1..16, 3.0f, "必修"),
            demo("大学英语视听说", "李静", "多媒体教室B-105", 4, 3, 4, oddWeeks(3, 15), 1.0f, "限选"),
            demo("体育(三)", "周涛", "体育馆", 4, 5, 6, 1..16, 1.0f, "必修"),
            demo("马克思主义基本原理", "吴敏", "教学楼A-301", 5, 1, 2, 1..16, 3.0f, "必修"),
            demo("离散数学", "郑华", "教学楼A-205", 5, 3, 4, 1..16, 3.0f, "必修"),
        )

        private fun oddWeeks(from: Int, to: Int): Set<Int> =
            (from..to).filter { it % 2 == 1 }.toSet()

        private fun demo(
            name: String,
            teacher: String,
            room: String,
            weekday: Int,
            startSection: Int,
            endSection: Int,
            weeks: IntRange,
            credits: Float,
            type: String,
        ): Course = demo(name, teacher, room, weekday, startSection, endSection, weeks.toSet(), credits, type)

        private fun demo(
            name: String,
            teacher: String,
            room: String,
            weekday: Int,
            startSection: Int,
            endSection: Int,
            weeks: Set<Int>,
            credits: Float,
            type: String,
        ): Course = Course(
            id = 0L,
            semesterId = 0L,
            name = name,
            teacher = teacher,
            roomRaw = room,
            roomKey = null,
            credits = credits,
            courseType = type,
            teachingClass = "$name-01班",
            weekday = weekday,
            startSection = startSection,
            endSection = endSection,
            weeks = weeks,
            colorSeed = name.hashCode(),
            source = CourseSource.DEMO,
        )
    }
}
