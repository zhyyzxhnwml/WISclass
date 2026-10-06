package com.shangkele.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shangkele.core.context.prefs.CalendarCalibrationStore
import com.shangkele.core.context.silence.ClassSilenceScheduler
import com.shangkele.core.database.repository.ScheduleRepository
import com.shangkele.core.model.WeekCalculator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

data class CalendarUiState(
    val loaded: Boolean = false,
    val semesterName: String = "",
    val semesterId: Long = 0L,
    /** 开学日期 —— 用户选的那一天，**不一定是周一** */
    val startEpochDay: Long = 0L,
    /** 由「开学日期 + 今天」算出的当前周次 */
    val currentWeek: Int = 1,
    val totalWeeks: Int = 20,
    val confirmed: Boolean = false,
) {
    val startDateLabel: String
        get() = if (startEpochDay > 0) WeekCalculator.fullDate(startEpochDay) else "未设置"

    /** 开学日是周几。让用户一眼核对「我填的日子对不对」，纯展示。 */
    val startWeekdayLabel: String
        get() = if (startEpochDay > 0) WeekCalculator.weekdayLabel(startEpochDay) else ""

    /** 这一周的日期区间，用来和教务系统对照 */
    val currentWeekRange: String
        get() = if (startEpochDay > 0) {
            WeekCalculator.weekRangeLabel(startEpochDay, currentWeek)
        } else {
            ""
        }
}

@HiltViewModel
class CalendarViewModel @Inject constructor(
    private val repository: ScheduleRepository,
    private val calibrationStore: CalendarCalibrationStore,
    private val silenceScheduler: ClassSilenceScheduler,
) : ViewModel() {

    private val _state = MutableStateFlow(CalendarUiState())
    val state: StateFlow<CalendarUiState> = _state.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        load()
    }

    private fun load() {
        viewModelScope.launch {
            val semester = repository.getActiveSemester()
            val today = LocalDate.now().toEpochDay()
            _state.value = CalendarUiState(
                loaded = true,
                semesterName = semester?.name.orEmpty(),
                semesterId = semester?.id ?: 0L,
                startEpochDay = semester?.startDateEpochDay ?: 0L,
                currentWeek = semester?.let {
                    WeekCalculator.currentWeek(it.startDateEpochDay, today, it.totalWeeks)
                } ?: 1,
                totalWeeks = semester?.totalWeeks ?: 20,
                confirmed = semester?.let { calibrationStore.isConfirmedFor(it.id) } ?: false,
            )
        }
    }

    /**
     * 设置开学日期 —— 本页唯一入口。
     *
     * **存的就是用户选的那一天**，不做「归到周一」的改写。
     * 改写会让用户下次打开看到的是另一个日期，以为没生效、反复重选；
     * 周次就是从这一天起算的：每 7 天一周，不跳到周一
     * （开学日是周六，第 1 周就是周六 ~ 周五），见 [WeekCalculator.weekStartEpochDay]。
     */
    fun setStartDate(epochDay: Long) {
        viewModelScope.launch {
            val semester = repository.getActiveSemester()
            if (semester == null) {
                _message.value = "还没有课表，先导入一次再来设置"
                return@launch
            }
            // 用返回值判断，不假定写入一定成功：写不进去却报「已设为」，
            // 就是最难查的那种「界面说好了、数据没变」
            val written = repository.updateActiveSemesterStartDate(epochDay)
            if (!written) {
                _message.value = "没能写入：找不到当前学期，请先导入一次课表"
                return@launch
            }
            calibrationStore.markConfirmed(semester.id)
            // 校历一变，「现在在不在上课」的判定跟着变，静音链必须重排，
            // 否则闹钟还挂在旧周次的课表上
            runCatching { silenceScheduler.reschedule() }
            load()
            _state.value = _state.value.copy(confirmed = true)
            _message.value = "开学日期已设为 ${WeekCalculator.fullDate(epochDay)}" +
                "（${WeekCalculator.weekdayLabel(epochDay)}）"
        }
    }

    /**
     * 日期本来就对，只是还没确认过。
     *
     * 保留这条路径是因为自动识别的校历有可能是对的，
     * 不该逼用户为了「确认」去重选一遍日期。
     */
    fun confirm() {
        val semesterId = _state.value.semesterId
        if (semesterId <= 0L) {
            _message.value = "还没有课表，先导入一次再来设置"
            return
        }
        calibrationStore.markConfirmed(semesterId)
        _state.value = _state.value.copy(confirmed = true)
        _message.value = "已确认开学日期"
    }

    fun consumeMessage() {
        _message.value = null
    }
}
