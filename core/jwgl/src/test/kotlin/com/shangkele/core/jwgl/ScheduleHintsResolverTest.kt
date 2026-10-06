package com.shangkele.core.jwgl

import com.shangkele.core.model.TimeSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 换算逻辑测试。
 *
 * 两条换算错了代价都很高，而且都不会报错：
 *  - 第一周错一周 → 整张课表的本周判定全错
 *  - 作息时间错 → 上课静音和「该出发了」在错误时刻触发
 */
class ScheduleHintsResolverTest {

    /** 2026-09-07 是周一，其 epochDay = 20703（与 SchoolDefaults 的默认值一致）。 */
    private val monday20260907 = 20703L

    // ---- 校历反推 ----

    @Test
    fun `第1周的周一就是本周周一`() {
        // 20705 = 周三
        assertEquals(monday20260907, ScheduleHintsResolver.firstWeekMonday(20705L, 1))
    }

    @Test
    fun `第5周反推回4周前`() {
        assertEquals(monday20260907 - 28L, ScheduleHintsResolver.firstWeekMonday(20705L, 5))
    }

    @Test
    fun `周一当天也能正确反推`() {
        assertEquals(monday20260907, ScheduleHintsResolver.firstWeekMonday(monday20260907, 1))
        assertEquals(monday20260907 - 14L, ScheduleHintsResolver.firstWeekMonday(monday20260907, 3))
    }

    @Test
    fun `周日算作本周最后一天而不是下一周`() {
        // 20709 = 周日，属于 20703 那一周
        assertEquals(monday20260907, ScheduleHintsResolver.firstWeekMonday(20709L, 1))
    }

    @Test
    fun `周次非法时按第1周处理而不是算出乱七八糟的日期`() {
        assertEquals(monday20260907, ScheduleHintsResolver.firstWeekMonday(20705L, 0))
        assertEquals(monday20260907, ScheduleHintsResolver.firstWeekMonday(20705L, -3))
    }

    // ---- 作息换算 ----

    @Test
    fun `单节数据可直接采用且不标记为推算`() {
        val detected = listOf(
            DetectedSectionTime(1, 1, 8 * 60 + 30, 9 * 60 + 15),
            DetectedSectionTime(2, 2, 9 * 60 + 25, 10 * 60 + 10),
        )
        val result = ScheduleHintsResolver.resolveTimeSlots(detected)
        assertEquals(2, result.slots.size)
        assertFalse("页面直接给的逐节时间不需要推算", result.assumed)
        assertEquals(8 * 60 + 30, result.slots[0].startMinutes)
    }

    @Test
    fun `分块时间按四十五分钟拆分并标记为推算`() {
        val detected = listOf(DetectedSectionTime(1, 2, 8 * 60, 9 * 60 + 40))
        val result = ScheduleHintsResolver.resolveTimeSlots(detected)

        assertEquals(2, result.slots.size)
        assertTrue("拆块用到了假设，必须让用户核对", result.assumed)
        assertEquals(TimeSlot(1, 8 * 60, 8 * 60 + 45), result.slots[0])
        // 课间 = 100 - 45*2 = 10 分钟
        assertEquals(TimeSlot(2, 8 * 60 + 55, 9 * 60 + 40), result.slots[1])
    }

    @Test
    fun `分块总时长不够时跳过而不是产出负时长`() {
        // 第1-4节只给了 60 分钟，四节 45 分钟根本放不下
        val detected = listOf(DetectedSectionTime(1, 4, 8 * 60, 9 * 60))
        val result = ScheduleHintsResolver.resolveTimeSlots(detected)
        assertTrue("放不下就不该硬拆", result.slots.isEmpty())
        assertTrue(result.notes.isNotEmpty())
    }

    @Test
    fun `同时有逐节与分块时只采用逐节`() {
        val detected = listOf(
            DetectedSectionTime(1, 2, 8 * 60, 9 * 60 + 40),
            DetectedSectionTime(1, 1, 8 * 60 + 30, 9 * 60 + 15),
        )
        val result = ScheduleHintsResolver.resolveTimeSlots(detected)
        assertEquals(1, result.slots.size)
        assertFalse(result.assumed)
        assertEquals(8 * 60 + 30, result.slots[0].startMinutes)
    }

    @Test
    fun `空输入返回空`() {
        val result = ScheduleHintsResolver.resolveTimeSlots(emptyList())
        assertTrue(result.isEmpty)
        assertFalse(result.assumed)
    }

    // ---- 与现有作息对比 ----

    @Test
    fun `完全一致时没有差异`() {
        val current = listOf(TimeSlot(1, 510, 555))
        val same = listOf(TimeSlot(1, 510, 555))
        assertTrue(ScheduleHintsResolver.diff(current, same).isEmpty())
    }

    @Test
    fun `时间不同时列出差异`() {
        val current = listOf(TimeSlot(1, 480, 525))   // 08:00-08:45，就是之前那组错的默认值
        val detected = listOf(TimeSlot(1, 510, 555))  // 08:30-09:15
        val diff = ScheduleHintsResolver.diff(current, detected)

        assertEquals(1, diff.size)
        assertEquals("08:00-08:45", diff[0].current)
        assertEquals("08:30-09:15", diff[0].detected)
    }

    @Test
    fun `现有作息里没有的节次也会列出`() {
        val diff = ScheduleHintsResolver.diff(emptyList(), listOf(TimeSlot(3, 600, 645)))
        assertEquals(1, diff.size)
        assertEquals("（无）", diff[0].current)
    }
}
