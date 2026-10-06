package com.shangkele.core.context.departure

import com.shangkele.core.model.Course
import com.shangkele.core.model.GeoPoint
import com.shangkele.core.model.TimeSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class DeparturePlannerTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private val date = LocalDate.of(2026, 10, 5)

    private val slots = mapOf(
        1 to TimeSlot(1, 8 * 60, 8 * 60 + 45),
        2 to TimeSlot(2, 8 * 60 + 55, 9 * 60 + 40),
        3 to TimeSlot(3, 10 * 60, 10 * 60 + 45),
        4 to TimeSlot(4, 10 * 60 + 55, 11 * 60 + 40),
        5 to TimeSlot(5, 14 * 60, 14 * 60 + 45),
        6 to TimeSlot(6, 14 * 60 + 55, 15 * 60 + 40),
    )

    private fun at(hour: Int, minute: Int): Long =
        date.atStartOfDay(zone).withHour(hour).withMinute(minute).toInstant().toEpochMilli()

    private fun course(
        name: String,
        room: String,
        start: Int,
        end: Int,
    ) = Course(
        semesterId = 1L,
        name = name,
        teacher = "",
        roomRaw = room,
        roomKey = com.shangkele.core.common.classroom.RoomKeyNormalizer.normalize(room),
        credits = 0f,
        courseType = null,
        teachingClass = null,
        weekday = 1,
        startSection = start,
        endSection = end,
        weeks = (1..16).toSet(),
        colorSeed = 0,
    )

    @Test
    fun `今天第一节用默认提前量`() {
        val courses = listOf(course("高等数学", "A-101", 1, 2))
        val plan = DeparturePlanner.plan(courses, slots, at(7, 30), zone)

        val advice = plan.advice!!
        assertEquals(DepartureReason.UNKNOWN_BUILDING, advice.reason)
        assertEquals(10, advice.leadMinutes)
        assertEquals("07:50", advice.leaveLabel)
        assertNull("今天第一节没有上一节课", advice.gapMinutes)
    }

    @Test
    fun `换楼留 15 分钟`() {
        val courses = listOf(
            course("高等数学", "A-101", 1, 2),
            course("大学物理", "C-305", 3, 4),
        )
        val plan = DeparturePlanner.plan(courses, slots, at(9, 41), zone)

        val advice = plan.advice!!
        assertEquals(DepartureReason.DIFFERENT_BUILDING, advice.reason)
        assertEquals(15, advice.leadMinutes)
        assertEquals("09:45", advice.leaveLabel)
        assertEquals("大学物理", advice.courseName)
        assertEquals(20, advice.gapMinutes)
    }

    @Test
    fun `同楼只留 5 分钟`() {
        val courses = listOf(
            course("高等数学", "A-101", 1, 2),
            course("线性代数", "A-205", 3, 4),
        )
        val plan = DeparturePlanner.plan(courses, slots, at(9, 41), zone)

        val advice = plan.advice!!
        assertEquals(DepartureReason.SAME_BUILDING, advice.reason)
        assertEquals(5, advice.leadMinutes)
        assertEquals("09:55", advice.leaveLabel)
    }

    @Test
    fun `带数字前缀的楼栋也能识别为同楼`() {
        val courses = listOf(
            course("计算机网络", "3B301", 1, 2),
            course("操作系统", "3B302", 3, 4),
        )
        val advice = DeparturePlanner.plan(courses, slots, at(9, 41), zone).advice!!
        assertEquals(DepartureReason.SAME_BUILDING, advice.reason)
    }

    @Test
    fun `课间比提前量还短就一下课走`() {
        // 第 1 节 8:45 下课，第 2 节 8:55 上课 —— 中间只有 10 分钟，
        // 但要换楼（需要 15 分钟）
        val tightSlots = mapOf(
            1 to TimeSlot(1, 8 * 60, 8 * 60 + 45),
            2 to TimeSlot(2, 8 * 60 + 45, 9 * 60 + 30),
        )
        val courses = listOf(
            course("高等数学", "A-101", 1, 1),
            course("大学物理", "C-305", 2, 2),
        )
        val advice = DeparturePlanner.plan(courses, tightSlots, at(8, 30), zone).advice!!

        assertEquals(DepartureReason.TIGHT_GAP, advice.reason)
        assertEquals("08:45", advice.leaveLabel)
        assertEquals(0, advice.gapMinutes)
        assertTrue(advice.reasonLabel.contains("一下课就得走"))
    }

    @Test
    fun `到点了就该提醒`() {
        val courses = listOf(
            course("高等数学", "A-101", 1, 2),
            course("大学物理", "C-305", 3, 4),
        )
        // 提前量 15 分钟 => 09:45 出发，09:50 时应该提醒
        val plan = DeparturePlanner.plan(courses, slots, at(9, 50), zone)
        assertTrue(plan.shouldNotifyNow)
    }

    @Test
    fun `还没到出发时间不提醒`() {
        val courses = listOf(
            course("高等数学", "A-101", 1, 2),
            course("大学物理", "C-305", 3, 4),
        )
        // 09:43 还没到 09:45
        val plan = DeparturePlanner.plan(courses, slots, at(9, 43), zone)
        assertFalse(plan.shouldNotifyNow)
        assertEquals(at(9, 45), plan.nextBoundaryAtMillis)
    }

    @Test
    fun `已经上课了就不再提醒 只看下一节`() {
        val courses = listOf(
            course("高等数学", "A-101", 1, 2),
            course("大学物理", "C-305", 3, 4),
        )
        // 10:20 时 3-4 节正在上（10:00-11:40），没有下一节了
        val plan = DeparturePlanner.plan(courses, slots, at(10, 20), zone)
        assertNull(plan.advice)
        assertFalse(plan.shouldNotifyNow)
        assertEquals(
            date.plusDays(1).atStartOfDay(zone).plusMinutes(5).toInstant().toEpochMilli(),
            plan.nextBoundaryAtMillis,
        )
    }

    @Test
    fun `一直翻到下午的课`() {
        val courses = listOf(
            course("高等数学", "A-101", 1, 2),
            course("大学物理", "C-305", 5, 6),
        )
        val plan = DeparturePlanner.plan(courses, slots, at(9, 50), zone)
        val advice = plan.advice!!
        assertEquals("大学物理", advice.courseName)
        // 14:00 上课，换楼 => 13:45 出发
        assertEquals("13:45", advice.leaveLabel)
    }

    @Test
    fun `拿不到楼栋信息时退化成默认提前量而不是乱猜`() {
        val courses = listOf(
            course("高等数学", "101", 1, 2),
            course("大学物理", "202", 3, 4),
        )
        val advice = DeparturePlanner.plan(courses, slots, at(9, 41), zone).advice!!
        assertEquals(DepartureReason.UNKNOWN_BUILDING, advice.reason)
        assertEquals(10, advice.leadMinutes)
    }

    @Test
    fun `今天没课时不提醒`() {
        val plan = DeparturePlanner.plan(emptyList(), slots, at(9, 0), zone)
        assertNull(plan.advice)
        assertFalse(plan.shouldNotifyNow)
    }

    @Test
    fun `作息表缺节次时跳过而不是崩`() {
        val courses = listOf(course("实验课", "E-402", 9, 10))
        assertNull(DeparturePlanner.plan(courses, slots, at(9, 0), zone).advice)
    }

    // ---- 采到教室坐标之后：按「空间 + 时间」算 ----

    private val rooms = mapOf("A-101" to GeoPoint(26.0, 119.0))

    @Test
    fun `采到教室坐标后就按步行时间算`() {
        // 10:00 上课
        val courses = listOf(course("高等数学", "A-101", 3, 4))
        val plan = DeparturePlanner.plan(
            coursesToday = courses,
            slots = slots,
            nowMillis = at(9, 0),
            zone = zone,
            // 离教室约 1.1 公里
            current = GeoPoint(26.01, 119.0),
            roomLocations = rooms,
        )

        val advice = plan.advice!!
        assertEquals(DepartureReason.WALKING, advice.reason)
        // 1112 米直线 → 乘绕路系数 1.3 → 除以 70 米/分 → 向上取整 21 分钟
        assertEquals(21, advice.walkMinutes)
        // 再加上到教室后的 3 分钟缓冲
        assertEquals(24, advice.leadMinutes)
        assertEquals("09:36", advice.leaveLabel)
        assertTrue("距离应在 1100~1125 米之间，实际 ${advice.distanceMeters}", advice.distanceMeters!! in 1100..1125)
        assertTrue(advice.reasonLabel.contains("米"))
    }

    @Test
    fun `已经在教室附近就不提醒出发`() {
        val courses = listOf(course("高等数学", "A-101", 3, 4))
        val plan = DeparturePlanner.plan(
            coursesToday = courses,
            slots = slots,
            nowMillis = at(9, 50),
            zone = zone,
            // 离教室约 22 米
            current = GeoPoint(26.0002, 119.0),
            roomLocations = rooms,
        )

        val advice = plan.advice!!
        assertEquals(DepartureReason.ALREADY_THERE, advice.reason)
        assertEquals(0, advice.leadMinutes)
        assertFalse("人已经在教室了，不该再提醒他出发", plan.shouldNotifyNow)
        assertTrue(advice.reasonLabel.contains("已经在教室"))
    }

    /**
     * 回退路径：一间教室都没采过坐标时必须和以前完全一样。
     *
     * 这条是整次改动的安全带 —— 新逻辑一旦在没坐标时也「自己算一个」，
     * 所有还没采过坐标的教室会同时失去提醒，而且看不出是哪儿出的问题。
     */
    @Test
    fun `教室没采过坐标时退回楼栋推断`() {
        val courses = listOf(
            course("高等数学", "A-101", 1, 2),
            course("大学物理", "C-305", 3, 4),
        )
        val advice = DeparturePlanner.plan(
            coursesToday = courses,
            slots = slots,
            nowMillis = at(9, 41),
            zone = zone,
            current = GeoPoint(26.01, 119.0),
            roomLocations = emptyMap(),
        ).advice!!

        assertEquals(DepartureReason.DIFFERENT_BUILDING, advice.reason)
        assertEquals(15, advice.leadMinutes)
        assertNull(advice.walkMinutes)
    }

    @Test
    fun `拿不到当前位置时退回楼栋推断`() {
        val courses = listOf(
            course("高等数学", "A-101", 1, 2),
            course("大学物理", "C-305", 3, 4),
        )
        val advice = DeparturePlanner.plan(
            coursesToday = courses,
            slots = slots,
            nowMillis = at(9, 41),
            zone = zone,
            current = null,
            roomLocations = rooms,
        ).advice!!

        assertEquals(DepartureReason.DIFFERENT_BUILDING, advice.reason)
        assertNull(advice.walkMinutes)
    }

    /**
     * (0, 0) 是「定位失败但返回了默认值」的典型输出。
     *
     * 当成真坐标会算出「你在几内亚湾」，步行时间上万分钟，提前量直接把提醒顶到天上去 ——
     * 而界面上只会显示一个荒唐的出发时刻，看不出根因。
     */
    @Test
    fun `零点坐标当作没拿到位置`() {
        val courses = listOf(
            course("高等数学", "A-101", 1, 2),
            course("大学物理", "C-305", 3, 4),
        )
        val advice = DeparturePlanner.plan(
            coursesToday = courses,
            slots = slots,
            nowMillis = at(9, 41),
            zone = zone,
            current = GeoPoint(0.0, 0.0),
            roomLocations = rooms,
        ).advice!!

        assertEquals(DepartureReason.DIFFERENT_BUILDING, advice.reason)
        assertNull(advice.walkMinutes)
    }

    @Test
    fun `按距离算时课间不够就一下课走`() {
        // 第 1 节 8:45 下课，第 2 节 8:50 上课
        val tightSlots = mapOf(
            1 to TimeSlot(1, 8 * 60, 8 * 60 + 45),
            2 to TimeSlot(2, 8 * 60 + 50, 9 * 60 + 35),
        )
        val courses = listOf(
            course("高等数学", "A-101", 1, 1),
            course("大学物理", "A-101", 2, 2),
        )
        val advice = DeparturePlanner.plan(
            coursesToday = courses,
            slots = tightSlots,
            nowMillis = at(8, 30),
            zone = zone,
            current = GeoPoint(26.01, 119.0),
            roomLocations = rooms,
        ).advice!!

        // 需要 24 分钟，课间只有 5 分钟 —— 下课就得走
        assertEquals(5, advice.gapMinutes)
        assertEquals(5, advice.leadMinutes)
        assertEquals("08:45", advice.leaveLabel)
    }
}
