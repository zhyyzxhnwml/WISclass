package com.shangkele.feature.settings

import android.app.DatePickerDialog
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalDate

/**
 * 校历设置。
 *
 * **只做一件事：设开学日期。**
 *
 * 之前这一页放的是「按教务系统上的周次调」，那是个绕弯的设计：
 * 用户想说的就是「我 9 月 2 号开学」，让他去数、去加减第几周，
 * 绕回来还可能算错。直接给日期选择器就够了。
 *
 * 开学日未必是周一（比如 8/29 是周六），而**周次就是从这一天起算的**：
 * 第 1 周 = 开学日 + 0~6 天，第 2 周 = +7~13 天，以此类推，
 * 绝不跳到那一周的周一去。见 [com.shangkele.core.model.WeekCalculator.weekStartEpochDay]。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CalendarViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("校历设置") },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = state.semesterName.ifEmpty { "还没有课表" },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(8.dp))
                    InfoRow("开学日期", state.startDateLabel)
                    if (state.startWeekdayLabel.isNotEmpty()) {
                        InfoRow("这天是", state.startWeekdayLabel)
                    }
                    InfoRow("今天算作", "第 ${state.currentWeek} 周")
                    if (state.currentWeekRange.isNotEmpty()) {
                        InfoRow("本周日期", state.currentWeekRange)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = if (state.confirmed) {
                            "✅ 已确认"
                        } else {
                            "⚠ 还没确认过。上面的日期是自动识别的，请核对。"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (state.confirmed) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "开学日期",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "选学校开学的那一天。校历上写「第一周」的那几天，" +
                            "选第一天就行。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = {
                            pickDate(context, state.startEpochDay, viewModel::setStartDate)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("选择日期")
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "开学日不是周一也没关系，这里如实存你选的日期。" +
                            "周次会按它所在那一周的周一计算，不会改动这个日期。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (!state.confirmed && state.startEpochDay > 0) {
                Spacer(Modifier.height(16.dp))
                TextButton(
                    onClick = viewModel::confirm,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("上面的日期是对的，不用改")
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                text = "为什么不让 App 自己猜开学日期：第一周是哪天只有学校知道，任何默认值都是猜。" +
                    "猜错的后果是「本周有没有这门课」全判错，而界面上只会安静地显示一个错误的周次。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 系统日期选择器。
 *
 * 刻意用平台自带的 [DatePickerDialog] 而不是 Compose 的 DatePicker：
 * 它是稳定 API、不需要 experimental opt-in，日历与年份切换的手感也是用户熟悉的。
 */
private fun pickDate(
    context: Context,
    currentEpochDay: Long,
    onPicked: (Long) -> Unit,
) {
    val initial = LocalDate.ofEpochDay(
        if (currentEpochDay > 0) currentEpochDay else LocalDate.now().toEpochDay(),
    )
    DatePickerDialog(
        context,
        { _, year, month, dayOfMonth -> onPicked(LocalDate.of(year, month + 1, dayOfMonth).toEpochDay()) },
        initial.year,
        initial.monthValue - 1,
        initial.dayOfMonth,
    ).show()
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}
