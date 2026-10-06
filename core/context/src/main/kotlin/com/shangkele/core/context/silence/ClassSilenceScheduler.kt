package com.shangkele.core.context.silence

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.shangkele.core.context.dnd.DndController
import com.shangkele.core.context.prefs.AppPreferences
import com.shangkele.core.database.repository.ScheduleRepository
import com.shangkele.core.model.WeekCalculator
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 上课自动静音的调度器。
 *
 * 不做「一次性排完整学期的闹钟」——那样会有几百个 PendingIntent，而且课表一变就全废。
 * 改成**链式重算**：每次只排下一个边界（某节课开始或结束），闹钟响了再算下一个。
 * 一旦课表变化，只要调一次 [reschedule] 就全对上了。
 */
@Singleton
class ClassSilenceScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: ScheduleRepository,
    private val dnd: DndController,
    private val prefs: AppPreferences,
) {

    suspend fun reschedule(nowMillis: Long = System.currentTimeMillis()): ClassSilencePlan? {
        if (!prefs.autoSilenceEnabled) {
            // 关掉开关时要把我们开过的静音还回去，不能就这么留着
            dnd.restore()
            cancelAlarm()
            return null
        }

        val semester = repository.getActiveSemester() ?: return null
        val courses = repository.getCourses(semester.id)
        val slots = repository.getTimeSlots(semester.id).associateBy { it.section }

        val today = Instant.ofEpochMilli(nowMillis).atZone(ZoneId.systemDefault()).toLocalDate()
        val todayEpochDay = today.toEpochDay()
        val week = WeekCalculator.currentWeek(
            semester.startDateEpochDay,
            todayEpochDay,
            semester.totalWeeks,
        )
        val weekday = WeekCalculator.weekdayOf(todayEpochDay)
        val coursesToday = courses.filter { it.weekday == weekday && it.occursInWeek(week) }

        val plan = SilencePlanner.plan(coursesToday, slots, nowMillis)
        dnd.setSilent(plan.shouldBeSilent)
        plan.nextBoundaryAtMillis?.let { scheduleAt(it) }
        return plan
    }

    private fun scheduleAt(triggerAtMillis: Long) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = pendingIntent()

        try {
            val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                alarmManager.canScheduleExactAlarms()
            if (canExact) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pending,
                )
            } else {
                // 没拿到精确闹钟权限就退到不精确，最多晚几分钟，
                // 对「上课静音」来说是可接受的降级
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pending,
                )
            }
        } catch (e: SecurityException) {
            // 权限被撤销的瞬间可能抛，忽略即可：下次开 App 会重排
        }
    }

    fun cancelAlarm() {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        alarmManager.cancel(pendingIntent())
    }

    private fun pendingIntent(): PendingIntent {
        val intent = Intent(context, ClassSilenceReceiver::class.java)
            .setAction(ClassSilenceReceiver.ACTION_RECALCULATE)
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private companion object {
        const val REQUEST_CODE = 0x534B
    }
}
