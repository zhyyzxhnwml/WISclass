package com.shangkele.core.common.week

/**
 * 解析正方教务 `jc` 节次串。
 *
 * ```
 * "0102" -> 1..2      "0304" -> 3..4      "1112" -> 11..12
 * "05"   -> 5..5      "1-2"  -> 1..2      "0103" -> 1..3（连堂实验）
 * ```
 */
object SectionCodeParser {

    private val RANGE = Regex("""(\d{1,2})\s*[-~～—–]\s*(\d{1,2})""")

    fun parse(raw: String?): IntRange? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null

        RANGE.find(text)?.let { match ->
            val a = match.groupValues[1].toIntOrNull() ?: return null
            val b = match.groupValues[2].toIntOrNull() ?: return null
            if (a <= 0 || b <= 0) return null
            return minOf(a, b)..maxOf(a, b)
        }

        val digits = text.filter { it.isDigit() }
        return when (digits.length) {
            4 -> {
                val a = digits.substring(0, 2).toIntOrNull() ?: return null
                val b = digits.substring(2, 4).toIntOrNull() ?: return null
                if (a <= 0 || b <= 0 || a > b) null else a..b
            }
            2, 1 -> digits.toIntOrNull()?.takeIf { it > 0 }?.let { it..it }
            else -> null
        }
    }
}
