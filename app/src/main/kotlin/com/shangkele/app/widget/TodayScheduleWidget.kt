package com.shangkele.app.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.shangkele.app.MainActivity

/**
 * 今日课表小组件。
 *
 * 设计上刻意**不用计时器**：MagicOS 对小组件刷新有节流，做秒级倒计时会卡住不动。
 * 改成展示「今天有哪些课 + 哪节是下一节」，由
 *  - 组件自身的 updatePeriodMillis（30 分钟，系统最小值）
 *  - 每次打开 App
 * 两处驱动刷新就够用了。
 *
 * **列表可上下滑动**：之前只显示前 3 节、剩下的写成「还有 N 节」，
 * 一上午四节课就永远看不到第四节。现在整列可滚，有多少显示多少。
 *
 * 见 docs/07-荣耀200适配清单.md §六。
 */
class TodayScheduleWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val state = runCatching { WidgetDataLoader.load(context) }
            .getOrElse {
                WidgetState(
                    weekLabel = "",
                    courses = emptyList(),
                    emptyHint = "读取课表失败，打开 App 刷新一次",
                    hasSemester = false,
                )
            }
        provideContent { WidgetContent(state) }
    }
}

private val Primary = ColorProvider(Color(0xFF3B6FD8))
private val TextMain = ColorProvider(Color(0xFF111827))
private val TextSub = ColorProvider(Color(0xFF6B7280))

@Composable
private fun WidgetContent(state: WidgetState) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            // 不用纯白：MagicOS 护眼模式会拉色温，纯白观感偏差明显
            .background(Color(0xFFFAFAFA))
            .cornerRadius(16.dp)
            .padding(12.dp),
    ) {
        // 只有标题行可点，不整块可点 —— 否则点击会和列表的滚动手势抢事件
        Row(
            modifier = GlanceModifier
                .fillMaxWidth()
                .clickable(actionStartActivity<MainActivity>()),
            verticalAlignment = Alignment.Vertical.CenterVertically,
        ) {
            Text(
                text = "上课啦",
                style = TextStyle(color = Primary, fontSize = 13.sp, fontWeight = FontWeight.Bold),
            )
            if (state.weekLabel.isNotEmpty()) {
                Spacer(GlanceModifier.width(6.dp))
                Text(text = state.weekLabel, style = TextStyle(color = TextSub, fontSize = 11.sp))
            }
            // TODO(下一轮)：这里会加一个「拍照」入口。
            // 现在先不放 —— 拍照功能本身还没做，放个点了没反应的按钮比不放更糟。
        }

        Spacer(GlanceModifier.height(8.dp))

        if (state.courses.isEmpty()) {
            Text(text = state.emptyHint, style = TextStyle(color = TextSub, fontSize = 13.sp))
            return@Column
        }

        LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
            items(state.courses) { course ->
                CourseRow(course)
                Spacer(GlanceModifier.height(6.dp))
            }
        }
    }
}

@Composable
private fun CourseRow(course: WidgetCourse) {
    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.Vertical.CenterVertically,
    ) {
        Box(
            modifier = GlanceModifier
                .width(3.dp)
                .height(26.dp)
                .background(if (course.isNext) Color(0xFF3B6FD8) else Color(0xFFD1D5DB))
                .cornerRadius(2.dp),
        ) {}

        Spacer(GlanceModifier.width(8.dp))

        Column(modifier = GlanceModifier.defaultWeight()) {
            Row(verticalAlignment = Alignment.Vertical.CenterVertically) {
                Text(text = course.timeLabel, style = TextStyle(color = TextSub, fontSize = 10.sp))
                when {
                    course.ongoing -> {
                        Spacer(GlanceModifier.width(4.dp))
                        Text(text = "进行中", style = TextStyle(color = Primary, fontSize = 10.sp))
                    }

                    course.isNext -> {
                        Spacer(GlanceModifier.width(4.dp))
                        Text(text = "下一节", style = TextStyle(color = Primary, fontSize = 10.sp))
                    }
                }
            }
            Text(
                text = course.name,
                style = TextStyle(color = TextMain, fontSize = 13.sp, fontWeight = FontWeight.Medium),
                maxLines = 1,
            )
            Text(text = course.room, style = TextStyle(color = TextSub, fontSize = 10.sp), maxLines = 1)
        }
    }
}
