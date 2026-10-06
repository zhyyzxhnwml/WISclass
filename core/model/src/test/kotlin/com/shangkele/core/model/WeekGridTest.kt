package com.shangkele.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 列顺序测试。
 *
 * 这条如果错了，**课程会被画到错误的列上**（周三的课画到周一那格），
 * 而且格子本身看起来完全正常 —— 只有对着教务系统才能发现。
 */
class WeekGridTest {

    @Test
    fun `周一开学时列顺序就是周一到周日`() {
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7), WeekGrid.columnWeekdays(1))
        assertEquals(
            listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日"),
            WeekGrid.columnLabels(1),
        )
    }

    @Test
    fun `周六开学时列顺序从周六开始`() {
        // 8/29 开学（周六）→ 第 1 周就是 周六 周日 周一 周二 周三 周四 周五
        assertEquals(listOf(6, 7, 1, 2, 3, 4, 5), WeekGrid.columnWeekdays(6))
        assertEquals(
            listOf("周六", "周日", "周一", "周二", "周三", "周四", "周五"),
            WeekGrid.columnLabels(6),
        )
    }

    @Test
    fun `七列恰好覆盖星期一到星期日各一次`() {
        for (start in 1..7) {
            val columns = WeekGrid.columnWeekdays(start)
            assertEquals("start=$start 必须正好七列", 7, columns.size)
            assertEquals("start=$start 不能重复", (1..7).toSet(), columns.toSet())
        }
    }

    @Test
    fun `周六开学时周一的课落在第3列`() {
        // 第 1 周： 周六(0) 周日(1) 周一(2) 周二(3) …
        assertEquals(2, WeekGrid.columnOf(1, startWeekday = 6))
        assertEquals(0, WeekGrid.columnOf(6, startWeekday = 6))
        assertEquals(6, WeekGrid.columnOf(5, startWeekday = 6))
    }

    @Test
    fun `列号与列头文字一一对应`() {
        // 用 2026-10-05（周一）所在的第 6 周验证：8/29 开学 + 35 天 = 10/03（周六）
        val start = SchoolDefaults.DEFAULT_START_DATE_EPOCH_DAY // 2026-08-29 周六
        val week6Start = WeekCalculator.weekStartEpochDay(start, 6)
        val labels = WeekGrid.columnLabels(WeekCalculator.weekdayOf(start))

        (0..6).forEach { column ->
            val day = week6Start + column
            val expectedLabel = labels[column]
            val actualLabel = WeekCalculator.weekdayLabel(day)
            assertEquals("第 $column 列（$day）", expectedLabel, actualLabel)
        }
    }

    @Test
    fun `非法输入被夹到合法范围`() {
        assertEquals(WeekGrid.columnWeekdays(1), WeekGrid.columnWeekdays(0))
        assertEquals(WeekGrid.columnWeekdays(7), WeekGrid.columnWeekdays(99))
        assertEquals(0, WeekGrid.columnOf(weekday = 0, startWeekday = 1))
    }
}
