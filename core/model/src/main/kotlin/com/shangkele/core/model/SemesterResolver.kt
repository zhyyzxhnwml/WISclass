package com.shangkele.core.model

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * 学期推测。
 *
 * 用户不可能记得「第一周周一是哪天」，所以根据当前日期先猜一个，
 * 让他在导入页确认或改一下即可。
 *
 * 规则（按国内高校普遍校历）：
 *  - 8~12 月 → 本学年第一学期，第一周约在 9 月 1 日所在周
 *  - 1 月    → 仍算第一学期（期末周）
 *  - 2~7 月  → 上一学年第二学期，第一周约在 2 月下旬
 */
object SemesterResolver {

    data class Guess(
        val xnm: String,
        val xqm: String,
        val semesterName: String,
        val startDateEpochDay: Long,
        val totalWeeks: Int,
    )

    fun guess(today: LocalDate = LocalDate.now()): Guess {
        val year = today.year
        val month = today.monthValue

        return when {
            month >= 8 -> build(year.toString(), "3", LocalDate.of(year, 9, 1), today)
            month == 1 -> build((year - 1).toString(), "3", LocalDate.of(year - 1, 9, 1), today)
            else -> build((year - 1).toString(), "12", LocalDate.of(year, 2, 24), today)
        }
    }

    private fun build(xnm: String, xqm: String, anchor: LocalDate, today: LocalDate): Guess = Guess(
        xnm = xnm,
        xqm = xqm,
        semesterName = Semester.buildName(xnm, xqm),
        startDateEpochDay = mondayOf(anchor).toEpochDay(),
        totalWeeks = SchoolDefaults.SEMESTER_TOTAL_WEEKS,
    )

    private fun mondayOf(date: LocalDate): LocalDate =
        date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    /** 供导入页做「学年」下拉用。 */
    fun yearOptions(today: LocalDate = LocalDate.now()): List<String> {
        val year = today.year
        return listOf((year - 2).toString(), (year - 1).toString(), year.toString(), (year + 1).toString())
    }

    val TERM_OPTIONS: List<Pair<String, String>> = listOf(
        "3" to "第一学期",
        "12" to "第二学期",
    )
}
