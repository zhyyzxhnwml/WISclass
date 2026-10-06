package com.shangkele.core.context.silence

import com.shangkele.core.model.Course
import com.shangkele.core.model.TimeSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * 「上课手机突然响了」是体感最差的一类 bug，所以这块逻辑单独用单测守住。
 * 固定时区，避免在别的机器上飘。
 *
 * 语义约定：一节连堂课（如 1-2 节）按**一整段**处理，
 * 中间那 10 分钟课间保持静音 —— 学生不会在课间特意去关勿扰再打开。
 */
class SilencePlannerTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private val date = LocalDate.of(2026, 10, 5)

    private val slots = mapOf(
        1 to TimeSlot(1, 8 * 60, 8 * 60 + 45),
        2 to TimeSlot(2, 8 * 60 + 55, 9 * 60 + 40),
        3 to TimeSlot(3, 10 * 60, 10 * 60 + 45),
        4 to TimeSlot(4, 10 * 60 + 55, 11 * 60 + 40),
    )

    private fun at(hour: Int, minute: Int): Long =
        date.atStartOfDay(zone).withHour(hour).withMinute(minute).toInstant().toEpochMilli()

    private fun course(name: String, startSection: Int, endSection: Int) = Course(
        semesterId = 1L,
        name = name,
        teacher = "",
        roomRaw = "",
        roomKey = null,
        credits = 0f,
        courseType = null,
        teachingClass = null,
        weekday = 1,
        startSection = startSection,
        endSection = endSection,
        weeks = (1..16).toSet(),
        colorSeed = 0,
    )

    /** 上午：1-2 节高等数学（8:00-9:40 连堂），3-4 节大学英语（10:00-11:40） */
    private val morning = listOf(
        course("高等数学", 1, 2),
        course("大学英语", 3, 4),
    )

    private fun nextDayFallback(): Long =
        date.plusDays(1).atStartOfDay(zone).plusMinutes(5).toInstant().toEpochMilli()

    @Test
    fun `上课期间应该静音`() {
        val plan = SilencePlanner.plan(morning, slots, at(8, 10), zone)
        assertTrue(plan.shouldBeSilent)
        assertEquals("高等数学", plan.currentCourseName)
    }

    @Test
    fun `连堂课中间的课间保持静音`() {
        val plan = SilencePlanner.plan(morning, slots, at(8, 50), zone)
        assertTrue(plan.shouldBeSilent)
        assertEquals("高等数学", plan.currentCourseName)
    }

    @Test
    fun `第一节课第一分钟就静音`() {
        assertTrue(SilencePlanner.plan(morning, slots, at(8, 0), zone).shouldBeSilent)
    }

    @Test
    fun `下课那一分钟就该恢复`() {
        // 1-2 节 9:40 下课
        val plan = SilencePlanner.plan(morning, slots, at(9, 40), zone)
        assertFalse(plan.shouldBeSilent)
        assertNull(plan.currentCourseName)
        assertEquals(at(10, 0), plan.nextBoundaryAtMillis)
    }

    @Test
    fun `大课间恢复并把边界排到下一节课开始`() {
        val plan = SilencePlanner.plan(morning, slots, at(9, 50), zone)
        assertFalse(plan.shouldBeSilent)
        assertEquals(at(10, 0), plan.nextBoundaryAtMillis)
    }

    @Test
    fun `还没到第一节课`() {
        val plan = SilencePlanner.plan(morning, slots, at(7, 0), zone)
        assertFalse(plan.shouldBeSilent)
        assertEquals(at(8, 0), plan.nextBoundaryAtMillis)
    }

    @Test
    fun `课全上完后排到次日凌晨`() {
        val plan = SilencePlanner.plan(morning, slots, at(15, 0), zone)
        assertFalse(plan.shouldBeSilent)
        assertEquals(nextDayFallback(), plan.nextBoundaryAtMillis)
    }

    @Test
    fun `今天没课也不静音`() {
        val plan = SilencePlanner.plan(emptyList(), slots, at(10, 0), zone)
        assertFalse(plan.shouldBeSilent)
        assertEquals(nextDayFallback(), plan.nextBoundaryAtMillis)
    }

    @Test
    fun `作息表缺少对应节次时跳过而不是崩`() {
        // 第 9-10 节在 slots 里不存在
        val plan = SilencePlanner.plan(listOf(course("实验课", 9, 10)), slots, at(10, 0), zone)
        assertFalse(plan.shouldBeSilent)
        assertEquals(nextDayFallback(), plan.nextBoundaryAtMillis)
    }

    @Test
    fun `作息表起止时间异常时忽略该节课`() {
        val broken = mapOf(1 to TimeSlot(1, 600, 600))
        val plan = SilencePlanner.plan(listOf(course("坏数据", 1, 1)), broken, at(10, 0), zone)
        assertFalse(plan.shouldBeSilent)
        assertEquals(nextDayFallback(), plan.nextBoundaryAtMillis)
    }
}
