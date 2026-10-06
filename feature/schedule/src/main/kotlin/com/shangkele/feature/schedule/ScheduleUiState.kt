package com.shangkele.feature.schedule

import com.shangkele.core.context.departure.DepartureAdvice
import com.shangkele.core.model.Course
import com.shangkele.core.model.SchoolDefaults
import com.shangkele.core.model.Semester
import com.shangkele.core.model.TimeSlot
import com.shangkele.core.model.WeekCalculator
import com.shangkele.core.model.WeekGrid
import java.time.LocalDate

/** 「下一节课」提示卡数据。 */
data class NextCourseHint(
    val courseName: String,
    val teacher: String,
    val room: String,
    val timeLabel: String,
    val minutesUntilStart: Long,
    val ongoing: Boolean,
    /** 归一化后的教室键（`A-101`）。「我到教室了」要按它存坐标。 */
    val roomKey: String? = null,
)

data class ScheduleUiState(
    val loading: Boolean = true,
    val semester: Semester? = null,
    val currentWeek: Int = 1,
    /** 由翻页驱动；「本周/非本周」的判定都基于它 */
    val selectedWeek: Int = 1,
    val totalWeeks: Int = SchoolDefaults.SEMESTER_TOTAL_WEEKS,
    val todayWeekday: Int = 1,
    val timeSlots: List<TimeSlot> = emptyList(),
    /** 整个学期的课，翻到哪周由界面用 TimetableLayout 现算，避免每页都从 ViewModel 取一次 */
    val allCourses: List<Course> = emptyList(),
    val selectedCourse: Course? = null,
    val nextCourse: NextCourseHint? = null,
    /** 「该出发了」建议：下一节课该几点出门、为什么 */
    val departure: DepartureAdvice? = null,
    /** 这节课的教室坐标是否已经记过。没记过就在卡片上给「我到教室了」 */
    val classroomLocationKnown: Boolean = false,
    /** 正在取定位 */
    val capturingLocation: Boolean = false,
    /** 「我到教室了」的结果提示（成功，或失败原因），显示在卡片里 */
    val captureMessage: String? = null,
    val unreadChangeCount: Int = 0,
    /**
     * 校历是否已被用户确认过。
     *
     * 没确认过就在课表上方挂一条提示 —— **不猜、但也不装作没事**。
     * 默认 true，避免刚进页面数据还没到就闪一下提示。
     */
    val calendarConfirmed: Boolean = true,
) {
    val hasData: Boolean get() = timeSlots.isNotEmpty() || allCourses.isNotEmpty()

    val isCurrentWeek: Boolean get() = selectedWeek == currentWeek

    val semesterTitle: String get() = semester?.name.orEmpty()

    /** 「下一节课」提示只认本周，不跟随翻页。 */
    val nextCourseForCurrentWeek: NextCourseHint? get() = nextCourse

    /** 开学日是星期几（1 = 周一 … 7 = 周日）。列顺序和周次都以它为锚。 */
    val startWeekday: Int
        get() {
            val start = semester?.startDateEpochDay ?: 0L
            return if (start > 0L) WeekCalculator.weekdayOf(start) else 1
        }

    /**
     * 七列的表头。
     *
     * **列顺序跟着开学日是周几旋转。** 8/29（周六）开学，第 1 周就是
     * 周六 ~ 周五，格子里也必须按这个顺序摆 —— 否则课程会被画到错误的列上，
     * 而画错的格子和正确的格子长得一模一样。
     */
    val weekdayLabels: List<String>
        get() = WeekGrid.columnLabels(startWeekday)

    /** 选中周第一天的 epochDay；校历没设置时为 0。 */
    val selectedWeekStart: Long
        get() = semester?.let {
            WeekCalculator.weekStartEpochDay(it.startDateEpochDay, selectedWeek)
        } ?: 0L

    /**
     * 选中周七天各自的「日」，按**列顺序**排列，如 `[29, 30, 31, 1, 2, 3, 4]`。
     *
     * 这是校历正确性的第二个可见证据：每一列头顶的日号告诉你「周三具体是几号」。
     * 看错周时拿它和教务系统对一下就能发现，不用靠感觉。
     */
    val weekDayNumbers: List<Int>
        get() = dayDates.map { it.dayOfMonth }

    /** 每一列对应的月份，只在月份变化时显示，避免七列都重复写「10月」。 */
    val weekDayMonths: List<Int>
        get() = dayDates.map { it.monthValue }

    private val dayDates: List<LocalDate>
        get() {
            val start = selectedWeekStart
            if (start <= 0L) return emptyList()
            return (0L..6L).map { LocalDate.ofEpochDay(start + it) }
        }

    /**
     * 今天在选中周的哪一列（0..6）；**看的不是本周时为 -1**。
     *
     * 之前高亮的是「今天是周三」，所以翻到第 3 周时也会把周三标亮 ——
     * 那和用户正在看的那一周没有关系，反而误导。
     */
    val todayColumn: Int
        get() {
            if (!isCurrentWeek) return -1
            val start = selectedWeekStart
            if (start <= 0L) return -1
            val offset = (LocalDate.now().toEpochDay() - start).toInt()
            return if (offset in 0..6) offset else -1
        }
}
