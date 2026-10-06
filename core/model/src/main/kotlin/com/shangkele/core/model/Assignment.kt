package com.shangkele.core.model

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 从课堂内容里抽出来的**作业 / 待办**（录音转写里老师布置的，或板书照片上写的）。
 * 见 docs/05-数据模型.md §2.9。
 *
 * [dueEpochDay] 和 [dueRawText] 是**一对**，不要只留一个：
 *
 *  - [dueRawText] 是老师的原话（「下周三之前交」），必须原样留着；
 *  - [dueEpochDay] 是把那句话换算成的日期，可能算错，也可能算不出来（为 null）。
 *
 * 两个并排显示，用户扫一眼就能发现有没有理解错。只留日期的话，
 * 算错了他没有任何办法察觉 —— 而这正是「AI 帮忙记作业」最不能出的错。
 */
data class Assignment(
    val id: Long = 0L,
    val courseId: Long? = null,
    /** 从哪条笔记（哪次录音 / 哪张照片）抽出来的。 */
    val noteId: Long? = null,
    val title: String,
    val dueEpochDay: Long? = null,
    /** 老师的原话，如「下周三前」。 */
    val dueRawText: String? = null,
    /** 模型自评的把握，0~1。 */
    val confidence: Float = 0f,
    val done: Boolean = false,
    val remindAtMs: Long? = null,
) {

    /** 「下周三前 → 10-14」。**
     *
     *  日期和原话都摊开写，是为了让用户能自己核对 —— 这是这类「AI 猜时间」
     *  的功能唯一可靠的自检方式。
     */
    val dueLabel: String
        get() {
            val raw = dueRawText?.takeIf { it.isNotBlank() }
            val day = dueEpochDay?.let { DATE.format(LocalDate.ofEpochDay(it)) }
            return when {
                raw != null && day != null -> "$raw → $day"
                day != null -> day
                raw != null -> raw
                else -> "没写时间"
            }
        }

    /**
     * 距离截止还有几天。负数表示已经过期，null 表示没有日期。
     *
     * 界面上据此说「还有 3 天」或「已过期 2 天」，
     * 比单纯摆一个日期更容易让人反应过来。
     */
    fun daysLeft(todayEpochDay: Long): Long? = dueEpochDay?.let { it - todayEpochDay }

    private companion object {
        val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd")
    }
}
