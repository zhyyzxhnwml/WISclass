package com.shangkele.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shangkele.core.jwgl.ImportRequest
import com.shangkele.core.jwgl.ImportResult
import com.shangkele.core.jwgl.JwglSessionStore
import com.shangkele.core.jwgl.JwglWebCookies
import com.shangkele.core.context.silence.ClassSilenceScheduler
import com.shangkele.core.jwgl.PageHintsParser
import com.shangkele.core.jwgl.ScheduleHintsResolver
import com.shangkele.core.jwgl.ScheduleImporter
import com.shangkele.core.database.repository.ScheduleRepository
import com.shangkele.core.model.SchoolDefaults
import com.shangkele.core.model.SemesterResolver
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ImportViewModel @Inject constructor(
    private val importer: ScheduleImporter,
    private val sessionStore: JwglSessionStore,
    private val silenceScheduler: ClassSilenceScheduler,
    private val repository: ScheduleRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ImportUiState())
    val state: StateFlow<ImportUiState> = _state.asStateFlow()

    /** 页面内抓取的超时兜底，避免界面永远停在「正在抓取」。 */
    private var webImportTimeout: Job? = null

    init {
        viewModelScope.launch {
            val existing = repository.getActiveSemester()
            val guess = SemesterResolver.guess()
            _state.update {
                it.copy(
                    xnm = existing?.xnm ?: guess.xnm,
                    xqm = existing?.xqm ?: guess.xqm,
                    // 已经有学期就用库里的值，不要拿猜测值盖掉 ——
                    // 否则「重新导入一次课表」会把用户设过的开学日期重置回猜测值，
                    // 而这一步看不出任何异常。
                    startEpochDay = existing?.startDateEpochDay ?: guess.startDateEpochDay,
                    totalWeeks = existing?.totalWeeks ?: guess.totalWeeks,
                    yearOptions = SemesterResolver.yearOptions(),
                    hasSavedSession = sessionStore.hasSession(),
                    savedEndpoint = sessionStore.scheduleEndpoint(),
                )
            }
        }
    }

    /**
     * 收到页面上刮下来的时间线索。
     *
     * **只识别、不应用。** 周次错一周会让整张课表的「本周有没有这门课」全判错，
     * 作息错会让上课静音和「该出发了」在错误时刻触发 —— 两者都不会报错，
     * 所以必须先把识别结果摆给用户看，由他点确认。
     */
    fun ingestPageHints(payload: String) {
        val hints = runCatching { PageHintsParser.parse(payload) }.getOrNull() ?: return

        viewModelScope.launch {
            val todayEpochDay = java.time.LocalDate.now().toEpochDay()
            val startEpochDay = hints.currentWeek?.let {
                ScheduleHintsResolver.firstWeekMonday(todayEpochDay, it)
            }

            val resolution = ScheduleHintsResolver.resolveTimeSlots(hints.sectionTimes)
            val semester = repository.getActiveSemester()
            val existingSlots = semester?.let { repository.getTimeSlots(it.id) }.orEmpty()
            val differences = ScheduleHintsResolver.diff(existingSlots, resolution.slots)

            _state.update {
                it.copy(
                    detected = DetectedHintsUi(
                        currentWeek = hints.currentWeek,
                        startEpochDay = startEpochDay,
                        slots = resolution.slots,
                        differences = differences,
                        assumed = resolution.assumed,
                        notes = resolution.notes,
                        pageTextLength = hints.textLength,
                        pageSample = hints.sampleText,
                    ),
                    detectedApplied = false,
                )
            }
        }
    }

    /**
     * 写入开学日期。
     *
     * 教务系统能给的只有「今天是第 N 周」——它**不告诉我们第 N 周的第 1 天是星期几**。
     * 而周次是从开学那天起算的：开学日是周六，第 1 周就是周六 ~ 周五。
     * 所以这里只接受用户**明确选中的那一天**，不再自作主张拿「那一周的周一」顶上
     * （那会把 8/29 开学写成 8/24，整张课表错位，且不报错）。
     */
    fun applyDetectedCalendar(startEpochDay: Long) {
        viewModelScope.launch {
            _state.update { it.copy(startEpochDay = startEpochDay, detectedApplied = true) }
            // 还没有学期时先只记在内存里，导入会带着它建学期
            repository.updateActiveSemesterStartDate(startEpochDay)
            // 校历变了，「现在在不在上课」的判定跟着变，静音链要重排
            runCatching { silenceScheduler.reschedule() }
        }
    }

    /** 应用识别出的作息表。 */
    fun applyDetectedTimeSlots() {
        val detected = _state.value.detected ?: return
        if (detected.slots.isEmpty()) return
        viewModelScope.launch {
            val semester = repository.getActiveSemester() ?: return@launch
            repository.replaceTimeSlots(semester.id, detected.slots)
            runCatching { silenceScheduler.reschedule() }
            _state.update {
                it.copy(
                    detected = it.detected?.copy(slots = emptyList(), differences = emptyList()),
                    detectedApplied = true,
                )
            }
        }
    }

    fun dismissDetected() = _state.update { it.copy(detected = null) }

    fun selectYear(xnm: String) = _state.update { it.copy(xnm = xnm) }

    fun selectTerm(xqm: String) = _state.update { it.copy(xqm = xqm) }

    /**
     * 直接指定开学日期。
     *
     * 猜出来的值是「9 月 1 日所在周的周一」（见 [SemesterResolver]），
     * 大多数学校不是这天 —— 而它决定整张课表的本周判定，错了不会有任何报错。
     *
     * **已有学期时立刻落库**：只在内存里改的话，用户在这一页选完日期、
     * 没点导入就返回，改动会连同页面一起消失 —— 一个不会报错的空操作。
     * 还没学期时写不进去也没关系，导入会用内存里的值新建学期。
     */
    fun setStartDate(epochDay: Long) {
        _state.update { it.copy(startEpochDay = epochDay) }
        viewModelScope.launch { repository.updateActiveSemesterStartDate(epochDay) }
    }

    fun openWebLogin() = _state.update { it.copy(tab = ImportTab.WebLogin) }

    fun backToSetup() = _state.update { it.copy(tab = ImportTab.Setup) }

    fun dismissStatus() = _state.update { it.copy(status = ImportStatus.Idle) }

    /**
     * 点「开始导入」：置为进行中并起超时。
     * 真正的注入脚本由界面层执行（它持有 WebView），结果通过 [ingestScheduleJson] / [onWebFetchFailed] 回来。
     */
    fun beginWebImport() {
        _state.update { it.copy(status = ImportStatus.Running) }
        webImportTimeout?.cancel()
        webImportTimeout = viewModelScope.launch {
            delay(WEB_IMPORT_TIMEOUT_MS)
            if (_state.value.status is ImportStatus.Running) {
                _state.update {
                    it.copy(
                        status = ImportStatus.Failure(
                            "页面没有返回课表数据（超时）。\n" +
                                "请确认已登录，并停留在教务系统首页后重试。",
                        ),
                    )
                }
            }
        }
    }

    /** WebView 页面内抓到了课表 JSON —— 首选通道。 */
    fun ingestScheduleJson(endpointUrl: String, rawJson: String) {
        webImportTimeout?.cancel()
        if (_state.value.status is ImportStatus.Running) return
        viewModelScope.launch {
            _state.update { it.copy(status = ImportStatus.Running) }
            handleResult(importer.importRawJson(rawJson, request(), endpointUrl))
        }
    }

    /**
     * 页面内抓取失败 → 自动退到原生 OkHttp 再试一次。
     * 两条都失败时把两边的信息都摆出来，方便定位到底是端点不对还是会话没带上。
     */
    fun onWebFetchFailed(detail: String) {
        webImportTimeout?.cancel()
        val cookies = JwglWebCookies.read()
        if (cookies.isNullOrBlank()) {
            _state.update {
                it.copy(
                    status = ImportStatus.Failure(
                        "没有检测到登录会话，请先在页面里登录教务系统。\n（$detail）",
                    ),
                )
            }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(status = ImportStatus.Running) }
            when (val result = importer.importWithCookies(cookies, request())) {
                is ImportResult.Success -> handleResult(result)
                is ImportResult.Failure -> _state.update {
                    it.copy(
                        status = ImportStatus.Failure(
                            "页面内抓取失败：$detail\n改用接口直接请求也失败：${result.message}",
                        ),
                    )
                }
            }
        }
    }

    /**
     * 「用已保存的登录刷新课表」。
     *
     * 走 WebView 页面内抓取而不是接口直连：实测同源请求的成功率高得多，
     * 而且会话一旦过期，正方会把 WebView 带到登录页，用户顺势重登即可 ——
     * 比在后台静默失败、只丢一句难懂的报错好得多。
     */
    fun openWebLoginForRefresh() {
        _state.update {
            it.copy(
                tab = ImportTab.WebLogin,
                autoFetchPending = true,
                status = ImportStatus.Idle,
            )
        }
    }

    /** WebView 完成自动抓取后清掉标记，避免每次翻页都重复触发。 */
    fun consumeAutoFetch() {
        _state.update { it.copy(autoFetchPending = false) }
    }

    /** 接口直连的静默刷新，留作后续后台刷新用。 */
    fun refreshWithSavedSession() {
        viewModelScope.launch {
            _state.update { it.copy(status = ImportStatus.Running) }
            handleResult(importer.refreshWithSavedSession(request()))
        }
    }

    fun forgetLogin() {
        importer.forgetSession()
        JwglWebCookies.clear()
        _state.update {
            it.copy(
                hasSavedSession = false,
                savedEndpoint = null,
                status = ImportStatus.Idle,
                tab = ImportTab.Setup,
            )
        }
    }

    private fun handleResult(result: ImportResult) {
        when (result) {
            is ImportResult.Success -> {
                // 新课表意味着新的上下课时间点，静音闹钟要立刻按新课表重排
                viewModelScope.launch { runCatching { silenceScheduler.reschedule() } }
                _state.update {
                    it.copy(
                        status = ImportStatus.Success(
                            courseCount = result.courseCount,
                            changes = result.changes,
                            skipped = result.skipped,
                            studentName = result.studentName,
                            sourceUrl = result.sourceUrl,
                        ),
                        hasSavedSession = true,
                        savedEndpoint = result.sourceUrl ?: it.savedEndpoint,
                        tab = ImportTab.Setup,
                    )
                }
            }

            is ImportResult.Failure -> _state.update {
                it.copy(
                    status = ImportStatus.Failure(result.message),
                    // 会话已经没用了，就别再显示那个必然失败的刷新入口
                    hasSavedSession = if (result.sessionExpired) false else it.hasSavedSession,
                    autoFetchPending = false,
                )
            }
        }
    }

    private fun request() = ImportRequest(
        xnm = _state.value.xnm,
        xqm = _state.value.xqm,
        totalWeeks = _state.value.totalWeeks.takeIf { it > 0 } ?: SchoolDefaults.SEMESTER_TOTAL_WEEKS,
        startDateEpochDay = _state.value.startEpochDay,
    )

    private companion object {
        const val WEB_IMPORT_TIMEOUT_MS = 25_000L
    }
}
