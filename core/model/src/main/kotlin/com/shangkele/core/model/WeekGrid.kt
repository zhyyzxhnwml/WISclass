package com.shangkele.core.model

/**
 * 课表网格的**列顺序**。
 *
 * 列不再固定是「周一 → 周日」：第 1 周从周六开学时，格子里就该是
 * 周六、周日、周一、…、周五 —— 那一周的 7 天本来就长这样。
 *
 * 关键约束：这里和 [WeekCalculator.weekStartEpochDay] **必须用同一个锚
 * （开学日）**。周次按开学日推、列顺序却按周一排，日子和课程就会对错格子，
 * 而且两边各自看都「没错」，非常难查。
 */
object WeekGrid {

    /** 从 [startWeekday]（1 = 周一 … 7 = 周日）开始的七列，返回每列对应的 weekday。 */
    fun columnWeekdays(startWeekday: Int): List<Int> {
        val start = startWeekday.coerceIn(1, 7)
        return (0..6).map { ((start - 1 + it) % 7) + 1 }
    }

    /** 从 [startWeekday] 开始的七列表头文字，如 `[周六, 周日, 周一, …]`。 */
    fun columnLabels(startWeekday: Int): List<String> =
        columnWeekdays(startWeekday).map { SchoolDefaults.WEEKDAY_LABELS[it - 1] }

    /** [weekday]（1 = 周一 … 7 = 周日）落在第几列（0..6）。 */
    fun columnOf(weekday: Int, startWeekday: Int): Int {
        val start = startWeekday.coerceIn(1, 7)
        val day = weekday.coerceIn(1, 7)
        return (day - start + 7) % 7
    }
}
