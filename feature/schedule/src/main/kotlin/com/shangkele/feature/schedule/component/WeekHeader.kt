package com.shangkele.feature.schedule.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.shangkele.core.model.WeekCalculator

/**
 * 周次头部。
 *
 * 周次切换的主交互是**左右滑动翻页**（见 ScheduleScreen 的 HorizontalPager），
 * 这里只显示当前周次，并提供两种精确跳转：左右箭头（相邻周）和点击周次打开跳转面板。
 *
 * **必须显示日期区间**：校历错一周时，界面上除了周次数字之外没有任何线索 ——
 * 而周次数字本身就是错的，用户只能靠「感觉不对」发现。把 `09-28 ~ 10-04`
 * 摆出来，一眼就能和教务系统对照。
 */
@Composable
fun WeekHeader(
    selectedWeek: Int,
    currentWeek: Int,
    totalWeeks: Int,
    semesterStartEpochDay: Long,
    onJumpToWeek: (Int) -> Unit,
    /** 打开「校历设置」，周次对不上时用户从这儿过去改开学日期 */
    onOpenCalendar: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var showPicker by remember { mutableStateOf(false) }

    val dateRange = if (semesterStartEpochDay > 0) {
        WeekCalculator.weekRangeLabel(semesterStartEpochDay, selectedWeek)
    } else {
        ""
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(
            onClick = { onJumpToWeek(selectedWeek - 1) },
            enabled = selectedWeek > 1,
        ) {
            Text("‹ 上一周", style = MaterialTheme.typography.labelMedium)
        }

        Spacer(Modifier.weight(1f))

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.clickable { showPicker = true },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "第 $selectedWeek 周",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                if (selectedWeek == currentWeek) {
                    Text(
                        text = "  · 本周",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Text(
                text = if (dateRange.isEmpty()) "共 $totalWeeks 周" else "$dateRange · 共 $totalWeeks 周",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.weight(1f))

        TextButton(
            onClick = { onJumpToWeek(selectedWeek + 1) },
            enabled = selectedWeek < totalWeeks,
        ) {
            Text("下一周 ›", style = MaterialTheme.typography.labelMedium)
        }
    }

    if (showPicker) {
        WeekPickerDialog(
            selectedWeek = selectedWeek,
            currentWeek = currentWeek,
            totalWeeks = totalWeeks,
            semesterStartEpochDay = semesterStartEpochDay,
            onSelect = {
                showPicker = false
                onJumpToWeek(it)
            },
            onOpenCalendar = {
                showPicker = false
                onOpenCalendar()
            },
            onDismiss = { showPicker = false },
        )
    }
}

@Composable
private fun WeekPickerDialog(
    selectedWeek: Int,
    currentWeek: Int,
    totalWeeks: Int,
    semesterStartEpochDay: Long,
    onSelect: (Int) -> Unit,
    onOpenCalendar: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("周次") },
        text = {
            Column {
                SemesterStartInfo(
                    semesterStartEpochDay = semesterStartEpochDay,
                    onOpenCalendar = onOpenCalendar,
                )

                Spacer(Modifier.padding(top = 8.dp))
                HorizontalDivider()
                Spacer(Modifier.padding(top = 4.dp))

                LazyColumn(
                    modifier = Modifier.heightIn(max = 300.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items((1..totalWeeks).toList(), key = { it }) { week ->
                        val range = if (semesterStartEpochDay > 0) {
                            WeekCalculator.weekRangeLabel(semesterStartEpochDay, week)
                        } else {
                            ""
                        }
                        FilterChip(
                            selected = week == selectedWeek,
                            onClick = { onSelect(week) },
                            modifier = Modifier.fillMaxWidth(),
                            label = {
                                Text(
                                    text = buildString {
                                        append("第 $week 周")
                                        if (range.isNotEmpty()) append("  $range")
                                        if (week == currentWeek) append("  · 本周")
                                    },
                                )
                            },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

/**
 * 当前用的开学日期。
 *
 * 这里**刻意不再提供「按周加减」的校准** —— 用户想表达的是「我 9 月 2 号开学」，
 * 让他去数、去加减第几周是绕弯，绕回来还可能算错。
 * 改日期统一去「设置 → 校历设置」用日期选择器，这里只把当前值摆出来，
 * 让用户能判断上面列出的周次区间对不对。
 */
@Composable
private fun SemesterStartInfo(semesterStartEpochDay: Long, onOpenCalendar: () -> Unit) {
    Column {
        Text(
            text = "校历",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.padding(top = 4.dp))
        Text(
            text = if (semesterStartEpochDay > 0) {
                "开学日期：${WeekCalculator.fullDate(semesterStartEpochDay)}" +
                    "（${WeekCalculator.weekdayLabel(semesterStartEpochDay)}）"
            } else {
                "开学日期还没设置"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.padding(top = 4.dp))
        Text(
            text = "上面这些周次的日期区间是按这个开学日期推的。对不上就改它。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.padding(top = 4.dp))
        TextButton(onClick = onOpenCalendar) {
            Text("改开学日期 ›")
        }
    }
}
