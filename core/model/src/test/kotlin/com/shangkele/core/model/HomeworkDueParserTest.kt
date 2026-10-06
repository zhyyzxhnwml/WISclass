package com.shangkele.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class HomeworkDueParserTest {

    private fun day(year: Int, month: Int, d: Int): Long =
        LocalDate.of(year, month, d).toEpochDay()

    /** 2026-10-06 是**周二**。 */
    private val tue = day(2026, 10, 6)

    // ---- 相对日 ----

    @Test
    fun `今天明天后天`() {
        assertEquals(tue, HomeworkDueParser.parse("今天交", tue))
        assertEquals(day(2026, 10, 7), HomeworkDueParser.parse("明天交", tue))
        assertEquals(day(2026, 10, 8), HomeworkDueParser.parse("后天交", tue))
        assertEquals(day(2026, 10, 9), HomeworkDueParser.parse("大后天交", tue))
    }

    @Test
    fun `刚才的某天不会算成截止`() {
        // 「昨天」不是截止时间；关键是不能因为含「天」就被判成「今天」
        assertNull(HomeworkDueParser.parse("昨天已经交过了", tue))
    }

    // ---- 周 ----

    @Test
    fun `本周几`() {
        // 本周一 = 10-05，本周五 = 10-09
        assertEquals(day(2026, 10, 9), HomeworkDueParser.parse("本周五之前交", tue))
        assertEquals(day(2026, 10, 7), HomeworkDueParser.parse("这周三交", tue))
    }

    @Test
    fun `下周几`() {
        // 下周一副 = 10-12，下周三 = 10-14
        assertEquals(day(2026, 10, 14), HomeworkDueParser.parse("下周三之前交", tue))
        assertEquals(day(2026, 10, 12), HomeworkDueParser.parse("下周一交", tue))
    }

    @Test
    fun `下下周`() {
        assertEquals(day(2026, 10, 19), HomeworkDueParser.parse("下下周一交", tue))
    }

    @Test
    fun `只说周几时取最近的那个`() {
        // 周二说「周五交」→ 本周五
        assertEquals(day(2026, 10, 9), HomeworkDueParser.parse("周五交", tue))
    }

    @Test
    fun `周几已经过去时要算到下一周`() {
        // 周六（10-10）说「周五交」—— 指下周五 10-16，而不是已经过去的 10-09
        val sat = day(2026, 10, 10)
        assertEquals(day(2026, 10, 16), HomeworkDueParser.parse("周五交", sat))
    }

    @Test
    fun `周日是这一周的最后一天`() {
        // 10-11 是周日，属于 10-05 那一自然周
        assertEquals(day(2026, 10, 11), HomeworkDueParser.parse("周日交", day(2026, 10, 6)))
        // 周二说「周一交」，已经过去了，取下一个周一
        assertEquals(day(2026, 10, 12), HomeworkDueParser.parse("周一交", tue))
    }

    @Test
    fun `下周不带周几时算下周一`() {
        assertEquals(day(2026, 10, 12), HomeworkDueParser.parse("下周交", tue))
    }

    @Test
    fun `周末`() {
        assertEquals(day(2026, 10, 11), HomeworkDueParser.parse("周末前交", tue))
    }

    /**
     * 「上周三」是已经发生的事。
     *
     * 不专门拦住的话，「周三」会被当成「下一个周三」，排出一个**整整晚一周**的日期 ——
     * 而且看上去完全正常。
     */
    @Test
    fun `上周不会被当成下周`() {
        assertNull(HomeworkDueParser.parse("上周三讲的那道题", tue))
    }

    // ---- 绝对日期 ----

    @Test
    fun `月日`() {
        assertEquals(day(2026, 10, 20), HomeworkDueParser.parse("10月20日之前交", tue))
        assertEquals(day(2026, 10, 20), HomeworkDueParser.parse("10月20号交", tue))
        assertEquals(day(2026, 11, 3), HomeworkDueParser.parse("11 月 3 日 交", tue))
    }

    @Test
    fun `年月日`() {
        assertEquals(
            day(2026, 12, 31),
            HomeworkDueParser.parse("2026-12-31 前提交", tue),
        )
    }

    @Test
    fun `跨年的月日算到明年`() {
        val dec = day(2026, 12, 20)
        // 12 月说「1 月 5 日交」显然指明年；算成今年就成了「已过期」
        assertEquals(day(2027, 1, 5), HomeworkDueParser.parse("1月5日交", dec))
    }

    @Test
    fun `刚过去不久的月日如实算成过去`() {
        // 10-06 说「9 月 28 日交」，只早 8 天，不该被挪到明年
        assertEquals(day(2026, 9, 28), HomeworkDueParser.parse("9月28日交", tue))
    }

    @Test
    fun `月底`() {
        assertEquals(day(2026, 10, 31), HomeworkDueParser.parse("月底之前交", tue))
        // 10-31 当天说「月底」还是 10-31；11-01 说就是 11-30
        assertEquals(day(2026, 11, 30), HomeworkDueParser.parse("月底交", day(2026, 11, 1)))
    }

    // ---- 说不准就不猜 ----

    /**
     * 这一组是刻意「解析不出来」的。
     *
     * 猜一个日期比不猜更糟：用户会照着错的日期准备，而界面上看不出任何异常。
     * 返回 null 时界面只显示老师的原话，用户自己知道该什么时候交。
     */
    @Test
    fun `说不准的一律不猜`() {
        assertNull(HomeworkDueParser.parse("期末之前交", tue))
        assertNull(HomeworkDueParser.parse("最后一次课交", tue))
        assertNull(HomeworkDueParser.parse("这章课后练习做了", tue))
        assertNull(HomeworkDueParser.parse("尽量早点交", tue))
        assertNull(HomeworkDueParser.parse("", tue))
        assertNull(HomeworkDueParser.parse(null, tue))
        // 非法日期不能崩，只能返回 null
        assertNull(HomeworkDueParser.parse("2月30日交", tue))
        assertNull(HomeworkDueParser.parse("13月1日交", tue))
    }
}
