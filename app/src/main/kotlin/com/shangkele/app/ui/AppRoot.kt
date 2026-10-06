package com.shangkele.app.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.shangkele.feature.notes.NoteDetailScreen
import com.shangkele.feature.notes.NoteDetailViewModel
import com.shangkele.feature.notes.NotesScreen
import com.shangkele.feature.onboarding.ImportScreen
import com.shangkele.feature.schedule.AgendaScreen
import com.shangkele.feature.schedule.ScheduleScreen
import com.shangkele.feature.settings.CalendarScreen
import com.shangkele.feature.settings.LlmConfigScreen
import com.shangkele.feature.settings.ModelManagerScreen
import com.shangkele.feature.settings.SettingsScreen
import com.shangkele.feature.settings.TimeSlotEditorScreen
import com.shangkele.app.update.UpdateDialogHost
import com.shangkele.app.update.UpdateViewModel

/** 路由表。新增页面时先在这里登记。 */
object Routes {
    const val SCHEDULE = "schedule"

    /** 日程：课 + 作业/待办合成的时间线。空档就是空余时间。 */
    const val AGENDA = "agenda"
    const val IMPORT = "import"
    const val SETTINGS = "settings"
    const val NOTES = "notes"
    const val TIME_SLOTS = "settings/timeSlots"
    const val MODELS = "settings/models"
    const val LLM_CONFIG = "settings/llm"
    const val CALENDAR = "settings/calendar"

    /** 笔记详情。带参路由，参数名与 [com.shangkele.feature.notes.NoteDetailViewModel] 的约定一致。 */
    const val NOTE_DETAIL = "notes/{noteId}"

    fun noteDetail(noteId: Long): String = "notes/$noteId"
}

/**
 * 顶层页面 —— 底部导航的三格。
 *
 * 加这个之前，顶层页面是靠一串回调互相跳的：课表右上角塞「笔记」「设置」两个按钮，
 * 设置里再挂一串二级入口。到了第三个顶层页面就理不清了：从笔记回课表得按返回，
 * 从设置去笔记要先绕回课表；而「返回」在顶层页面之间本来就没有意义。
 *
 * 换成底部导航后：顶层页面永远可见、随时可切，返回键只管二级页面。
 */
private enum class MainTab(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    SCHEDULE(Routes.SCHEDULE, "课表", Icons.Filled.DateRange),
    AGENDA(Routes.AGENDA, "日程", Icons.Filled.List),
    NOTES(Routes.NOTES, "笔记", Icons.Filled.Create),
    SETTINGS(Routes.SETTINGS, "设置", Icons.Filled.Settings),
}

@Composable
fun AppRoot(
    // 挂在 Activity 上（不是某个导航目标），这样切标签、进二级页都不会丢状态；
    // 弹窗挂在根节点而不是设置页里，因为自动检查发生在开 App 时，那时用户不在设置页
    updateViewModel: UpdateViewModel = hiltViewModel(),
) {
    val navController = rememberNavController()

    // 开 App 时静默查一次有没有新版。频率限制与「以后再说」都在 ViewModel 里管。
    LaunchedEffect(Unit) { updateViewModel.autoCheck() }

    // 只有落在这三个顶层路由上时才显示底栏；进二级页面（笔记详情、导入、模型管理…）
    // 自动让开，把整屏还给内容。
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val currentTab = MainTab.entries.firstOrNull { it.route == currentRoute }

    Scaffold(
        bottomBar = {
            if (currentTab != null) {
                NavigationBar {
                    MainTab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = tab == currentTab,
                            onClick = { navController.switchTab(tab) },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Routes.SCHEDULE,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(Routes.SCHEDULE) {
                ScheduleScreen(
                    onOpenImport = { navController.navigate(Routes.IMPORT) },
                    onOpenCalendar = { navController.navigate(Routes.CALENDAR) },
                )
            }

            composable(Routes.AGENDA) {
                AgendaScreen()
            }

            composable(Routes.NOTES) {
                NotesScreen(
                    onOpenNote = { noteId -> navController.navigate(Routes.noteDetail(noteId)) },
                )
            }

            composable(
                route = Routes.NOTE_DETAIL,
                arguments = listOf(navArgument(NoteDetailViewModel.ARG_NOTE_ID) { type = NavType.StringType }),
            ) {
                NoteDetailScreen(onBack = { navController.popBackStack() })
            }

            composable(Routes.IMPORT) {
                ImportScreen(
                    onBack = { navController.popBackStack() },
                    // 导入完成后把导入页从栈里弹掉，避免用户返回时又回到导入页
                    onImported = { navController.popBackStack(Routes.SCHEDULE, inclusive = false) },
                )
            }

            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onOpenTimeSlots = { navController.navigate(Routes.TIME_SLOTS) },
                    onOpenModels = { navController.navigate(Routes.MODELS) },
                    onOpenImport = { navController.navigate(Routes.IMPORT) },
                    onOpenCalendar = { navController.navigate(Routes.CALENDAR) },
                    onCheckUpdate = updateViewModel::checkNow,
                    appVersion = updateViewModel.currentVersion,
                )
            }

            composable(Routes.TIME_SLOTS) {
                TimeSlotEditorScreen(onBack = { navController.popBackStack() })
            }

            composable(Routes.MODELS) {
                ModelManagerScreen(
                    onBack = { navController.popBackStack() },
                    onOpenLlmConfig = { navController.navigate(Routes.LLM_CONFIG) },
                )
            }

            composable(Routes.LLM_CONFIG) {
                LlmConfigScreen(onBack = { navController.popBackStack() })
            }

            composable(Routes.CALENDAR) {
                CalendarScreen(onBack = { navController.popBackStack() })
            }
        }
    }

    // 更新相关的弹窗挂在根节点上，任何页面都能弹出来
    UpdateDialogHost(updateViewModel)
}

/**
 * 切换顶层标签。
 *
 * `popUpTo(startDestination) { saveState = true }` + `restoreState = true` 是标准写法，
 * 两个 `saveState` 缺一不可：
 *  - 切走时存住这个标签自己的状态（课表翻到第几周、笔记列表滚到哪）；
 *  - 切回来时恢复它，而不是重建。
 *
 * `popUpTo` 同时保证栈里只有「起始页 + 当前页」：否则「课表 → 设置 → 笔记 → 返回」
 * 会退回到设置 —— 用户按返回时预期是退出，不是回到另一个标签。
 */
private fun NavHostController.switchTab(tab: MainTab) {
    navigate(tab.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
