package com.shangkele.core.context.dnd

import android.app.NotificationManager
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 勿扰模式控制。
 *
 * 有一个容易踩的坑：**别把用户自己开的勿扰给关掉**。
 * 所以这里记录「静音是不是我们开的」，只有是我们开的，恢复时才动手。
 */
@Singleton
class DndController @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun manager(): NotificationManager? =
        context.getSystemService(NotificationManager::class.java)

    fun isAccessGranted(): Boolean = try {
        manager()?.isNotificationPolicyAccessGranted == true
    } catch (e: Exception) {
        false
    }

    /** 当前是否处于「完全静音」。 */
    fun isSilentNow(): Boolean = try {
        manager()?.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_NONE
    } catch (e: Exception) {
        false
    }

    /**
     * 切换静音。
     * @return true 表示状态确实被改变了
     */
    fun setSilent(silent: Boolean): Boolean {
        val manager = manager() ?: return false
        if (!isAccessGranted()) return false

        val currentlySilent = isSilentNow()
        if (silent == currentlySilent) return false

        return try {
            if (silent) {
                // 进入静音前记一笔：不是我们开的就别认领
                prefs.edit().putBoolean(KEY_OWNED_BY_US, !currentlySilent).apply()
                manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
            } else {
                if (!prefs.getBoolean(KEY_OWNED_BY_US, false)) return false
                manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
                prefs.edit().putBoolean(KEY_OWNED_BY_US, false).apply()
            }
            true
        } catch (e: SecurityException) {
            false
        } catch (e: Exception) {
            false
        }
    }

    /** 关掉自动静音时调用：只恢复我们当初开的静音。 */
    fun restore(): Boolean = setSilent(false)

    private companion object {
        const val PREFS_NAME = "dnd_state"
        const val KEY_OWNED_BY_US = "owned_by_us"
    }
}
