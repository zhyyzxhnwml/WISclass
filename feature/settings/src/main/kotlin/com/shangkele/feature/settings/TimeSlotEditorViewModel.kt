package com.shangkele.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shangkele.core.context.departure.DepartureReminderScheduler
import com.shangkele.core.context.silence.ClassSilenceScheduler
import com.shangkele.core.database.repository.ScheduleRepository
import com.shangkele.core.model.SchoolDefaults
import com.shangkele.core.model.TimeSlot
import com.shangkele.core.model.TimeSlotValidator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TimeSlotRow(
    val section: Int,
    val startMinutes: Int,
    val endMinutes: Int,
    /** 校验发现的问题，null 表示这一节没问题 */
    val issue: String? = null,
) {
    val startLabel: String get() = TimeSlot.formatMinutes(startMinutes)

    val endLabel: String get() = TimeSlot.formatMinutes(endMinutes)
}

data class TimeSlotEditorUiState(
    val loaded: Boolean = false,
    val semesterName: String = "",
    val rows: List<TimeSlotRow> = emptyList(),
) {
    val hasIssue: Boolean get() = rows.any { it.issue != null }

    /** 第一节课的开始时间 —— 用户一眼就能看出「几点上课」对不对。 */
    val firstStartLabel: String get() = rows.firstOrNull()?.startLabel.orEmpty()

    val lastEndLabel: String get() = rows.lastOrNull()?.endLabel.orEmpty()
}

@HiltViewModel
class TimeSlotEditorViewModel @Inject constructor(
    private val repository: ScheduleRepository,
    private val silenceScheduler: ClassSilenceScheduler,
    private val departureScheduler: DepartureReminderScheduler,
) : ViewModel() {

    private val _state = MutableStateFlow(TimeSlotEditorUiState())
    val state: StateFlow<TimeSlotEditorUiState> = _state.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private var semesterId: Long? = null

    init {
        viewModelScope.launch {
            val semester = repository.getActiveSemester()
            if (semester == null) {
                _state.update { it.copy(loaded = true) }
                return@launch
            }
            semesterId = semester.id
            // 顺手做一次自愈：早期写错的占位作息会在这里被换成正确默认值
            repository.ensureDefaultTimeSlots(semester.id)
            publish(repository.getTimeSlots(semester.id), semester.name)
        }
    }

    fun setTime(section: Int, isStart: Boolean, minutes: Int) {
        val rows = _state.value.rows
        if (rows.isEmpty()) return
        val updated = rows.map { row ->
            when {
                row.section != section -> row
                isStart -> row.copy(startMinutes = minutes)
                else -> row.copy(endMinutes = minutes)
            }
        }
        persist(updated)
    }

    fun restoreDefaults() {
        persist(
            SchoolDefaults.TIME_SLOTS.map {
                TimeSlotRow(it.section, it.startMinutes, it.endMinutes)
            },
        )
        _message.value = "已恢复默认作息"
    }

    fun consumeMessage() {
        _message.value = null
    }

    /**
     * 每改一次立刻落库，不做「保存」按钮。
     *
     * 改完一个时间点就是一个完整意图，没有「改到一半」的中间态；
     * 而忘点保存导致以为改好了、实际没改，是很难自己发现的错。
     */
    private fun persist(rows: List<TimeSlotRow>) {
        val slots = rows.map { TimeSlot(it.section, it.startMinutes, it.endMinutes) }
        publish(slots, _state.value.semesterName)

        viewModelScope.launch {
            val id = semesterId ?: return@launch
            repository.replaceTimeSlots(id, slots)
            // 作息一改，静音与出发提醒的时间点全变，必须立刻重排，
            // 否则闹钟还挂在旧时间上
            runCatching { silenceScheduler.reschedule() }
            runCatching { departureScheduler.reschedule() }
        }
    }

    private fun publish(slots: List<TimeSlot>, semesterName: String) {
        val issues = TimeSlotValidator.validate(slots).associateBy { it.section }
        _state.value = TimeSlotEditorUiState(
            loaded = true,
            semesterName = semesterName,
            rows = slots.sortedBy { it.section }.map {
                TimeSlotRow(it.section, it.startMinutes, it.endMinutes, issues[it.section]?.message)
            },
        )
    }
}
