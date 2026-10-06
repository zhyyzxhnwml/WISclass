package com.shangkele.core.model

/**
 * 作息表的一节。
 *
 * 各节次起止时间是**硬编码风险点**：不同学校、不同校区都可能不同，
 * 因此这里只提供默认值，首启会引导用户校准（见 docs/06-功能清单与路线图.md P0-7）。
 *
 * @param startMinutes 距当日 00:00 的分钟数
 */
data class TimeSlot(
    val section: Int,
    val startMinutes: Int,
    val endMinutes: Int,
) {
    val durationMinutes: Int get() = (endMinutes - startMinutes).coerceAtLeast(0)

    fun startLabel(): String = formatMinutes(startMinutes)

    fun endLabel(): String = formatMinutes(endMinutes)

    fun rangeLabel(): String = "${startLabel()}-${endLabel()}"

    companion object {
        fun formatMinutes(minutes: Int): String {
            val h = (minutes / 60).coerceIn(0, 23)
            val m = (minutes % 60).coerceIn(0, 59)
            return "%02d:%02d".format(h, m)
        }
    }
}
