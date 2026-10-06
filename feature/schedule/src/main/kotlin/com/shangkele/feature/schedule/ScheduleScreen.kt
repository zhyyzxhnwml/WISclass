package com.shangkele.feature.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shangkele.core.context.departure.DepartureAdvice
import com.shangkele.core.model.Course
import com.shangkele.core.model.TimetableLayout
import com.shangkele.feature.schedule.component.TimetableGrid
import com.shangkele.feature.schedule.component.WeekHeader
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleScreen(
    modifier: Modifier = Modifier,
    onOpenImport: () -> Unit = {},
    onOpenCalendar: () -> Unit = {},
    viewModel: ScheduleViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    // 用 State 包一层而不是直接捕获 Int，保证 pageCount 每次都读到最新值
    val totalWeeksState = remember { mutableStateOf(state.totalWeeks) }
    totalWeeksState.value = state.totalWeeks.coerceAtLeast(1)
    val pagerState = rememberPagerState(pageCount = { totalWeeksState.value })

    // 数据第一次到位时跳到当前周。刷新课表不会改变 hasData，所以不会把用户拽回本周。
    LaunchedEffect(state.hasData, state.totalWeeks) {
        if (state.hasData && state.totalWeeks > 0) {
            pagerState.scrollToPage((state.currentWeek - 1).coerceIn(0, state.totalWeeks - 1))
        }
    }

    // 翻页 → 通知 ViewModel，保证头部文案与详情卡的「非本周」判定一致
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { page ->
            viewModel.selectWeek(page + 1)
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "上课啦",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = state.semesterTitle.ifEmpty { "尚未导入课表" },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    if (!state.isCurrentWeek) {
                        TextButton(
                            onClick = {
                                scope.launch {
                                    pagerState.animateScrollToPage(
                                        (state.currentWeek - 1).coerceIn(0, state.totalWeeks - 1),
                                    )
                                }
                            },
                        ) {
                            Text("回到本周")
                        }
                    }
                    // 「导入」必须常驻。
                    // 之前它只在空状态里 —— 首次导入成功后就再没有入口，
                    // 换学期、课表调整、导错了都只能清空 App 数据。
                    // 「笔记」「设置」不在这里：它们是底部导航的顶层页面，常驻可见。
                    TextButton(onClick = onOpenImport) { Text("导入") }
                },
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when {
                state.loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))

                !state.hasData -> EmptyState(
                    onOpenImport = onOpenImport,
                    onLoadDemo = viewModel::loadDemoData,
                )

                else -> ScheduleContent(
                    state = state,
                    pagerState = pagerState,
                    onJumpToWeek = { week ->
                        scope.launch {
                            pagerState.animateScrollToPage(
                                (week - 1).coerceIn(0, state.totalWeeks - 1),
                            )
                        }
                    },
                    onOpenCalendar = onOpenCalendar,
                    onCourseClick = viewModel::toggleCourse,
                    onDismissDetail = viewModel::clearSelectedCourse,
                )
            }
        }
    }
}

/**
 * 校历未确认时的提示条。
 *
 * 措辞刻意不含「可能不准」这种软话：这一项错了就是**整张课表的本周判定全错**，
 * 而且不会有任何报错。要么去校准，要么明确忽略。
 */
@Composable
private fun CalendarHintBanner(onOpenCalendar: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "校历还没校准",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    text = "「第一周是哪天」没确认过，周次可能不对 —— 这会让本周的课判错。" +
                        "对照教务系统上的周次调一次即可。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            TextButton(onClick = onOpenCalendar) { Text("去校准") }
        }
    }
}

@Composable
private fun EmptyState(onOpenImport: () -> Unit, onLoadDemo: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "还没有课表",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "从学校教务系统导入，或先加载一份演示数据看看效果。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onOpenImport, modifier = Modifier.fillMaxWidth()) {
            Text("导入真实课表")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onLoadDemo, modifier = Modifier.fillMaxWidth()) {
            Text("加载演示数据")
        }
    }
}

@Composable
private fun ScheduleContent(
    state: ScheduleUiState,
    pagerState: PagerState,
    onJumpToWeek: (Int) -> Unit,
    onOpenCalendar: () -> Unit,
    onCourseClick: (Course) -> Unit,
    onDismissDetail: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // 校历没确认过就挂一条提示。不猜日期，但也不能装作没事 ——
        // 校历错会让「本周有没有这门课」全判错，而界面上看不出来。
        if (!state.calendarConfirmed) {
            CalendarHintBanner(onOpenCalendar)
        }

        state.nextCourse?.let { hint ->
            NextCourseCard(hint = hint, departure = state.departure)
        }

        WeekHeader(
            selectedWeek = state.selectedWeek,
            currentWeek = state.currentWeek,
            totalWeeks = state.totalWeeks,
            semesterStartEpochDay = state.semester?.startDateEpochDay ?: 0L,
            onJumpToWeek = onJumpToWeek,
            onOpenCalendar = onOpenCalendar,
        )

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f),
        ) { page ->
            val week = page + 1
            // 每页现算，避免把 20 周的排布结果全塞进 ViewModel 状态
            val layout = remember(state.allCourses, week) {
                TimetableLayout.layout(state.allCourses, week)
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    // 点空白处收起详情卡；课程块自己有 clickable，会先吃掉点击
                    .pointerInput(Unit) { detectTapGestures { onDismissDetail() } },
            ) {
                TimetableGrid(
                    weekdays = state.weekdayLabels,
                    startWeekday = state.startWeekday,
                    slots = state.timeSlots,
                    currentWeekCourses = layout.currentWeek,
                    offWeekCourses = layout.offWeek,
                    // 每列头顶标出那天具体几号，并对得上教务系统的日期
                    dayNumbers = state.weekDayNumbers,
                    dayMonths = state.weekDayMonths,
                    // 看的不是本周时传 -1，什么都不高亮 —— 否则翻到第3周
                    // 也会把「今天周三」那一列标亮，跟用户看的这周没关系
                    todayColumnIndex = state.todayColumn,
                    selectedCourseId = state.selectedCourse?.id,
                    onCourseClick = onCourseClick,
                    modifier = Modifier.background(MaterialTheme.colorScheme.surface),
                )
            }
        }

        state.selectedCourse?.let { course ->
            CourseDetailCard(
                course = course,
                offWeek = !course.occursInWeek(state.selectedWeek),
                onDismiss = onDismissDetail,
            )
        }
    }
}

@Composable
private fun NextCourseCard(hint: NextCourseHint, departure: DepartureAdvice?) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (hint.ongoing) "正在上课" else "下一节课",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = hint.courseName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    text = "${hint.teacher} · ${hint.room} · ${hint.timeLabel}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                departure?.let { advice ->
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = departureLine(hint, advice),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            if (!hint.ongoing) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "${hint.minutesUntilStart}",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Text(
                        text = "分钟后",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }
    }
}

/**
 * 出发建议文案。
 *
 * 正在上课时卡片显示的是当前这门课，而出发建议针对的是下一节，
 * 所以那种情况下要把课名带上，否则会让人以为是这门课的建议。
 */
private fun departureLine(hint: NextCourseHint, advice: DepartureAdvice): String =
    if (hint.ongoing && advice.courseName != hint.courseName) {
        "「${advice.courseName}」建议 ${advice.leaveLabel} 出发 · ${advice.reasonLabel}"
    } else {
        "建议 ${advice.leaveLabel} 出发 · ${advice.reasonLabel}"
    }

@Composable
private fun CourseDetailCard(course: Course, offWeek: Boolean, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = course.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
            DetailRow("教师", course.teacher)
            DetailRow("教室", course.roomLabel())
            DetailRow(
                "节次",
                "周${"一二三四五六日"[course.weekday - 1]} 第${course.startSection}-${course.endSection}节",
            )
            DetailRow("周次", formatWeeks(course.weeks))
            DetailRow("学分", "${course.credits}")
            course.courseType?.let { DetailRow("性质", it) }
            if (offWeek) DetailRow("提示", "本周不上这门课（灰显）")
            DetailRow("来源", course.source.name)
        }
    }
}

/** 归一化后的键与原始写法一致时就不重复展示，避免出现「3B301（3B301）」这种冗余。 */
private fun Course.roomLabel(): String {
    val key = roomKey?.takeIf { it.isNotBlank() } ?: return roomRaw
    return if (key == roomRaw) roomRaw else "$roomRaw（$key）"
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.padding(vertical = 2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(56.dp),
        )
        Text(text = value, style = MaterialTheme.typography.bodySmall)
    }
}

/** 把周次集合压缩成 "1-16" / "1,3,5-9" 这类可读文本。 */
private fun formatWeeks(weeks: Set<Int>): String {
    if (weeks.isEmpty()) return "—"
    val sorted = weeks.sorted()
    val parts = mutableListOf<String>()
    var start = sorted.first()
    var prev = start
    for (index in 1 until sorted.size) {
        val current = sorted[index]
        if (current == prev + 1) {
            prev = current
            continue
        }
        parts += if (start == prev) "$start" else "$start-$prev"
        start = current
        prev = current
    }
    parts += if (start == prev) "$start" else "$start-$prev"
    return parts.joinToString(",")
}
