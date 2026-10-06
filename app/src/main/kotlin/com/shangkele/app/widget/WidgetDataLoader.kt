package com.shangkele.app.widget

import android.content.Context
import com.shangkele.core.model.SchoolDefaults
import com.shangkele.core.model.WeekCalculator
import java.time.LocalDate
import java.time.LocalTime

/** 小组件要展示的一节课。 */
data class WidgetCourse(
    val name: String,
    val room: String,
    val timeLabel: String,
    val startMinutes: Int,
    val ongoing: Boolean,
    val isNext: Boolean,
)

data class WidgetState(
    val weekLabel: String,
    val courses: List<WidgetCourse>,
    /** 没有课表 / 今天没课时的提示文案 */
    val emptyHint: String,
    val hasSemester: Boolean,
)

/**
 * 小组件的数据装载。
 *
 * 只取「今天 + 本周」的课，和课表页的判定口径保持一致（`occursInWeek`）。
 */
object WidgetDataLoader {

    suspend fun load(context: Context): WidgetState {
        val repository = context.scheduleRepository()
        val semester = repository.getActiveSemester()
            ?: return WidgetState(
                weekLabel = "尚未导入课表",
                courses = emptyList(),
                emptyHint = "打开「上课啦」导入课表",
                hasSemester = false,
            )

        val courses = repository.getCourses(semester.id)
        val slots = repository.getTimeSlots(semester.id).associateBy { it.section }

        val today = LocalDate.now()
        val epochDay = today.toEpochDay()
        val week = WeekCalculator.currentWeek(semester.startDateEpochDay, epochDay, semester.totalWeeks)
        val weekday = today.dayOfWeek.value
        val nowMinutes = LocalTime.now().let { it.hour * 60 + it.minute }

        val rows = courses
            .filter { it.weekday == weekday && it.occursInWeek(week) }
            .mapNotNull { course ->
                val start = slots[course.startSection] ?: return@mapNotNull null
                val end = slots[course.endSection] ?: return@mapNotNull null
                WidgetCourse(
                    name = course.name,
                    room = course.roomRaw.ifBlank { "教室待定" },
                    timeLabel = "${start.startLabel()}-${end.endLabel()}",
                    startMinutes = start.startMinutes,
                    ongoing = nowMinutes in start.startMinutes until end.endMinutes,
                    isNext = false,
                )
            }
            .sortedBy { it.startMinutes }

        // 标记「下一节」：正在上的优先显示，否则是第一门还没开始的
        val ongoingIndex = rows.indexOfFirst { it.ongoing }
        val nextIndex = if (ongoingIndex >= 0) {
            ongoingIndex
        } else {
            rows.indexOfFirst { it.startMinutes > nowMinutes }
        }
        val marked = rows.mapIndexed { index, row -> row.copy(isNext = index == nextIndex) }

        val weekdayLabel = SchoolDefaults.WEEKDAY_LABELS.getOrNull(weekday - 1) ?: ""
        return WidgetState(
            weekLabel = "第 $week 周 · $weekdayLabel",
            courses = marked,
            emptyHint = "今天没有课",
            hasSemester = true,
        )
    }
}
