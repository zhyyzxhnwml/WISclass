package com.shangkele.core.context.silence

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.shangkele.core.context.departure.DepartureReminderScheduler
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 「课表节拍」广播接收器。
 *
 * 同时承担两件事的边界触发：
 *  - 上课静音的开关（[ClassSilenceScheduler]）
 *  - 「该出发了」的提醒（[DepartureReminderScheduler]）
 *
 * 两者都是链式重算：闹钟响了就把两件事都重算一遍，各自排出自己的下一个边界。
 * 这样任何一个被系统清掉，另一次触发都能顺手把它接回来。
 *
 * 另外还监听开机与应用更新 —— 重启后链条要重新接上。
 * `goAsync()` 是为了在 `onReceive` 返回后还能继续读数据库。
 */
@AndroidEntryPoint
class ClassSilenceReceiver : BroadcastReceiver() {

    @Inject
    lateinit var silenceScheduler: ClassSilenceScheduler

    @Inject
    lateinit var departureScheduler: DepartureReminderScheduler

    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                silenceScheduler.reschedule()
            } catch (e: Exception) {
                // 广播里不能崩；下次开 App 或下个边界会重排
            }
            try {
                departureScheduler.reschedule()
            } catch (e: Exception) {
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_RECALCULATE = "com.shangkele.action.RECALCULATE_SILENCE"
    }
}
