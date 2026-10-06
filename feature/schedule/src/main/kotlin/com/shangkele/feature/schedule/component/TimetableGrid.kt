package com.shangkele.feature.schedule.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.shangkele.core.model.Course
import com.shangkele.core.model.TimeSlot
import com.shangkele.core.model.WeekGrid
import com.shangkele.feature.schedule.theme.CoursePalette

/**
 * 课表网格。
 *
 * 网格线走 [Canvas] 一次性绘制（120Hz 下避免逐格重绘，见 docs/07 §七），
 * 课程块与文字用绝对定位的 composable，保留点击语义与无障碍支持。
 *
 * 分成两层画：[currentWeekCourses] 正常配色，[offWeekCourses] 灰显并标注「非本周」。
 * 灰显的课由 `TimetableLayout` 保证不会和本周课程占同一格，因此不会叠色块。
 *
 * 行高目前统一；按作息时长做不等高是 W3 的事。
 */
@Composable
fun TimetableGrid(
    weekdays: List<String>,
    slots: List<TimeSlot>,
    currentWeekCourses: List<Course>,
    offWeekCourses: List<Course>,
    /** 选中周七天各自的「日」（周一→周日）；为空则表头只显示星期 */
    dayNumbers: List<Int> = emptyList(),
    /** 各列所属月份，用于在月份切换处标注，避免七列都重复写「10月」 */
    dayMonths: List<Int> = emptyList(),
    /** 今天在选中周的哪一列（0..6）；**-1 表示看的不是本周，什么都不高亮** */
    todayColumnIndex: Int = -1,
    selectedCourseId: Long?,
    onCourseClick: (Course) -> Unit,
    /**
     * 开学日是周几（1 = 周一 … 7 = 周日）。
     * 用它把 `course.weekday` 映射到列号 —— 第 1 周从周六开学时，
     * 「周一」的课要画在第 3 列而不是第 1 列。
     */
    startWeekday: Int = 1,
    modifier: Modifier = Modifier,
    sectionColumnWidth: Dp = 58.dp,
    rowHeight: Dp = 66.dp,
    headerHeight: Dp = 62.dp,
) {
    val dark = isSystemInDarkTheme()
    val dividerColor = MaterialTheme.colorScheme.outlineVariant
    val headerBackground = MaterialTheme.colorScheme.surfaceVariant

    if (slots.isEmpty()) {
        Box(
            modifier = modifier.fillMaxWidth().height(160.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "作息表为空，请先在设置中校准",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val dayCount = weekdays.size.coerceAtLeast(1)
    val rowCount = slots.size
    val gridHeight = headerHeight + rowHeight * rowCount
    val gap = 2.dp

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val dayWidth = (maxWidth - sectionColumnWidth) / dayCount

        Box(Modifier.fillMaxWidth().height(gridHeight)) {
            Canvas(Modifier.fillMaxWidth().height(gridHeight)) {
                val sectionPx = sectionColumnWidth.toPx()
                val headerPx = headerHeight.toPx()
                val rowPx = rowHeight.toPx()
                val dayPx = dayWidth.toPx()

                drawRect(color = headerBackground, size = Size(size.width, headerPx))

                for (index in 0..dayCount) {
                    val x = sectionPx + dayPx * index
                    drawLine(dividerColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
                }
                for (index in 0..rowCount) {
                    val y = headerPx + rowPx * index
                    drawLine(dividerColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                }
                drawLine(dividerColor, Offset(sectionPx, 0f), Offset(sectionPx, size.height), strokeWidth = 2f)
                drawLine(dividerColor, Offset(0f, headerPx), Offset(size.width, headerPx), strokeWidth = 2f)
            }

            weekdays.forEachIndexed { index, label ->
                val day = dayNumbers.getOrNull(index)
                val isToday = index == todayColumnIndex
                // 只在第一列或月份发生变化的那一列标月份
                val month = dayMonths.getOrNull(index)
                val showMonth = month != null &&
                    (index == 0 || dayMonths.getOrNull(index - 1) != month)

                Box(
                    modifier = Modifier
                        .offset(x = sectionColumnWidth + dayWidth * index, y = 0.dp)
                        .size(dayWidth, headerHeight),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (day != null) {
                            // 今天用一个圆角底色块圈出来 —— 和纸质课表上圈出今天的习惯一致
                            Box(
                                modifier = Modifier
                                    .background(
                                        color = if (isToday) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            Color.Transparent
                                        },
                                        shape = RoundedCornerShape(8.dp),
                                    )
                                    .padding(horizontal = 7.dp, vertical = 1.dp),
                            ) {
                                Text(
                                    text = "$day",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isToday) {
                                        MaterialTheme.colorScheme.onPrimary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                )
                            }
                            if (showMonth) {
                                Text(
                                    text = "${month}月",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            slots.forEachIndexed { index, slot ->
                Box(
                    modifier = Modifier
                        .offset(x = 0.dp, y = headerHeight + rowHeight * index)
                        .size(sectionColumnWidth, rowHeight),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(text = "${slot.section}", style = MaterialTheme.typography.labelLarge)
                        Text(
                            text = slot.startLabel(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = slot.endLabel(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // 先画灰显的，本周课程压在上面（理论上不重叠，这里只是保险）
            offWeekCourses.forEach { course ->
                placeCourse(
                    course = course,
                    slots = slots,
                    dayCount = dayCount,
                    dayWidth = dayWidth,
                    rowHeight = rowHeight,
                    headerHeight = headerHeight,
                    sectionColumnWidth = sectionColumnWidth,
                    gap = gap,
                    startWeekday = startWeekday,
                    offWeek = true,
                    selected = false,
                    dark = dark,
                    onClick = { onCourseClick(course) },
                )
            }

            currentWeekCourses.forEach { course ->
                placeCourse(
                    course = course,
                    slots = slots,
                    dayCount = dayCount,
                    dayWidth = dayWidth,
                    rowHeight = rowHeight,
                    headerHeight = headerHeight,
                    sectionColumnWidth = sectionColumnWidth,
                    gap = gap,
                    startWeekday = startWeekday,
                    offWeek = false,
                    selected = course.id != 0L && course.id == selectedCourseId,
                    dark = dark,
                    onClick = { onCourseClick(course) },
                )
            }
        }
    }
}

@Composable
private fun placeCourse(
    course: Course,
    slots: List<TimeSlot>,
    dayCount: Int,
    dayWidth: Dp,
    rowHeight: Dp,
    headerHeight: Dp,
    sectionColumnWidth: Dp,
    gap: Dp,
    startWeekday: Int,
    offWeek: Boolean,
    selected: Boolean,
    dark: Boolean,
    onClick: () -> Unit,
) {
    val startIndex = slots.indexOfFirst { it.section == course.startSection }
    val endIndex = slots.indexOfFirst { it.section == course.endSection }
    if (startIndex < 0 || endIndex < 0) return

    val column = WeekGrid.columnOf(course.weekday, startWeekday)
    if (column !in 0 until dayCount) return

    val x = sectionColumnWidth + dayWidth * column
    val y = headerHeight + rowHeight * startIndex
    val blockHeight = rowHeight * (endIndex - startIndex + 1)

    CourseBlock(
        course = course,
        dark = dark,
        offWeek = offWeek,
        selected = selected,
        onClick = onClick,
        modifier = Modifier
            .offset(x = x + gap, y = y + gap)
            .size(dayWidth - gap * 2, blockHeight - gap * 2),
    )
}

@Composable
private fun CourseBlock(
    course: Course,
    dark: Boolean,
    offWeek: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = CoursePalette.color(course.colorSeed, dark)
    val container = when {
        offWeek -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        else -> accent.copy(alpha = if (dark) 0.26f else 0.14f)
    }
    val border = when {
        offWeek -> MaterialTheme.colorScheme.outlineVariant
        selected -> accent.copy(alpha = 1f)
        else -> accent.copy(alpha = 0.55f)
    }

    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = container,
        border = BorderStroke(if (selected) 2.dp else 1.dp, border),
    ) {
        Column(modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
            Text(
                text = course.name,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (offWeek) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = if (offWeek) 2 else 3,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Start,
            )
            Text(
                text = course.roomRaw,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // 单节的小块放不下额外标签，只在两节及以上显示
            if (offWeek && course.sectionSpan >= 2) {
                Text(
                    text = "非本周",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                )
            }
        }
    }
}
