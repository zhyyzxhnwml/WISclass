package com.shangkele.app

import android.app.Application
import androidx.glance.appwidget.updateAll
import com.shangkele.app.widget.TodayScheduleWidget
import com.shangkele.core.context.departure.DepartureReminderScheduler
import com.shangkele.core.context.prefs.CalendarCalibrationStore
import com.shangkele.core.database.repository.ScheduleRepository
import com.shangkele.core.model.SemesterEdit
import com.shangkele.core.context.recording.ClassNoteRecorder
import com.shangkele.core.context.silence.ClassSilenceScheduler
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class ShangKeLeApp : Application() {

    @Inject
    lateinit var silenceScheduler: ClassSilenceScheduler

    @Inject
    lateinit var departureScheduler: DepartureReminderScheduler

    @Inject
    lateinit var classNoteRecorder: ClassNoteRecorder

    @Inject
    lateinit var repository: ScheduleRepository

    @Inject
    lateinit var calibrationStore: CalendarCalibrationStore

    override fun onCreate() {
        super.onCreate()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            // 静音和出发提醒都是「链式重算」：一次只排下一个边界，响了再算下一个。
            // 链条一旦被系统清掉（重启、被强杀），靠这里重新接上。
            runCatching { silenceScheduler.reschedule() }
            runCatching { departureScheduler.reschedule() }
            // 录音期间进程被 MagicOS 杀掉（或历史版本走了错误的结束路径）会留下
            // 永远停在「录音中」的笔记，删不掉也转写不了。每次启动收尾一次。
            runCatching { classNoteRecorder.recoverOrphanedNotes() }
            runCatching { healPlaceholderStartDate() }
            // 小组件只靠系统的 30 分钟周期刷新会偏旧，每次开 App 顺手刷一遍
            runCatching { TodayScheduleWidget().updateAll(this@ShangKeLeApp) }
        }
    }

    /**
     * 把早期写死的占位校历换成真实开学日期。
     *
     * 这里和之前删掉的那版「校历自愈」有本质区别：那版是拿一个**猜测值**去覆盖，
     * 猜错就让整张课表的本周判定全错；这版只在**库里恰好还是我早期随手写的
     * 那个占位值、并且你从没设过**时才动手（规则见 [SemesterEdit.shouldHealPlaceholder]）。
     * 你只要设过一次，它就匹配不上。
     *
     * 不做这一步的后果：已经装过的机器库里一直留着占位值，光改常量永远修不好。
     */
    private suspend fun healPlaceholderStartDate() {
        val semester = repository.getActiveSemester() ?: return
        val confirmed = calibrationStore.isConfirmedFor(semester.id)
        if (!repository.healPlaceholderStartDate(confirmed)) return
        // 校历变了，静音与出发提醒挂在旧周次上，得重排
        runCatching { silenceScheduler.reschedule() }
        runCatching { departureScheduler.reschedule() }
    }
}
