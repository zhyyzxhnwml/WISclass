package com.shangkele.feature.schedule

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shangkele.core.database.repository.ScheduleRepository
import com.shangkele.core.model.Assignment
import com.shangkele.core.model.Course
import com.shangkele.core.model.Semester
import com.shangkele.core.model.TimeSlot
import com.shangkele.core.model.WeekCalculator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/**
 * 日程：把**课**和**作业/待办**合到一条时间线上看。
 *
 * 核心的一条规则（用户明确要求）：**只列出有安排的日子，没安排的日子不出现** ——
 * 那些天就是空余时间。同理，一天之内两件事之间的空档也不画任何东西。
 * 不这么做的结果是把「没安排」和「已排满」在视觉上搅在一起，反而看不出哪天空着。
 */
@HiltViewModel
class AgendaViewModel @Inject constructor(
    private val repository: ScheduleRepository,
) : ViewModel() {

    /** 一节课（在日程里的样子）。 */
    data class AgendaClass(
        val courseName: String,
        val room: String,
        /** 作息表里查不到节次对应的时间时为 null，此时显示「第 3-4 节」 */
        val startMinutes: Int?,
        val endMinutes: Int?,
        val sectionLabel: String,
    ) {
        val timeLabel: String
            get() = if (startMinutes != null && endMinutes != null) {
                "%02d:%02d–%02d:%02d".format(
                    startMinutes / 60, startMinutes % 60,
                    endMinutes / 60, endMinutes % 60,
                )
            } else {
                sectionLabel
            }
    }

    /** 有安排的一天。 */
    data class AgendaDay(
        val dateEpochDay: Long,
        val isToday: Boolean,
        val classes: List<AgendaClass>,
        /** 这天到期的待办。 */
        val todos: List<Assignment>,
    )

    data class AgendaUiState(
        val loading: Boolean = true,
        /** 已经过了截止日、还没打勾的 —— 排在最上面。 */
        val overdue: List<Assignment> = emptyList(),
        /** 有安排的日子，按日期升序。**没安排的日子不在这里**。 */
        val days: List<AgendaDay> = emptyList(),
        /** 抽出来了但没解析出日期的待办（「期末之前交」这种），单独一堆。 */
        val undated: List<Assignment> = emptyList(),
        val todayEpochDay: Long = 0L,
        val currentWeek: Int? = null,
        val hasSemester: Boolean = true,
    ) {
        /** 两周内一条安排都没有。 */
        val isEmpty: Boolean
            get() = overdue.isEmpty() && days.isEmpty() && undated.isEmpty()

        val scheduledDayCount: Int get() = days.size
    }

    private val semesterFlow = repository.observeActiveSemester()

    private val coursesFlow = semesterFlow.flatMapLatest { semester ->
        if (semester == null) flowOf(emptyList()) else repository.observeCourses(semester.id)
    }

    private val slotsFlow = semesterFlow.flatMapLatest { semester ->
        if (semester == null) flowOf(emptyList()) else repository.observeTimeSlots(semester.id)
    }

    val state: StateFlow<AgendaUiState> = combine(
        semesterFlow,
        coursesFlow,
        slotsFlow,
        repository.observeAssignments(),
    ) { semester, courses, slots, assignments ->
        build(semester, courses, slots, assignments)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = AgendaUiState(),
    )

    /** 打勾 / 取消。日程页是复习时最可能顺手勾掉的地方。 */
    fun setDone(id: Long, done: Boolean) {
        viewModelScope.launch { repository.setAssignmentDone(id, done) }
    }

    private fun build(
        semester: Semester?,
        courses: List<Course>,
        slots: List<TimeSlot>,
        assignments: List<Assignment>,
    ): AgendaUiState {
        val today = LocalDate.now().toEpochDay()
        val slotBySection = slots.associateBy { it.section }

        val pending = assignments.filter { !it.done }
        val overdue = pending
            .filter { it.dueEpochDay != null && it.dueEpochDay!! < today }
            .sortedBy { it.dueEpochDay }
        // 解析不出日期的（「期末之前交」）不能丢，也不能硬塞进某一天 —— 单独一堆
        val undated = pending.filter { it.dueEpochDay == null }
        val datedByDay = pending
            .filter { it.dueEpochDay != null && it.dueEpochDay!! >= today }
            .groupBy { it.dueEpochDay!! }

        val currentWeek = semester?.let {
            WeekCalculator.currentWeek(it.startDateEpochDay, today, it.totalWeeks)
        }

        val days = (0 until HORIZON_DAYS).mapNotNull { offset ->
            val day = today + offset
            val week = semester?.let {
                WeekCalculator.currentWeek(it.startDateEpochDay, day, it.totalWeeks)
            }

            val classes = if (week == null) {
                emptyList()
            } else {
                courses
                    .filter { it.weekday == WeekCalculator.weekdayOf(day) && it.occursInWeek(week) }
                    .map { course ->
                        val start = slotBySection[course.startSection]?.startMinutes
                        val end = slotBySection[course.endSection]?.endMinutes
                        AgendaClass(
                            courseName = course.name,
                            room = course.roomRaw,
                            startMinutes = start,
                            endMinutes = end,
                            sectionLabel = "第 ${course.startSection}-${course.endSection} 节",
                        )
                    }
                    // 查不到作息时间的排在最后，但**不丢**：课表导入正常就一定查得到，
                    // 查不到说明作息表没配好，这时候把课藏起来更糟
                    .sortedBy { it.startMinutes ?: Int.MAX_VALUE }
            }

            val todos = datedByDay[day].orEmpty()

            // 没课、也没有到期的事 —— 这一天整天不列出来，它就是空余时间
            if (classes.isEmpty() && todos.isEmpty()) {
                null
            } else {
                AgendaDay(dateEpochDay = day, isToday = offset == 0, classes = classes, todos = todos)
            }
        }

        return AgendaUiState(
            loading = false,
            overdue = overdue,
            days = days,
            undated = undated,
            todayEpochDay = today,
            currentWeek = currentWeek,
            hasSemester = semester != null,
        )
    }

    private companion object {
        /** 往前看两周。再远就没意义了 —— 教务也常常只排到这儿。 */
        const val HORIZON_DAYS = 14
    }
}
