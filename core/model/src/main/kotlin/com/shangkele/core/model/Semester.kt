package com.shangkele.core.model

/**
 * 学期。
 *
 * @param xnm 学年码，如 "2026" 表示 2026-2027 学年
 * @param xqm 学期码：3 = 第一学期，12 = 第二学期，16 = 第三学期（短学期）
 * @param startDateEpochDay 第一周**周一**的 epochDay，用户校准后用于推算当前周次
 */
data class Semester(
    val id: Long = 0L,
    val xnm: String,
    val xqm: String,
    val name: String,
    val startDateEpochDay: Long,
    val totalWeeks: Int = 20,
    val isActive: Boolean = false,
) {
    /** 学期码 → 可读名称。 */
    fun termLabel(): String = when (xqm) {
        "3" -> "第一学期"
        "12" -> "第二学期"
        "16" -> "短学期"
        else -> "学期 $xqm"
    }

    companion object {
        /** 根据 xnm / xqm 生成展示名，如 "2026-2027学年第一学期"。 */
        fun buildName(xnm: String, xqm: String): String {
            val startYear = xnm.toIntOrNull()
            val yearLabel = if (startYear != null) "$startYear-${startYear + 1}学年" else "${xnm}学年"
            val term = when (xqm) {
                "3" -> "第一学期"
                "12" -> "第二学期"
                "16" -> "短学期"
                else -> "第${xqm}学期"
            }
            return yearLabel + term
        }
    }
}
