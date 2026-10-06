package com.shangkele.feature.schedule

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shangkele.core.context.departure.DepartureAdvice
import com.shangkele.core.context.departure.DeparturePlanner
import com.shangkele.core.context.prefs.CalendarCalibrationStore
import com.shangkele.core.database.repository.ScheduleRepository
import com.shangkele.core.database.seed.DemoDataSeeder
import com.shangkele.core.model.Course
import com.shangkele.core.model.SchoolDefaults
import com.shangkele.core.model.TimeSlot
import com.shangkele.core.model.WeekCalculator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ScheduleViewModel @Inject constructor(
    private val repository: ScheduleRepository,
    private val seeder: DemoDataSeeder,
    private val calibrationStore: CalendarCalibrationStore,
) : ViewModel() {

    /** null 表示「跟随当前周」，用户点了具体周次后才固定。 */
    private val selectedWeek = MutableStateFlow<Int?>(null)
    private val selectedCourse = MutableStateFlow<Course?>(null)

    /** 每分钟推进一次，用于「下一节课」倒计时。 */
    private val nowFlow: Flow<LocalTime> = flow {
        while (true) {
            emit(LocalTime.now())
            delay(30_000L)
        }
    }

    private val semesterFlow = repository.observeActiveSemester()

    private val coursesFlow = semesterFlow.flatMapLatest { semester ->
        if (semester == null) flowOf(emptyList<Course>()) else repository.observeCourses(semester.id)
    }

    private val slotsFlow = semesterFlow.flatMapLatest { semester ->
        if (semester == null) flowOf(emptyList<TimeSlot>()) else repository.observeTimeSlots(semester.id)
    }

    private val baseState: Flow<ScheduleUiState> =
        combine(semesterFlow, coursesFlow, slotsFlow, selectedWeek) { semester, courses, slots, picked ->
            val today = LocalDate.now()
            val currentWeek = semester?.let {
                WeekCalculator.currentWeek(it.startDateEpochDay, today.toEpochDay(), it.totalWeeks)
            } ?: 1
            val week = picked ?: currentWeek
            ScheduleUiState(
                loading = false,
                semester = semester,
                currentWeek = currentWeek,
                selectedWeek = week,
                totalWeeks = semester?.totalWeeks ?: SchoolDefaults.SEMESTER_TOTAL_WEEKS,
                todayWeekday = today.dayOfWeek.value,
                timeSlots = slots,
                allCourses = courses,
                calendarConfirmed = semester?.let { calibrationStore.isConfirmedFor(it.id) } ?: true,
            )
        }

    val uiState: StateFlow<ScheduleUiState> =
        combine(baseState, selectedCourse, nowFlow) { base, picked, now ->
            base.copy(
                selectedCourse = picked,
                nextCourse = resolveNextCourse(base, now),
                departure = resolveDeparture(base),
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = ScheduleUiState(),
        )

    /**
     * 手动加载演示数据。
     *
     * W2 起**不再自动播种**：现在有真实导入通道了，一进来就塞假课表会让人分不清
     * 看到的是真实数据还是演示数据。空库时由 UI 明确给两个按钮。
     */
    fun loadDemoData() {
        viewModelScope.launch { seeder.seed() }
    }

    /** 由翻页回调驱动。 */
    fun selectWeek(week: Int) {
        if (week > 0) selectedWeek.value = week
    }

    /** 「回到本周」由界面直接把 pager 滚回当前周，这里只是单测/兜底入口。 */
    fun backToCurrentWeek() {
        selectedWeek.value = null
    }

    /**
     * 点课程块。
     *
     * 再点一次同一门课会收起详情 —— 之前这里只是单纯赋值，导致详情卡的「关闭」
     * 按钮（它调用的就是这个方法）永远关不掉。
     */
    fun toggleCourse(course: Course) {
        val current = selectedCourse.value
        selectedCourse.value = if (current?.stableKey() == course.stableKey()) null else course
    }

    fun clearSelectedCourse() {
        selectedCourse.value = null
    }

    private fun resolveNextCourse(state: ScheduleUiState, now: LocalTime): NextCourseHint? {
        val todayWeekday = LocalDate.now().dayOfWeek.value
        if (todayWeekday != state.todayWeekday) return null
        val nowMinutes = now.hour * 60 + now.minute
        val slotBySection = state.timeSlots.associateBy { it.section }

        val candidates = state.allCourses
            .filter { it.weekday == todayWeekday && it.occursInWeek(state.currentWeek) }
            .mapNotNull { course ->
                val start = slotBySection[course.startSection] ?: return@mapNotNull null
                val end = slotBySection[course.endSection] ?: return@mapNotNull null
                Triple(course, start, end)
            }
            .sortedBy { it.second.startMinutes }

        candidates.firstOrNull { nowMinutes in it.second.startMinutes..it.third.endMinutes }
            ?.let { (course, start, end) ->
                return NextCourseHint(
                    courseName = course.name,
                    teacher = course.teacher,
                    room = course.roomRaw,
                    timeLabel = "${start.startLabel()}-${end.endLabel()}",
                    minutesUntilStart = 0L,
                    ongoing = true,
                )
            }

        candidates.firstOrNull { it.second.startMinutes > nowMinutes }
            ?.let { (course, start, end) ->
                return NextCourseHint(
                    courseName = course.name,
                    teacher = course.teacher,
                    room = course.roomRaw,
                    timeLabel = "${start.startLabel()}-${end.endLabel()}",
                    minutesUntilStart = (start.startMinutes - nowMinutes).toLong(),
                    ongoing = false,
                )
            }
        return null
    }

    /**
     * 「该出发了」建议。
     *
     * 只看今天 + 本周的课，与静音、出发提醒的判定口径完全一致
     * （都用 `occursInWeek`），避免出现「卡片提示出门但闹钟没响」这种不一致。
     */
    private fun resolveDeparture(state: ScheduleUiState): DepartureAdvice? {
        if (state.timeSlots.isEmpty() || state.allCourses.isEmpty()) return null
        val todayCourses = state.allCourses.filter {
            it.weekday == state.todayWeekday && it.occursInWeek(state.currentWeek)
        }
        if (todayCourses.isEmpty()) return null
        return DeparturePlanner.plan(
            coursesToday = todayCourses,
            slots = state.timeSlots.associateBy { it.section },
            nowMillis = System.currentTimeMillis(),
        ).advice
    }

    /** 取某节课的作息，UI 与提醒共用。 */
    fun timeSlotOf(section: Int): TimeSlot? =
        uiState.value.timeSlots.firstOrNull { it.section == section }

}
