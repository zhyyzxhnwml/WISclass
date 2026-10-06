package com.shangkele.core.common.week

import java.util.TreeSet

/**
 * 正方教务 `zcd` 周次描述字段解析器。
 *
 * 这是全项目最容易出 Bug 的地方，形态见 docs/03-教务系统对接.md §五：
 *
 * ```
 * "01-16"          -> 1..16
 * "1,3,5-9"        -> 1,3,5,6,7,8,9
 * "3-15周"         -> 3..15
 * "1-16(单)"       -> 1,3,5,7,9,11,13,15
 * "1-16(双)"       -> 2,4,6,8,10,12,14,16
 * "1-16(单),17-18" -> 1,3,5,7,9,11,13,15,17,18
 * null / ""        -> 1..totalWeeks
 * ```
 */
object WeekParser {

    const val DEFAULT_TOTAL_WEEKS = 20

    private val RANGE = Regex("""(\d{1,2})\s*[-~～—–]\s*(\d{1,2})""")
    private val NUMBER = Regex("""\d{1,2}""")
    private val ODD = Regex("""[（(]\s*单\s*[）)]""")
    private val EVEN = Regex("""[（(]\s*双\s*[）)]""")

    /**
     * 解析周次描述。
     * @param raw 教务系统原始文本（允许前后空白、全角字符、中文括号）
     * @param totalWeeks 学期总周数，超出范围的周次会被裁掉
     */
    fun parse(raw: String?, totalWeeks: Int = DEFAULT_TOTAL_WEEKS): Set<Int> {
        val text = normalize(raw)
        if (text.isBlank()) {
            return if (totalWeeks <= 0) emptySet() else (1..totalWeeks).toSet()
        }

        val collected = TreeSet<Int>()
        for (segment in text.split(',')) {
            if (segment.isBlank()) continue

            val oddOnly = ODD.containsMatchIn(segment)
            val evenOnly = EVEN.containsMatchIn(segment)
            val body = segment.replace(ODD, " ").replace(EVEN, " ")

            // 用等长空格标记已消费的区间，避免被下面的单点规则重复解析
            val remaining = StringBuilder(body)
            for (match in RANGE.findAll(body)) {
                val from = match.groupValues[1].toIntOrNull() ?: continue
                val to = match.groupValues[2].toIntOrNull() ?: continue
                val lo = minOf(from, to)
                val hi = maxOf(from, to)
                for (week in lo..hi) {
                    if (keep(week, oddOnly, evenOnly)) collected.add(week)
                }
                val start = remaining.indexOf(match.value)
                if (start >= 0) {
                    remaining.replace(start, start + match.value.length, " ".repeat(match.value.length))
                }
            }
            for (match in NUMBER.findAll(remaining)) {
                val week = match.value.toIntOrNull() ?: continue
                if (keep(week, oddOnly, evenOnly)) collected.add(week)
            }
        }

        val result = TreeSet<Int>()
        for (week in collected) {
            if (totalWeeks <= 0 || week in 1..totalWeeks) result.add(week)
        }
        return result
    }

    /** 指定周次是否落在该课程的周次集合内。 */
    fun occursInWeek(weeks: Set<Int>, week: Int): Boolean = weeks.contains(week)

    private fun keep(week: Int, oddOnly: Boolean, evenOnly: Boolean): Boolean = when {
        oddOnly && !evenOnly -> week % 2 == 1
        evenOnly && !oddOnly -> week % 2 == 0
        else -> true
    }

    /** 全角转半角、统一分隔符、去掉「周」字。 */
    private fun normalize(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val sb = StringBuilder(raw.length)
        for (ch in raw) {
            // 全角转半角（覆盖全角数字、全角减号、全角括号、全角逗号等）
            val half = when {
                ch.code in 0xFF01..0xFF5E -> (ch.code - 0xFEE0).toChar()
                ch.code == 0x3000 -> ' '
                else -> ch
            }
            val mapped = when (half) {
                ';', '、' -> ','
                '至' -> '-'
                '周' -> ' '
                else -> if (half.isWhitespace()) ' ' else half
            }
            sb.append(mapped)
        }
        return sb.toString().trim()
    }
}
