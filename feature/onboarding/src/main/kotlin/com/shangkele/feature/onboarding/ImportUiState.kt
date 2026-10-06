package com.shangkele.feature.onboarding

import com.shangkele.core.jwgl.ScheduleHintsResolver
import com.shangkele.core.model.ScheduleChange
import com.shangkele.core.model.Semester
import com.shangkele.core.model.TimeSlot

/**
 * 从教务页面上自动识别到、但**还没应用**的时间线索。
 *
 * 刻意不自动写入：周次错一周会让整张课表错位，作息错会让静音与出发提醒在
 * 错误时刻触发 —— 两者都不报错。所以一律先摆出来让用户确认。
 */
data class DetectedHintsUi(
    val currentWeek: Int? = null,
    /** 由「当前周次 + 今天」反推出的第一周周一 */
    val startEpochDay: Long? = null,
    val slots: List<TimeSlot> = emptyList(),
    val differences: List<ScheduleHintsResolver.Difference> = emptyList(),
    /** true = 作息是用 45 分钟假设从分块时间推出来的，需要核对 */
    val assumed: Boolean = false,
    val notes: List<String> = emptyList(),
    /** 页面文本长度，为 0 说明注入脚本没读到内容 */
    val pageTextLength: Int = 0,
    /** 页面文本开头一段，识别不准时给用户看，便于定位 */
    val pageSample: String = "",
) {
    val hasCalendar: Boolean get() = currentWeek != null && startEpochDay != null

    val hasSlots: Boolean get() = slots.isNotEmpty()

    val hasAnything: Boolean get() = hasCalendar || hasSlots
}

sealed interface ImportStatus {
    /** 还没开始，等用户点按钮 */
    data object Idle : ImportStatus

    /** 正在抓取 + 解析 */
    data object Running : ImportStatus

    data class Success(
        val courseCount: Int,
        val changes: List<ScheduleChange>,
        val skipped: List<String>,
        val studentName: String?,
        /** 本次生效的课表接口地址，用来确认端点到底对不对 */
        val sourceUrl: String?,
    ) : ImportStatus

    data class Failure(val message: String) : ImportStatus
}

data class ImportUiState(
    val xnm: String = "",
    val xqm: String = "3",
    val startEpochDay: Long = 0L,
    val totalWeeks: Int = 20,
    val yearOptions: List<String> = emptyList(),
    val tab: ImportTab = ImportTab.Setup,
    val status: ImportStatus = ImportStatus.Idle,
    val hasSavedSession: Boolean = false,
    /** 上次实测可用的课表接口地址 */
    val savedEndpoint: String? = null,
    /**
     * 「用已保存的登录刷新」时置位：等 WebView 页面加载完就自动抓一次。
     *
     * 为什么不走接口直连：实测同源页面内抓取的成功率高得多，接口直连只有在
     * 会话完全有效时才成。走 WebView 还能顺带把过期会话自然导回登录页。
     */
    val autoFetchPending: Boolean = false,
    /** 页面上自动识别到的时间线索（待用户确认） */
    val detected: DetectedHintsUi? = null,
    /** 已经应用过，避免重复提示 */
    val detectedApplied: Boolean = false,
) {
    val semesterName: String
        get() = if (xnm.isBlank()) "" else Semester.buildName(xnm, xqm)

    val busy: Boolean get() = status is ImportStatus.Running
}

/** 导入页的两个步骤：先确认学期，再去登录。 */
enum class ImportTab { Setup, WebLogin }
