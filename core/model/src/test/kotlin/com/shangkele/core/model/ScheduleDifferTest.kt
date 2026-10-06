package com.shangkele.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleDifferTest {

    private fun course(
        name: String,
        teacher: String = "张明",
        room: String = "教学楼A-101",
        weekday: Int = 1,
        start: Int = 1,
        end: Int = 2,
        weeks: Set<Int> = (1..16).toSet(),
    ) = Course(
        semesterId = 1L,
        name = name,
        teacher = teacher,
        roomRaw = room,
        roomKey = null,
        credits = 3f,
        courseType = "必修",
        teachingClass = null,
        weekday = weekday,
        startSection = start,
        endSection = end,
        weeks = weeks,
        colorSeed = name.hashCode(),
    )

    @Test
    fun `完全一致时无变更`() {
        val old = listOf(course("高等数学"), course("大学英语"))
        val new = listOf(course("高等数学"), course("大学英语"))
        assertTrue(ScheduleDiffer.diff(old, new).isEmpty())
    }

    @Test
    fun `教室变动`() {
        val old = listOf(course("高等数学", room = "教学楼A-101"))
        val new = listOf(course("高等数学", room = "教学楼B-203"))
        val changes = ScheduleDiffer.diff(old, new)
        assertEquals(1, changes.size)
        assertEquals(ScheduleChangeType.ROOM_CHANGED, changes[0].type)
        assertEquals("教学楼A-101 → 教学楼B-203", changes[0].detail)
    }

    @Test
    fun `教师变动`() {
        val old = listOf(course("数据结构", teacher = "王海"))
        val new = listOf(course("数据结构", teacher = "赵强"))
        val changes = ScheduleDiffer.diff(old, new)
        assertEquals(1, changes.size)
        assertEquals(ScheduleChangeType.TEACHER_CHANGED, changes[0].type)
    }

    @Test
    fun `周次变动 单双周拆分`() {
        val old = listOf(course("大学英语", weeks = (1..16).toSet()))
        val new = listOf(course("大学英语", weeks = (1..16).filter { it % 2 == 1 }.toSet()))
        val changes = ScheduleDiffer.diff(old, new)
        assertEquals(1, changes.size)
        assertEquals(ScheduleChangeType.WEEKS_CHANGED, changes[0].type)
    }

    @Test
    fun `时间变动不是一增一删`() {
        val old = listOf(course("线性代数", weekday = 2, start = 3, end = 4))
        val new = listOf(course("线性代数", weekday = 3, start = 1, end = 2))
        val changes = ScheduleDiffer.diff(old, new)
        assertEquals(1, changes.size)
        assertEquals(ScheduleChangeType.TIME_CHANGED, changes[0].type)
        assertEquals("周二 第3-4节 → 周三 第1-2节", changes[0].detail)
    }

    @Test
    fun `新增与移除`() {
        val old = listOf(course("大学物理"))
        val new = listOf(course("计算机网络"))
        val changes = ScheduleDiffer.diff(old, new).sortedBy { it.type.name }
        assertEquals(2, changes.size)
        assertTrue(changes.any { it.type == ScheduleChangeType.ADDED })
        assertTrue(changes.any { it.type == ScheduleChangeType.REMOVED })
    }

    @Test
    fun `同一节课多个字段同时变动会报多条`() {
        val old = listOf(course("计算机网络", teacher = "孙娜", room = "工科楼C-401"))
        val new = listOf(course("计算机网络", teacher = "林涛", room = "工科楼C-402"))
        val types = ScheduleDiffer.diff(old, new).map { it.type }.toSet()
        assertEquals(
            setOf(ScheduleChangeType.TEACHER_CHANGED, ScheduleChangeType.ROOM_CHANGED),
            types,
        )
    }

    @Test
    fun `旧课表为空时全部是新增`() {
        val new = listOf(course("高等数学"), course("大学英语"))
        val changes = ScheduleDiffer.diff(emptyList(), new)
        assertEquals(2, changes.size)
        assertTrue(changes.all { it.type == ScheduleChangeType.ADDED })
    }

    @Test
    fun `新课表为空时全部是移除`() {
        val old = listOf(course("高等数学"), course("大学英语"))
        val changes = ScheduleDiffer.diff(old, emptyList())
        assertEquals(2, changes.size)
        assertTrue(changes.all { it.type == ScheduleChangeType.REMOVED })
    }
}
