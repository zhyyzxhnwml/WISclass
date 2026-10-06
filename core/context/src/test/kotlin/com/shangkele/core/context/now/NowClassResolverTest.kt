package com.shangkele.core.context.now

import com.shangkele.core.model.Course
import com.shangkele.core.model.TimeSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NowClassResolverTest {

    private val slots = mapOf(
        1 to TimeSlot(1, 8 * 60, 8 * 60 + 45),
        2 to TimeSlot(2, 8 * 60 + 55, 9 * 60 + 40),
        3 to TimeSlot(3, 10 * 60, 10 * 60 + 45),
    )

    private fun course(
        name: String,
        weekday: Int = 1,
        start: Int = 1,
        end: Int = 2,
        weeks: Set<Int> = (1..16).toSet(),
    ) = Course(
        semesterId = 1L,
        name = name,
        teacher = "",
        roomRaw = "",
        roomKey = null,
        credits = 0f,
        courseType = null,
        teachingClass = null,
        weekday = weekday,
        startSection = start,
        endSection = end,
        weeks = weeks,
        colorSeed = 0,
    )

    private val courses = listOf(
        course("高等数学", weekday = 1, start = 1, end = 2),
        course("大学英语", weekday = 1, start = 3, end = 3),
        course("数据结构", weekday = 2, start = 1, end = 2),
    )

    private fun minutes(hour: Int, minute: Int) = hour * 60 + minute

    @Test
    fun `上课期间能定位到当前课程`() {
        val now = NowClassResolver.current(courses, slots, weekday = 1, week = 4, nowMinutes = minutes(8, 10))
        assertEquals("高等数学", now?.course?.name)
        assertEquals(minutes(8, 0), now?.startMinutes)
        assertEquals(minutes(9, 40), now?.endMinutes)
    }

    @Test
    fun `连堂课的课间仍算在上这门课`() {
        val now = NowClassResolver.current(courses, slots, weekday = 1, week = 4, nowMinutes = minutes(8, 50))
        assertEquals("高等数学", now?.course?.name)
    }

    @Test
    fun `课间没有当前课程`() {
        val now = NowClassResolver.current(courses, slots, weekday = 1, week = 4, nowMinutes = minutes(9, 50))
        assertNull(now)
    }

    @Test
    fun `下课那一分钟就不算在上课`() {
        assertNull(
            NowClassResolver.current(courses, slots, weekday = 1, week = 4, nowMinutes = minutes(9, 40)),
        )
    }

    @Test
    fun `星期不匹配时找不到`() {
        assertNull(
            NowClassResolver.current(courses, slots, weekday = 3, week = 4, nowMinutes = minutes(8, 10)),
        )
    }

    @Test
    fun `周次不匹配时找不到`() {
        val onlyOdd = listOf(course("单周课", weekday = 1, start = 1, end = 2, weeks = setOf(1, 3, 5)))
        assertNull(
            NowClassResolver.current(onlyOdd, slots, weekday = 1, week = 4, nowMinutes = minutes(8, 10)),
        )
        assertEquals(
            "单周课",
            NowClassResolver.current(onlyOdd, slots, weekday = 1, week = 5, nowMinutes = minutes(8, 10))?.course?.name,
        )
    }

    @Test
    fun `能找到下一节`() {
        val next = NowClassResolver.next(courses, slots, weekday = 1, week = 4, nowMinutes = minutes(9, 50))
        assertEquals("大学英语", next?.course?.name)
    }

    @Test
    fun `能找到上一节`() {
        val previous = NowClassResolver.previous(courses, slots, weekday = 1, week = 4, nowMinutes = minutes(9, 50))
        assertEquals("高等数学", previous?.course?.name)
    }

    @Test
    fun `作息表缺节次时跳过而不是崩`() {
        val weird = listOf(course("实验课", weekday = 1, start = 9, end = 10))
        assertNull(
            NowClassResolver.current(weird, slots, weekday = 1, week = 4, nowMinutes = minutes(10, 0)),
        )
    }

    @Test
    fun `当天的课程列表按周次过滤`() {
        val list = NowClassResolver.todaysCourses(courses, weekday = 1, week = 4)
        assertEquals(listOf("高等数学", "大学英语"), list.map { it.name })
    }
}
