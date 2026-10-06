package com.shangkele.feature.schedule

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shangkele.core.model.Assignment
import com.shangkele.core.model.WeekCalculator
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 日程：课与作业/待办合成的一条时间线。
 *
 * 界面上只画**真有的东西**：没课的日子不出现，两件事之间的空档也不画任何占位。
 * 于是「空着」本身就是「空余时间」的表达 —— 这也是用户要的形态。
 * 反过来做（把空格画满、或把没安排的日子也列出来）会把「有空」和「排满」在视觉上
 * 搅在一起，反而看不出哪天空着。
 */
@Composable
fun AgendaScreen(
    modifier: Modifier = Modifier,
    viewModel: AgendaViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(modifier = modifier.fillMaxSize()) {
        AgendaHeader(state)

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
        ) {
            // 过期的一定放最上面：这是最该被看见、也最容易被漏掉的一类
            if (state.overdue.isNotEmpty()) {
                item { GroupTitle("已经过期", MaterialTheme.colorScheme.error) }
                items(state.overdue, key = { "overdue-${it.id}" }) { todo ->
                    TodoRow(todo, state.todayEpochDay, viewModel::setDone)
                }
            }

            state.days.forEach { day ->
                item(key = "day-${day.dateEpochDay}") { DayHeader(day, state.todayEpochDay) }
                itemsIndexed(day.classes) { _, item -> ClassRow(item) }
                items(day.todos, key = { "todo-${it.id}" }) { todo ->
                    TodoRow(todo, state.todayEpochDay, viewModel::setDone)
                }
            }

            // 「期末之前交」这种解析不出日期的，不能丢，也不能硬塞进某一天
            if (state.undated.isNotEmpty()) {
                item { GroupTitle("还没写时间的") }
                items(state.undated, key = { "undated-${it.id}" }) { todo ->
                    TodoRow(todo, state.todayEpochDay, viewModel::setDone)
                }
            }

            if (state.isEmpty) {
                item { EmptyHint(state.hasSemester) }
            }
        }
    }
}

@Composable
private fun AgendaHeader(state: AgendaViewModel.AgendaUiState) {
    val today = LocalDate.ofEpochDay(state.todayEpochDay)
    val todayLabel = when {
        !state.hasSemester -> "还没导入课表，现在只看得到作业"
        state.currentWeek != null ->
            "%s %s · 第 %d 周".format(
                today.format(DateTimeFormatter.ofPattern("MM-dd")),
                WeekCalculator.weekdayLabel(state.todayEpochDay),
                state.currentWeek,
            )

        else -> today.format(DateTimeFormatter.ofPattern("MM-dd"))
    }

    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Text(
            text = "日程",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.padding(top = 2.dp))
        Text(
            text = todayLabel,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.scheduledDayCount > 0) {
            Text(
                text = "两周内 ${state.scheduledDayCount} 天有安排，没列出来的日子都是空的",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun GroupTitle(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = color,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun DayHeader(day: AgendaViewModel.AgendaDay, today: Long) {
    val relative = day.dateEpochDay - today
    val label = when (relative) {
        0L -> "今天"
        1L -> "明天"
        2L -> "后天"
        else -> WeekCalculator.weekdayLabel(day.dateEpochDay)
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = if (day.isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = LocalDate.ofEpochDay(day.dateEpochDay).format(DateTimeFormatter.ofPattern("MM-dd")),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ClassRow(item: AgendaViewModel.AgendaClass) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = item.timeLabel,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(96.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.courseName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            if (item.room.isNotBlank()) {
                Text(
                    text = item.room,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 一条待办。
 *
 * 点一下就打勾 —— 日程页是复习/写作业时最可能顺手勾掉它的地方，
 * 再绕回笔记详情页去打勾没必要。
 */
@Composable
private fun TodoRow(item: Assignment, today: Long, onToggle: (Long, Boolean) -> Unit) {
    val left = item.daysLeft(today)
    val overdue = !item.done && left != null && left < 0

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle(item.id, !item.done) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (item.done) "☑" else "☐",
            style = MaterialTheme.typography.titleMedium,
            color = if (item.done) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyMedium,
                textDecoration = if (item.done) TextDecoration.LineThrough else null,
            )
            Text(
                // 老师的原话和换算出的日期都摆出来，用户扫一眼就知道有没有理解错
                text = buildString {
                    append(item.dueLabel)
                    if (!item.done && left != null) {
                        append(" · ")
                        append(
                            when {
                                left < 0 -> "已过期 ${-left} 天"
                                left == 0L -> "今天到期"
                                else -> "还有 $left 天"
                            },
                        )
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (overdue) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

@Composable
private fun EmptyHint(hasSemester: Boolean) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "接下来两周没有安排 —— 这段时间都是空的。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!hasSemester) {
            Spacer(Modifier.padding(top = 6.dp))
            Text(
                text = "导入课表之后，这里会出现每天要上的课。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Spacer(Modifier.padding(top = 6.dp))
            Text(
                text = "作业会自己出现在这儿：拍下板书、或录完课转写一下就行。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
