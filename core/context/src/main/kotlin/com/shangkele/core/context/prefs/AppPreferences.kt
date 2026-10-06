package com.shangkele.core.context.prefs

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 应用级开关。
 *
 * 用量很小，暂时不引入 DataStore（docs/02-技术架构.md 里规划的是 DataStore，
 * 等 W4 存模型配置、学期校准这些结构化数据时再一次性迁过去）。
 */
@Singleton
class AppPreferences @Inject constructor(
    @ApplicationContext context: Context,
) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 上课自动开勿扰、下课恢复 */
    var autoSilenceEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_SILENCE, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_SILENCE, value).apply()

    /**
     * 「该出发了」提醒。
     *
     * 默认开：零配置方案（只看换楼与课间长度），不需要用户先标地图，
     * 所以没有理由默认关着。
     */
    var departureReminderEnabled: Boolean
        get() = prefs.getBoolean(KEY_DEPARTURE, true)
        set(value) = prefs.edit().putBoolean(KEY_DEPARTURE, value).apply()

    private companion object {
        const val PREFS_NAME = "app_prefs"
        const val KEY_AUTO_SILENCE = "auto_silence"
        const val KEY_DEPARTURE = "departure_reminder"
    }
}
