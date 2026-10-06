package com.shangkele.core.context.prefs

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 记录「校历有没有被用户确认过」。
 *
 * **为什么需要这个标记**：第一周是哪天只有学校/教务系统知道，
 * 任何默认值都是猜。之前我猜过两次、错了两次，每次都会让
 * 「本周有没有这门课」全判错 —— 而且界面上除了日期区间没有任何线索。
 *
 * 所以现在不猜了：没确认过就在课表页挂一条提示，让用户自己定。
 * 这个标记只影响「要不要提示」，不参与任何计算。
 *
 * 用 SharedPreferences 而不是加数据库字段：加字段要走 Room 迁移，
 * 而这个标记丢了最坏结果只是多提示一次，不值得引入一次迁移。
 */
@Singleton
class CalendarCalibrationStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 用户是否明确设置过第一周周一。 */
    fun isConfirmed(): Boolean = prefs.getBoolean(KEY_CONFIRMED, false)

    /**
     * 标记为已确认。
     *
     * 注意：**换学期后应当重新确认**，因为第一周周一变了。
     * 传入学期 id，id 变了就视为未确认。
     */
    fun markConfirmed(semesterId: Long) {
        prefs.edit()
            .putBoolean(KEY_CONFIRMED, true)
            .putLong(KEY_SEMESTER_ID, semesterId)
            .apply()
    }

    /** 指定学期是否已被确认过。换学期（id 变化）后自动回到未确认。 */
    fun isConfirmedFor(semesterId: Long): Boolean {
        if (semesterId <= 0L) return false
        return prefs.getBoolean(KEY_CONFIRMED, false) &&
            prefs.getLong(KEY_SEMESTER_ID, -1L) == semesterId
    }

    private companion object {
        const val PREFS_NAME = "calendar_calibration"
        const val KEY_CONFIRMED = "confirmed"
        const val KEY_SEMESTER_ID = "semester_id"
    }
}
