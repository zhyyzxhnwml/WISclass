package com.shangkele.core.model

import java.time.LocalDate

/**
 * 周次与星期的换算。
 *
 * ## 唯一必须守住的规则：第 1 周从**开学那天**开始，不按周一对齐
 *
 * 学校 8 月 29 日（周六）开学，第 1 周就是 08-29 ~ 09-04，第 2 周 09-05 ~ 09-11，
 * 一格一格往后推 7 天。教务系统也是这么显示的 —— 跟「周一」没有关系，
 * 只要这 7 天把星期一到星期日各覆盖一次，它就是一个「周」。
 *
 * 之前这里把周锚定在「开学日所在那一周的周一」上：8/29 开学被算成 8/24 那一周，
 * 第 1 周整体错开 5 天，之后每一周的「本周有没有这门课」全部跟着错 ——
 * 而界面上只有一个安静的日期区间，看不出来。
 *
 * 依赖 java.time，因此 minSdk 必须 ≥ 26（本项目定为 26）。
 */
object WeekCalculator {

    /** epochDay → 星期（1 = 周一 … 7 = 周日）。 */
    fun weekdayOf(epochDay: Long): Int = LocalDate.ofEpochDay(epochDay).dayOfWeek.value

    private val WEEKDAY_NAMES = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

    /** epochDay → "周六"。用于让用户核对开学日期填得对不对。 */
    fun weekdayLabel(epochDay: Long): String = WEEKDAY_NAMES[weekdayOf(epochDay) - 1]

    /** 由开学日期推算当前是第几周，结果夹在 [1, totalWeeks] 内。 */
    fun currentWeek(
        semesterStartEpochDay: Long,
        todayEpochDay: Long,
        totalWeeks: Int = SchoolDefaults.SEMESTER_TOTAL_WEEKS,
    ): Int {
        if (totalWeeks <= 0) return 1
        val diff = todayEpochDay - semesterStartEpochDay
        // 开学前一律算第 1 周，不倒着数出「第 0 周」「第 -3 周」
        if (diff < 0) return 1
        return ((diff / 7).toInt() + 1).coerceIn(1, totalWeeks)
    }

    /** 第 N 周第一天的 epochDay。**以开学日为锚，不跳到周一。** */
    fun weekStartEpochDay(semesterStartEpochDay: Long, week: Int): Long =
        semesterStartEpochDay + (week.coerceAtLeast(1) - 1) * 7L

    /** 第 N 周最后一天的 epochDay。 */
    fun weekEndEpochDay(semesterStartEpochDay: Long, week: Int): Long =
        weekStartEpochDay(semesterStartEpochDay, week) + 6L

    /** epochDay → "MM-dd" 展示。 */
    fun shortDate(epochDay: Long): String {
        val date = LocalDate.ofEpochDay(epochDay)
        return "%02d-%02d".format(date.monthValue, date.dayOfMonth)
    }

    /** epochDay → "2026-08-29" 展示，用于校历设置。 */
    fun fullDate(epochDay: Long): String = LocalDate.ofEpochDay(epochDay).toString()

    /**
     * 某一周的日期区间，如 `08-29 ~ 09-04`。
     *
     * **这个标签是校历正确性的唯一可见证据。** 校历错一周时，
     * 界面上除了周次数字之外没有任何线索能让人发现 —— 而周次数字本身
     * 就是错的，用户只会觉得「感觉不对」。把区间摆出来，一眼就能对照。
     */
    fun weekRangeLabel(semesterStartEpochDay: Long, week: Int): String {
        val from = weekStartEpochDay(semesterStartEpochDay, week)
        return "${shortDate(from)} ~ ${shortDate(from + 6)}"
    }

    /** 某个日期所在周的周一。未设置的哨兵值（≤ 0）原样返回。 */
    fun mondayOf(epochDay: Long): Long =
        if (epochDay <= 0L) epochDay else epochDay - (weekdayOf(epochDay) - 1)
}
