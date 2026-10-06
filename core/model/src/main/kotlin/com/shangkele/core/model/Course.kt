package com.shangkele.core.model

/** 课程数据来源。 */
enum class CourseSource { API, WEB, MANUAL, DEMO }

/**
 * 一门课在课表上的一次排布。
 *
 * 注意：同一门课一周上两次（如周一 1-2 节、周三 3-4 节）在正方教务的 `kbList`
 * 里是**两条记录**，因此这里的一条 Course 对应一个「时间格」，而不是一个「课程」。
 */
data class Course(
    val id: Long = 0L,
    val semesterId: Long,
    val name: String,
    val teacher: String,
    val roomRaw: String,
    val roomKey: String?,
    val credits: Float,
    val courseType: String?,
    val teachingClass: String?,
    /** 1 = 周一 … 7 = 周日 */
    val weekday: Int,
    val startSection: Int,
    val endSection: Int,
    val weeks: Set<Int>,
    /** 由课程名 hash 得到，保证同名课程跨周次配色稳定 */
    val colorSeed: Int,
    val source: CourseSource = CourseSource.DEMO,
) {
    val sectionSpan: Int get() = endSection - startSection + 1

    /** 是否在本周上课。 */
    fun occursInWeek(week: Int): Boolean = weeks.contains(week)

    /** 是否与另一条记录存在时间冲突。 */
    fun conflictsWith(other: Course): Boolean =
        weekday == other.weekday &&
            weeks.intersect(other.weeks).isNotEmpty() &&
            startSection <= other.endSection &&
            other.startSection <= endSection

    /** 用于课表 diff 的唯一键（见 docs/03-教务系统对接.md §十）。 */
    fun stableKey(): String = buildString {
        append(name).append('|')
        append(teacher).append('|')
        append(weekday).append('|')
        append(startSection).append('-').append(endSection).append('|')
        append(weeks.sorted().joinToString(","))
    }

    companion object {
        /** 未匹配到校园 POI 时统一回退到固定提前量（见 docs/03 §六）。 */
        const val DEFAULT_REMIND_MINUTES = 15
    }
}
