package com.shangkele.core.context.now

import com.shangkele.core.model.Course
import com.shangkele.core.model.TimeSlot

/**
 * 判断「现在是哪节课」。
 *
 * 录音要自动关联到课程、通知要显示课名、出发提醒要知道下一节 ——
 * 全都依赖这一个判断，所以单独抽出来并用单测守住口径一致。
 *
 * 判定条件三者同时满足：**今天是这个星期几 + 本周上这门课 + 当前时刻落在它的节次区间内**。
 */
object NowClassResolver {

    data class NowClass(
        val course: Course,
        val startMinutes: Int,
        val endMinutes: Int,
    )

    /**
     * @param weekday 1 = 周一 … 7 = 周日
     * @param nowMinutes 距当日 00:00 的分钟数
     */
    fun current(
        courses: List<Course>,
        slots: Map<Int, TimeSlot>,
        weekday: Int,
        week: Int,
        nowMinutes: Int,
    ): NowClass? = windows(courses, slots, weekday, week)
        .firstOrNull { nowMinutes in it.startMinutes until it.endMinutes }

    /** 今天下一节还没开始的课。 */
    fun next(
        courses: List<Course>,
        slots: Map<Int, TimeSlot>,
        weekday: Int,
        week: Int,
        nowMinutes: Int,
    ): NowClass? = windows(courses, slots, weekday, week)
        .firstOrNull { it.startMinutes > nowMinutes }

    /** 今天这门课之前刚刚结束的那节。 */
    fun previous(
        courses: List<Course>,
        slots: Map<Int, TimeSlot>,
        weekday: Int,
        week: Int,
        nowMinutes: Int,
    ): NowClass? = windows(courses, slots, weekday, week)
        .lastOrNull { it.endMinutes <= nowMinutes }

    fun todaysCourses(courses: List<Course>, weekday: Int, week: Int): List<Course> =
        courses.filter { it.weekday == weekday && it.occursInWeek(week) }

    private fun windows(
        courses: List<Course>,
        slots: Map<Int, TimeSlot>,
        weekday: Int,
        week: Int,
    ): List<NowClass> =
        todaysCourses(courses, weekday, week)
            .mapNotNull { course ->
                val start = slots[course.startSection] ?: return@mapNotNull null
                val end = slots[course.endSection] ?: return@mapNotNull null
                if (end.endMinutes <= start.startMinutes) return@mapNotNull null
                NowClass(course, start.startMinutes, end.endMinutes)
            }
            .sortedBy { it.startMinutes }
}
