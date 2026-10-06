package com.shangkele.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeSlotValidatorTest {

    private fun slot(section: Int, start: Int, end: Int) = TimeSlot(section, start, end)

    @Test
    fun `默认作息表本身没有问题`() {
        assertTrue(TimeSlotValidator.validate(SchoolDefaults.TIME_SLOTS).isEmpty())
    }

    @Test
    fun `结束时间早于开始时间会被指出`() {
        val issues = TimeSlotValidator.validate(listOf(slot(1, 570, 555)))
        assertEquals(1, issues.size)
        assertEquals(1, issues.first().section)
        assertTrue(issues.first().message.contains("结束时间"))
    }

    @Test
    fun `开始与结束相同也算错`() {
        val issues = TimeSlotValidator.validate(listOf(slot(1, 570, 570)))
        assertEquals(1, issues.size)
    }

    @Test
    fun `两节时间重叠会被指出`() {
        val issues = TimeSlotValidator.validate(
            listOf(slot(1, 510, 560), slot(2, 555, 600)),
        )
        assertEquals(1, issues.size)
        assertEquals(2, issues.first().section)
        assertTrue(issues.first().message.contains("重叠"))
    }

    @Test
    fun `后一节比前一节还早会被指出`() {
        val issues = TimeSlotValidator.validate(
            listOf(slot(1, 600, 645), slot(2, 510, 555)),
        )
        assertEquals(1, issues.size)
        assertEquals(2, issues.first().section)
        assertTrue(issues.first().message.contains("还早"))
    }

    @Test
    fun `首尾相接不算重叠`() {
        val issues = TimeSlotValidator.validate(
            listOf(slot(1, 510, 555), slot(2, 555, 600)),
        )
        assertTrue(issues.isEmpty())
    }

    @Test
    fun `输入顺序打乱也能正确判断`() {
        val issues = TimeSlotValidator.validate(
            listOf(slot(3, 620, 665), slot(1, 510, 555), slot(2, 565, 610)),
        )
        assertTrue(issues.isEmpty())
    }

    @Test
    fun `空列表不报错`() {
        assertTrue(TimeSlotValidator.validate(emptyList()).isEmpty())
    }

    @Test
    fun `能取出第一节课开始与最后一节结束`() {
        val slots = listOf(slot(3, 620, 665), slot(1, 510, 555), slot(2, 565, 610))
        assertEquals(510, TimeSlotValidator.firstStartMinutes(slots))
        assertEquals(665, TimeSlotValidator.lastEndMinutes(slots))
    }

    @Test
    fun `默认作息上午从 0830 开始`() {
        assertEquals(8 * 60 + 30, TimeSlotValidator.firstStartMinutes(SchoolDefaults.TIME_SLOTS))
    }

    @Test
    fun `修正后的默认作息与旧的占位值不同`() {
        // 这条守着「自愈逻辑」的前提：两组值必须可区分，否则存量机器永远修不好
        assertTrue(SchoolDefaults.TIME_SLOTS != SchoolDefaults.LEGACY_PLACEHOLDER_TIME_SLOTS)
        assertEquals(12, SchoolDefaults.TIME_SLOTS.size)
        assertEquals(12, SchoolDefaults.LEGACY_PLACEHOLDER_TIME_SLOTS.size)
    }
}
