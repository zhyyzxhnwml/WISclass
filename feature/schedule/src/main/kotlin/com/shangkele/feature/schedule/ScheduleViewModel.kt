package com.shangkele.feature.schedule

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shangkele.core.context.departure.DepartureAdvice
import com.shangkele.core.context.departure.DeparturePlanner
import com.shangkele.core.context.location.DeviceLocation
import com.shangkele.core.context.prefs.CalendarCalibrationStore
import com.shangkele.core.database.repository.ScheduleRepository
import com.shangkele.core.database.seed.DemoDataSeeder
import com.shangkele.core.model.Course
import com.shangkele.core.model.GeoPoint
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
    private val location: DeviceLocation,
) : ViewModel() {

    /** null 表示「跟随当前周」，用户点了具体周次后才固定。 */
    private val selectedWeek = MutableStateFlow<Int?>(null)
    private val selectedCourse = MutableStateFlow<Course?>(null)

    /**
     * 「我到教室了」的状态。
     *
     * [lastKnown] 顺带留着最近一次拿到的位置 —— 课表上的出发建议要用它，
     * 否则同一个页面会出现「提醒按距离算、建议按楼栋算」两套口径。
     */
    private data class CaptureState(
        val running: Boolean = false,
        val message: String? = null,
        val lastKnown: GeoPoint? = null,
    )

    private val capture = MutableStateFlow(CaptureState())

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
        combine(
            baseState,
            selectedCourse,
            nowFlow,
            repository.observeClassroomLocations(),
            capture,
        ) { base, picked, now, rooms, capturing ->
            val next = resolveNextCourse(base, now)
            base.copy(
                selectedCourse = picked,
                nextCourse = next,
                departure = resolveDeparture(base, rooms, capturing.lastKnown),
                // 界面据此决定要不要给「我到教室了」：已经记过位置的教室就不再问
                classroomLocationKnown = next?.roomKey?.let { it in rooms } == true,
                capturingLocation = capturing.running,
                captureMessage = capturing.message,
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
                    roomKey = course.roomKey,
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
                    roomKey = course.roomKey,
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
    private fun resolveDeparture(
        state: ScheduleUiState,
        roomLocations: Map<String, GeoPoint>,
        current: GeoPoint?,
    ): DepartureAdvice? {
        if (state.timeSlots.isEmpty() || state.allCourses.isEmpty()) return null
        val todayCourses = state.allCourses.filter {
            it.weekday == state.todayWeekday && it.occursInWeek(state.currentWeek)
        }
        if (todayCourses.isEmpty()) return null
        return DeparturePlanner.plan(
            coursesToday = todayCourses,
            slots = state.timeSlots.associateBy { it.section },
            nowMillis = System.currentTimeMillis(),
            current = current,
            roomLocations = roomLocations,
        ).advice
    }

    /** 取某节课的作息，UI 与提醒共用。 */
    fun timeSlotOf(section: Int): TimeSlot? =
        uiState.value.timeSlots.firstOrNull { it.section == section }

    // ---- 教室坐标 ----

    /**
     * 「我到教室了」：把**当前这节课**的教室坐标记下来。
     *
     * 这是坐标唯一的来源 —— 不接地图 SDK，靠人到教室时按一下。
     * 记下来之后，出发提醒与课表上的建议才会从「按楼栋猜」变成「按距离算」。
     *
     * 失败一律说清原因（没权限 / 定不到位 / 这间教室没编号），
     * 不能点了没反应 —— 那样用户只会以为功能坏了。
     */
    fun rememberClassroomLocation() {
        if (capture.value.running) return
        viewModelScope.launch {
            capture.value = capture.value.copy(running = true, message = null)

            val hint = uiState.value.nextCourse
            val roomKey = hint?.roomKey
            if (roomKey.isNullOrBlank()) {
                capture.value = capture.value.copy(running = false, message = "这节课没有教室编号，记不了位置")
                return@launch
            }

            val point = location.current()
            if (point == null) {
                capture.value = capture.value.copy(
                    running = false,
                    message = if (location.hasPermission()) {
                        "暂时定不到位（室内信号弱），走到窗边再试一次"
                    } else {
                        "需要定位权限才能记住教室位置"
                    },
                )
                return@launch
            }

            val saved = runCatching { repository.rememberClassroomLocation(roomKey, point) }
                .getOrDefault(false)

            capture.value = CaptureState(
                running = false,
                message = if (saved) "记住了 ${hint.room} 的位置" else "没能保存，再试一次",
                lastKnown = point,
            )
        }
    }

    /**
     * 顺手刷新一次当前位置，只为了让课表上的出发建议也走距离口径。
     *
     * 失败就保持原样 —— 这条是锦上添花，不该因为它弹任何提示。
     */
    fun refreshLocation() {
        if (!location.hasPermission()) return
        viewModelScope.launch {
            val point = location.current() ?: return@launch
            capture.value = capture.value.copy(lastKnown = point)
        }
    }

    fun consumeCaptureMessage() {
        capture.value = capture.value.copy(message = null)
    }
}
