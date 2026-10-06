package com.shangkele.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 周次换算测试。
 *
 * 这里守的是一条**错一周就全盘错、而且不报错**的换算：
 * 周次决定「本周有没有这门课」，算错整张课表就是错的，
 * 而界面上除了周次数字本身没有任何线索。
 *
 * 核心规则：**第 1 周从开学那天开始，每天推 7 天一格，不按周一对齐。**
 * 学校 8/29（周六）开学 → 第 1 周 08-29 ~ 09-04，第 2 周 09-05 ~ 09-11。
 */
class WeekCalculatorTest {

    /** 2026-09-07 是周一，epochDay = 20703。 */
    private val monday20260907 = 20703L

    /** 2026-08-24 是周一，epochDay = 20689（比上面早两周）。 */
    private val monday20260824 = 20689L

    /** 2026-08-29 是**周六**，epochDay = 20694 —— 用户的真实开学日。 */
    private val saturday20260829 = 20694L

    // ---- 第 1 周从开学那天起算 ----

    @Test
    fun `第1周从开学当天开始而不是那一周的周一`() {
        // 8/29 是周六，第 1 周就必须从 8/29 开始，不能退到 8/24
        assertEquals(6, WeekCalculator.weekdayOf(saturday20260829)) // 周六
        assertEquals(saturday20260829, WeekCalculator.weekStartEpochDay(saturday20260829, 1))
        assertEquals("08-29 ~ 09-04", WeekCalculator.weekRangeLabel(saturday20260829, 1))
    }

    @Test
    fun `第N周就是开学日往后推 N-1 个七天`() {
        assertEquals(20701L, WeekCalculator.weekStartEpochDay(saturday20260829, 2)) // 09-05
        assertEquals("09-05 ~ 09-11", WeekCalculator.weekRangeLabel(saturday20260829, 2))
        // 第 6 周：8/29 + 35 天 = 10/03
        assertEquals(20729L, WeekCalculator.weekStartEpochDay(saturday20260829, 6))
        assertEquals("10-03 ~ 10-09", WeekCalculator.weekRangeLabel(saturday20260829, 6))
    }

    @Test
    fun `第1周的第7天仍在第1周`() {
        val end = WeekCalculator.weekEndEpochDay(saturday20260829, 1)
        assertEquals(20700L, end) // 09-04
        assertEquals(1, WeekCalculator.currentWeek(saturday20260829, end))
        // 第 8 天进入第 2 周
        assertEquals(2, WeekCalculator.currentWeek(saturday20260829, end + 1))
    }

    @Test
    fun `开学当天到今天正好落在第6周`() {
        // 2026-10-05 是周一，epochDay = 20731
        assertEquals(20731L, saturday20260829 + 37L)
        assertEquals(6, WeekCalculator.currentWeek(saturday20260829, 20731L))
    }

    @Test
    fun `哪怕开学日不是周一也不许跳到周一`() {
        // 这是之前真实出过的错：8/29 开学被算成 8/24 那一周，整张课表错位 5 天
        val start = saturday20260829
        val week1 = WeekCalculator.weekStartEpochDay(start, 1)
        assertEquals("第 1 周必须就是开学那天", start, week1)
        assertEquals(start, WeekCalculator.mondayOf(start) + 5L)
    }

    // ---- 当前周次 ----

    @Test
    fun `当前周次的计算与边界`() {
        // 开学当天就是第 1 周
        assertEquals(1, WeekCalculator.currentWeek(monday20260824, monday20260824))
        // 第 7 天仍属第 1 周
        assertEquals(1, WeekCalculator.currentWeek(monday20260824, monday20260824 + 6))
        // 第 8 天进入第 2 周
        assertEquals(2, WeekCalculator.currentWeek(monday20260824, monday20260824 + 7))
        // 开学前的日期夹到第 1 周，不倒着数出第 0 周
        assertEquals(1, WeekCalculator.currentWeek(monday20260824, monday20260824 - 10))
    }

    @Test
    fun `当前周次不会超出总周数`() {
        assertEquals(20, WeekCalculator.currentWeek(monday20260824, monday20260824 + 400L, 20))
    }

    @Test
    fun `总周数非法时按第1周处理`() {
        assertEquals(1, WeekCalculator.currentWeek(monday20260824, monday20260824 + 100L, 0))
    }

    // ---- 日期 ----

    @Test
    fun `日期区间标签格式正确`() {
        assertEquals("09-07 ~ 09-13", WeekCalculator.weekRangeLabel(monday20260907, 1))
        assertEquals("09-14 ~ 09-20", WeekCalculator.weekRangeLabel(monday20260907, 2))
    }

    @Test
    fun `日期区间跨月时也正确`() {
        // 从 08-24 起算的第6周 = 09-28 ~ 10-04
        assertEquals("09-28 ~ 10-04", WeekCalculator.weekRangeLabel(monday20260824, 6))
    }

    @Test
    fun `周次为0或负数不会算出荒唐的日期`() {
        assertEquals(monday20260824, WeekCalculator.weekStartEpochDay(monday20260824, 0))
        assertEquals(monday20260824, WeekCalculator.weekStartEpochDay(monday20260824, -5))
    }

    @Test
    fun `任意日期都能归到所在周的周一`() {
        // 20724 = 2026-09-28（周一）
        assertEquals(20724L, WeekCalculator.mondayOf(20724L))
        assertEquals(20724L, WeekCalculator.mondayOf(20725L)) // 周二
        assertEquals(20724L, WeekCalculator.mondayOf(20726L)) // 周三
        assertEquals(20724L, WeekCalculator.mondayOf(20730L)) // 周日
        // 下一个周一必须落到下一周，不能沿用本周
        assertEquals(20731L, WeekCalculator.mondayOf(20731L))
        // 哨兵值原样返回，避免未设置时被算成一个荒唐的日期
        assertEquals(0L, WeekCalculator.mondayOf(0L))
    }

    // ---- 星期 ----

    @Test
    fun `星期推算正确`() {
        assertEquals(1, WeekCalculator.weekdayOf(monday20260824))
        assertEquals(1, WeekCalculator.weekdayOf(monday20260907))
        assertEquals(6, WeekCalculator.weekdayOf(saturday20260829)) // 周六
        assertEquals(6, WeekCalculator.weekdayOf(20729L)) // 2026-10-03 周六
        assertEquals(7, WeekCalculator.weekdayOf(monday20260907 + 6)) // 周日
    }

    @Test
    fun `星期名称用于核对开学日期`() {
        assertEquals("周一", WeekCalculator.weekdayLabel(monday20260824))
        assertEquals("周六", WeekCalculator.weekdayLabel(saturday20260829))
        assertEquals("周六", WeekCalculator.weekdayLabel(20729L))
        assertEquals("周日", WeekCalculator.weekdayLabel(monday20260907 + 6))
    }

    // ---- 默认值 ----

    @Test
    fun `默认开学日期是2026年8月29日而不是某个周一`() {
        assertEquals("2026-08-29", WeekCalculator.fullDate(SchoolDefaults.DEFAULT_START_DATE_EPOCH_DAY))
        assertEquals(6, WeekCalculator.weekdayOf(SchoolDefaults.DEFAULT_START_DATE_EPOCH_DAY))
        // 旧的错误占位值必须和新值不同，否则「有没有被改回默认」就查不出来了
        assertEquals(true, SchoolDefaults.LEGACY_START_DATE_EPOCH_DAY != SchoolDefaults.DEFAULT_START_DATE_EPOCH_DAY)
        assertEquals("2026-09-07", WeekCalculator.fullDate(SchoolDefaults.LEGACY_START_DATE_EPOCH_DAY))
    }
}
