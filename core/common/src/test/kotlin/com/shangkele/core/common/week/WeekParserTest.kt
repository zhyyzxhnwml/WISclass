package com.shangkele.core.common.week

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 覆盖 docs/03-教务系统对接.md §五 的全部形态。
 * 这是 W1 的验收标准之一：这些用例必须全绿。
 */
class WeekParserTest {

    @Test
    fun `空值与空串返回全部周次`() {
        assertEquals((1..20).toSet(), WeekParser.parse(null))
        assertEquals((1..20).toSet(), WeekParser.parse(""))
        assertEquals((1..20).toSet(), WeekParser.parse("   "))
    }

    @Test
    fun `标准区间`() {
        assertEquals((1..16).toSet(), WeekParser.parse("01-16"))
        assertEquals((1..16).toSet(), WeekParser.parse("1-16"))
    }

    @Test
    fun `带周字后缀`() {
        assertEquals((3..15).toSet(), WeekParser.parse("3-15周"))
    }

    @Test
    fun `不连续周次`() {
        assertEquals(setOf(1, 3, 5, 6, 7, 8, 9), WeekParser.parse("1,3,5-9"))
    }

    @Test
    fun `单周`() {
        assertEquals(setOf(1, 3, 5, 7, 9, 11, 13, 15), WeekParser.parse("1-16(单)"))
    }

    @Test
    fun `双周`() {
        assertEquals(setOf(2, 4, 6, 8, 10, 12, 14, 16), WeekParser.parse("1-16(双)"))
    }

    @Test
    fun `全角括号与全角逗号`() {
        assertEquals(setOf(1, 3, 5, 7, 9, 11, 13, 15), WeekParser.parse("1-16（单）"))
        assertEquals(setOf(1, 3, 5, 6, 7, 8, 9), WeekParser.parse("1，3，5-9"))
    }

    @Test
    fun `单双周段与普通段混合`() {
        assertEquals(
            setOf(1, 3, 5, 7, 9, 11, 13, 15, 17, 18),
            WeekParser.parse("1-16(单),17-18"),
        )
    }

    @Test
    fun `全角数字与全角减号`() {
        assertEquals((1..16).toSet(), WeekParser.parse("０１－１６"))
    }

    @Test
    fun `波浪线与中文破折号`() {
        assertEquals((1..16).toSet(), WeekParser.parse("1~16"))
        assertEquals((1..16).toSet(), WeekParser.parse("1—16"))
        assertEquals((1..16).toSet(), WeekParser.parse("1至16"))
    }

    @Test
    fun `超出总周数被裁剪`() {
        assertEquals((1..20).toSet(), WeekParser.parse("01-30"))
        assertEquals(setOf(1, 2, 3), WeekParser.parse("1-10", totalWeeks = 3))
    }

    @Test
    fun `反序区间自动纠正`() {
        assertEquals((5..9).toSet(), WeekParser.parse("9-5"))
    }

    @Test
    fun `occursInWeek`() {
        assertTrue(WeekParser.occursInWeek(setOf(1, 3, 5), 3))
        assertFalse(WeekParser.occursInWeek(setOf(1, 3, 5), 4))
    }
}
