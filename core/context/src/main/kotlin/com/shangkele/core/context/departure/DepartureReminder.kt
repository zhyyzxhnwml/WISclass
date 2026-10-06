package com.shangkele.core.context.departure

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.shangkele.core.context.R
import com.shangkele.core.context.prefs.AppPreferences
import com.shangkele.core.context.silence.ClassSilenceReceiver
import com.shangkele.core.database.repository.ScheduleRepository
import com.shangkele.core.model.WeekCalculator
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** 发出「该出发了」通知。 */
@Singleton
class DepartureNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun notifyDeparture(advice: DepartureAdvice) {
        if (!canPostNotifications()) return
        ensureChannel()

        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val contentIntent = launchIntent?.let {
            PendingIntent.getActivity(
                context,
                0,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("该出发了")
            .setContentText("${advice.startLabel} ${advice.roomLabel} · ${advice.courseName}")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "${advice.startLabel} 在 ${advice.roomLabel} 上「${advice.courseName}」。\n" +
                        advice.reasonLabel,
                ),
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .apply { contentIntent?.let { setContentIntent(it) } }
            .build()

        try {
            manager.notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // 权限被撤的瞬间可能抛，忽略
        }
    }

    private fun canPostNotifications(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "上课提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "该出发了、课程开始提醒"
            },
        )
    }

    private companion object {
        const val CHANNEL_ID = "class_reminder"
        const val NOTIFICATION_ID = 0x534B01
    }
}

/**
 * 「该出发了」的闹钟调度。
 *
 * 和上课静音一样用**链式重算**：一次只排下一个边界（出发时刻或上课时刻），
 * 响了再算下一个。课表一变，只要重排一次就全对上。
 */
@Singleton
class DepartureReminderScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: ScheduleRepository,
    private val notifier: DepartureNotifier,
    private val prefs: AppPreferences,
) {

    suspend fun reschedule(nowMillis: Long = System.currentTimeMillis()): DeparturePlan? {
        if (!prefs.departureReminderEnabled) {
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

        val plan = DeparturePlanner.plan(coursesToday, slots, nowMillis)
        if (plan.shouldNotifyNow) {
            plan.advice?.let { notifier.notifyDeparture(it) }
        }
        plan.nextBoundaryAtMillis?.let { scheduleAt(it) }
        return plan
    }

    fun cancelAlarm() {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        alarmManager.cancel(pendingIntent())
    }

    private fun scheduleAt(triggerAtMillis: Long) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = pendingIntent()
        try {
            val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                alarmManager.canScheduleExactAlarms()
            if (canExact) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pending)
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pending)
            }
        } catch (e: SecurityException) {
            // 权限撤销瞬间可能抛；忽略，下次开 App 会重排
        }
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
        const val REQUEST_CODE = 0x534B02
    }
}
