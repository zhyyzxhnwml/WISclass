package com.shangkele.core.model

import java.time.DayOfWeek
import java.time.LocalDate

/**
 * 把老师那句时间说法（「下周三之前交」「10 月 20 日」）解析成**截止日**。
 *
 * 这是整条「AI 帮忙记作业」链路里最容易错、也最不能错的一步：
 * 日期排错，用户会照着错的日期准备；而排不出来只是「没排」，一眼就看得见。
 * 所以这里守两条：
 *
 *  1. **说不准就返回 null，绝不猜一个大概的日期**；
 *  2. 结果永远和老师的原话一起显示（见 [Assignment.dueLabel]），错了能立刻看出来。
 *
 * 「本周 / 下周」按**自然周**算（周一为一周之始），与课表的「第 N 周」无关 ——
 * 课表那套是从开学日起算的，两者不是一回事。
 */
object HomeworkDueParser {

    /** 周几 → 1..7（周一为 1）。「日」「天」都是周日。 */
    private val WEEKDAYS = mapOf(
        '一' to 1, '二' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '日' to 7, '天' to 7,
    )

    /** 「下下周三」这类前缀 → 相对**本周**要挪几周。 */
    private val WEEK_PREFIX = mapOf(
        "下下" to 2L,
        "下" to 1L,
        "本" to 0L,
        "这" to 0L,
        "该" to 0L,
    )

    private val WEEK_DAY = Regex("""(下下|下|本|这|该)?\s*周\s*([一二三四五六日天])""")
    private val WEEK_ONLY = Regex("""(下下|下|本|这|该)\s*周(?!\s*[一二三四五六日天])""")
    private val WEEKEND = Regex("""周\s*末""")
    private val MONTH_DAY = Regex("""(\d{1,2})\s*月\s*(\d{1,2})\s*[日号]""")
    private val ISO_DATE = Regex("""(\d{4})\s*[-/.]\s*(\d{1,2})\s*[-/.]\s*(\d{1,2})""")

    /**
     * 一个「月日」比今天早出这么多天时，认为它说的是**明年**。
     *
     * 12 月说「1 月 5 日交」显然指明年；而 10 月说「9 月 28 日交」就是已经过期了，
     * 该如实算成过去的日期，不能悄悄挪到明年。
     */
    private const val STALE_DAYS = 30L

    /**
     * @param rawText 老师的原话，例如「下周三之前交」
     * @param todayEpochDay 今天。所有相对说法都以它为基准
     * @return 截止日的 epochDay；说不准就返回 null
     */
    fun parse(rawText: String?, todayEpochDay: Long): Long? {
        val text = rawText?.trim().orEmpty()
        if (text.isEmpty()) return null

        // 「上周三」是已经过去的事，不是截止时间。
        // 不拦住的话，「周三」会被当成「下一个周三」，排出一个整整晚一周的日期
        if (text.contains("上周")) return null

        return dayOffset(text, todayEpochDay)
            ?: weekBased(text, todayEpochDay)
            ?: monthEnd(text, todayEpochDay)
            ?: absolute(text, todayEpochDay)
    }

    /** 「今天 / 明天 / 后天 / 大后天」。 */
    private fun dayOffset(text: String, today: Long): Long? = when {
        // 大后天必须先判，否则会被「后天」先命中，少算一天
        text.contains("大后天") -> today + 3
        text.contains("后天") -> today + 2
        text.contains("明天") -> today + 1
        text.contains("今天") || text.contains("今晚") -> today
        else -> null
    }

    /** 「本周五 / 下周三 / 下下周 / 周五 / 周末」。 */
    private fun weekBased(text: String, today: Long): Long? {
        val monday = mondayOfWeek(today)

        WEEKEND.find(text)?.let {
            val sunday = monday + 6
            return if (sunday >= today) sunday else sunday + 7
        }

        WEEK_DAY.find(text)?.let { match ->
            val weekday = WEEKDAYS[match.groupValues[2].first()] ?: return null
            val weeks = WEEK_PREFIX[match.groupValues[1]] ?: 0L
            val day = monday + weeks * 7 + (weekday - 1)
            // 只说「周五交」而今天已是周六时，指的是下一个周五。
            // 「本周五」若也已经过去，同样往后挪 —— 排到过去没有任何意义。
            return if (day >= today) day else day + 7
        }

        WEEK_ONLY.find(text)?.let { match ->
            val weeks = WEEK_PREFIX[match.groupValues[1]] ?: 0L
            val day = monday + weeks * 7
            return if (day >= today) day else day + 7
        }

        return null
    }

    /** 「月底 / 月末」。 */
    private fun monthEnd(text: String, today: Long): Long? {
        if (!text.contains("月底") && !text.contains("月末")) return null
        val date = LocalDate.ofEpochDay(today)
        val thisEnd = date.withDayOfMonth(date.lengthOfMonth())
        if (thisEnd.toEpochDay() >= today) return thisEnd.toEpochDay()
        // 这个月的月底已经过了，那就是下个月月底
        val next = date.plusMonths(1)
        return next.withDayOfMonth(next.lengthOfMonth()).toEpochDay()
    }

    /** 「2026-10-20」「10 月 20 日」「10月20号」。 */
    private fun absolute(text: String, today: Long): Long? {
        ISO_DATE.find(text)?.let { match ->
            val (year, month, day) = match.destructured
            return dateOrNull(year.toInt(), month.toInt(), day.toInt())
        }

        MONTH_DAY.find(text)?.let { match ->
            val month = match.groupValues[1].toIntOrNull() ?: return null
            val day = match.groupValues[2].toIntOrNull() ?: return null
            val year = LocalDate.ofEpochDay(today).year

            val sameYear = dateOrNull(year, month, day) ?: return null
            if (sameYear >= today - STALE_DAYS) return sameYear

            // 已经过去太久了，说明说的是明年（12 月说「1 月 5 日交」）
            return dateOrNull(year + 1, month, day)
        }

        return null
    }

    /** 非法日期（2 月 30 日、13 月）返回 null，而不是抛异常。 */
    private fun dateOrNull(year: Int, month: Int, day: Int): Long? =
        runCatching { LocalDate.of(year, month, day).toEpochDay() }.getOrNull()

    /** 今天所在**自然周**的周一（ISO 周，周一为始）。 */
    private fun mondayOfWeek(epochDay: Long): Long =
        LocalDate.ofEpochDay(epochDay).with(DayOfWeek.MONDAY).toEpochDay()
}
