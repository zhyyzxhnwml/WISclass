package com.shangkele.feature.settings

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shangkele.core.context.departure.DepartureAdvice
import com.shangkele.core.context.departure.DepartureReminderScheduler
import com.shangkele.core.context.dnd.DndController
import com.shangkele.core.context.permission.AppPermission
import com.shangkele.core.context.permission.PermissionInspector
import com.shangkele.core.context.prefs.AppPreferences
import com.shangkele.core.context.silence.ClassSilenceScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 设置页里的一行权限/保活项。 */
data class PermissionRow(
    val permission: AppPermission,
    val title: String,
    val granted: Boolean,
    /** 为什么要开这一项 —— 不写清楚用户不会去开 */
    val reason: String,
    /** 授予路径提示，尤其是荣耀这种没有公开 Activity 的 */
    val howTo: String,
)

data class SettingsUiState(
    val rows: List<PermissionRow> = emptyList(),
    val autoSilenceEnabled: Boolean = true,
    val departureReminderEnabled: Boolean = true,
    val currentlySilent: Boolean = false,
    val currentCourseName: String? = null,
    val departureAdvice: DepartureAdvice? = null,
    val refreshKey: Int = 0,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val inspector: PermissionInspector,
    private val prefs: AppPreferences,
    private val scheduler: ClassSilenceScheduler,
    private val departureScheduler: DepartureReminderScheduler,
    private val dnd: DndController,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    /** 重新检查权限，并顺带把静音/出发闹钟按最新课表重排一次。 */
    fun refresh() {
        viewModelScope.launch {
            val plan = runCatching { scheduler.reschedule() }.getOrNull()
            val departure = runCatching { departureScheduler.reschedule() }.getOrNull()
            _state.update {
                it.copy(
                    rows = buildRows(),
                    autoSilenceEnabled = prefs.autoSilenceEnabled,
                    departureReminderEnabled = prefs.departureReminderEnabled,
                    currentlySilent = dnd.isSilentNow(),
                    currentCourseName = plan?.currentCourseName,
                    departureAdvice = departure?.advice,
                    refreshKey = it.refreshKey + 1,
                )
            }
        }
    }

    fun setAutoSilence(enabled: Boolean) {
        prefs.autoSilenceEnabled = enabled
        refresh()
    }

    fun setDepartureReminder(enabled: Boolean) {
        prefs.departureReminderEnabled = enabled
        refresh()
    }

    fun settingsIntent(permission: AppPermission): Intent = inspector.settingsIntent(permission)

    private fun buildRows(): List<PermissionRow> = listOf(
        PermissionRow(
            permission = AppPermission.Notifications,
            title = "通知权限",
            granted = inspector.isGranted(AppPermission.Notifications),
            reason = "课程提醒、课表导入进度都要靠它。关掉之后上课提醒不会响。",
            howTo = "点右侧按钮直接授权。",
        ),
        PermissionRow(
            permission = AppPermission.DoNotDisturbAccess,
            title = "勿扰访问权",
            granted = inspector.isGranted(AppPermission.DoNotDisturbAccess),
            reason = "上课自动静音、下课自动恢复需要它。没授权就只能手动静音。",
            howTo = "在系统弹出的列表里找到「上课啦」并打开开关。",
        ),
        PermissionRow(
            permission = AppPermission.ExactAlarm,
            title = "精确闹钟",
            granted = inspector.isGranted(AppPermission.ExactAlarm),
            reason = "上下课切换要准到分钟。没授权会退化成「大概那个时间」，可能晚几分钟。",
            howTo = "允许「闹钟和提醒」。",
        ),
        PermissionRow(
            permission = AppPermission.BatteryOptimization,
            title = "电池优化白名单",
            granted = inspector.isGranted(AppPermission.BatteryOptimization),
            reason = "荣耀的后台管理很激进。不加白名单，闹钟和后台任务会被掐掉。",
            howTo = "选择「允许」后台不受限制。",
        ),
        PermissionRow(
            permission = AppPermission.AutoStart,
            title = "自启动 / 后台活动",
            granted = false,
            reason = "系统无法自动检查这一项，需要手动确认。",
            howTo = "设置 → 应用 → 应用启动管理 → 找到「上课啦」→ 关闭「自动管理」，" +
                "把「自启动」「关联启动」「后台活动」全部打开。",
        ),
    )
}
