package com.shangkele.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** 正在编辑的字段。 */
private data class Editing(val section: Int, val isStart: Boolean, val minutes: Int)

/**
 * 作息表校准页。
 *
 * 这是全项目**容错成本最高的一个设置**：节次时间错了，课表显示、上课自动静音、
 * 「该出发了」会一起错，而且不会有任何报错。所以：
 *  - 顶部直接把「第 1 节几点开始」摆出来，一眼能核对
 *  - 校验出的问题就标在对应那一行上，不用自己去算
 *  - 改一次存一次，没有「忘了点保存」
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeSlotEditorScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TimeSlotEditorViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var editing by remember { mutableStateOf<Editing?>(null) }

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
                title = { Text("作息表") },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
                actions = {
                    TextButton(onClick = viewModel::restoreDefaults) { Text("恢复默认") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            SummaryCard(state)

            if (state.rows.isEmpty()) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text(
                        text = if (state.loaded) {
                            "还没有课表。导入课表后会自动生成一份默认作息，再来这里核对。"
                        } else {
                            "读取中…"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        bottom = 24.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(state.rows, key = { it.section }) { row ->
                        TimeSlotRowItem(
                            row = row,
                            onEdit = { isStart ->
                                editing = Editing(
                                    section = row.section,
                                    isStart = isStart,
                                    minutes = if (isStart) row.startMinutes else row.endMinutes,
                                )
                            },
                        )
                    }
                }
            }
        }
    }

    editing?.let { target ->
        TimeSlotPickerDialog(
            initialMinutes = target.minutes,
            onConfirm = {
                viewModel.setTime(target.section, target.isStart, it)
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun SummaryCard(state: TimeSlotEditorUiState) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (state.hasIssue) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.primaryContainer
            },
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            if (state.rows.isNotEmpty()) {
                Text(
                    text = "第 1 节 ${state.firstStartLabel} 开始 · 最后一节 ${state.lastEndLabel} 结束",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(4.dp))
            }
            Text(
                text = if (state.hasIssue) {
                    "下面标红的时间有问题。作息不对会让上课静音和「该出发了」在错误的时刻触发，" +
                        "而且不会有报错提示，请务必核对。"
                } else {
                    "请对照学校实际作息核对每一节。改完立刻生效，会自动重排上课静音与出发提醒。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TimeSlotRowItem(row: TimeSlotRow, onEdit: (isStart: Boolean) -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = if (row.issue != null) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
    ) {
        Column(modifier = Modifier.padding(vertical = 8.dp, horizontal = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "第 ${row.section} 节",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.width(72.dp),
                )
                TimeField(label = "开始", value = row.startLabel, onClick = { onEdit(true) })
                Text(text = "→", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 8.dp))
                TimeField(label = "结束", value = row.endLabel, onClick = { onEdit(false) })
            }
            row.issue?.let {
                Text(
                    text = "⚠ $it",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 72.dp, top = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun TimeField(label: String, value: String, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeSlotPickerDialog(
    initialMinutes: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val pickerState = rememberTimePickerState(
        initialHour = (initialMinutes / 60).coerceIn(0, 23),
        initialMinute = (initialMinutes % 60).coerceIn(0, 59),
        is24Hour = true,
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择时间") },
        text = { TimePicker(state = pickerState) },
        confirmButton = {
            TextButton(onClick = { onConfirm(pickerState.hour * 60 + pickerState.minute) }) {
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
