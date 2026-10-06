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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * 设置页：保活与权限清单 + 自动静音开关。
 *
 * 荣耀（MagicOS）的后台策略很激进，不做这套引导，静音闹钟和后续的桌面小组件
 * 都活不下来。见 docs/07-荣耀200适配清单.md §三。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    onOpenTimeSlots: () -> Unit = {},
    onOpenModels: () -> Unit = {},
    onOpenImport: () -> Unit = {},
    onOpenCalendar: () -> Unit = {},
    /** 检查更新。更新逻辑在 App 层（它需要 PackageManager 与安装权限），这里只转达点击。 */
    onCheckUpdate: () -> Unit = {},
    appVersion: String = "",
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // 从系统设置页返回时自动重新检查，省得用户手动刷新
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            // 顶层页面，没有「返回」——它的入口是底部导航
            TopAppBar(title = { Text("设置") })
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onCheckUpdate),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "检查更新",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "›",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = if (appVersion.isBlank()) {
                            "点一下看看有没有新版本"
                        } else {
                            "当前 $appVersion。开 App 时也会自动查一次（6 小时一次）。"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenImport),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "课表导入与刷新",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "进入 ›",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "重新从教务系统拉一次课表。换学期、课表有调整、或上次导入不全时用这里。" +
                            "已导入的课表不会被清空，只是覆盖更新。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenCalendar),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "校历校准",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "校准 ›",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "第一周是哪天。这一项错了，整张课表的「本周有没有这门课」会全判错，" +
                            "而且界面上看不出来。建议对照教务系统核对一次。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenTimeSlots),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "作息表",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "校准 ›",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "各节次几点上、几点下。这是全项目最容易填错、错了又最不报错的一项：" +
                            "时间不对，课表显示、上课自动静音、「该出发了」会一起错。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenModels),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "模型管理",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "查看 ›",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "语音识别模型的下载、暂停与删除；AI 摘要改为接入云端大模型，" +
                            "同一页里可以配置服务地址与 API Key。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                text = "保活与权限",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "荣耀的后台管理比较激进。下面几项建议全部打开，否则课表提醒、" +
                    "自动静音和桌面小组件都可能不生效。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(12.dp))
            state.rows.forEach { row ->
                PermissionCard(
                    row = row,
                    onOpenSettings = {
                        runCatching { context.startActivity(viewModel.settingsIntent(row.permission)) }
                    },
                )
                Spacer(Modifier.height(8.dp))
            }

            Spacer(Modifier.height(8.dp))
            ToggleCard(
                title = "上课自动静音",
                description = "进入上课时间自动开勿扰，下课自动恢复。" +
                    "你自己手动开的勿扰不会被我们关掉。",
                checked = state.autoSilenceEnabled,
                onCheckedChange = viewModel::setAutoSilence,
                statusText = autoSilenceStatus(state),
            )
            Spacer(Modifier.height(8.dp))
            ToggleCard(
                title = "该出发了",
                description = "按「要不要换楼」和「课间有多长」推算该几点出门，提前发通知。" +
                    "不需要标地图位置。",
                checked = state.departureReminderEnabled,
                onCheckedChange = viewModel::setDepartureReminder,
                statusText = departureStatus(state),
            )

            Spacer(Modifier.height(16.dp))
            Text(
                text = "上课啦是个人自用工具，非学校官方应用。" +
                    "登录在教务系统官方页面完成，密码不会被保存；" +
                    "课表数据与登录会话只存在本机。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun autoSilenceStatus(state: SettingsUiState): String = when {
    state.currentCourseName != null && state.currentlySilent ->
        "当前状态：正在上「${state.currentCourseName}」，已静音"

    state.currentCourseName != null ->
        "当前状态：正在上「${state.currentCourseName}」，但未静音（可能缺勿扰访问权）"

    state.currentlySilent -> "当前状态：静音中（课间会恢复）"

    else -> "当前状态：未静音"
}

private fun departureStatus(state: SettingsUiState): String {
    val advice = state.departureAdvice
        ?: return "当前没有可提醒的课程（今天没课或今天的课已上完）"
    return "下一节：${advice.startLabel} 在 ${advice.roomLabel}，" +
        "建议 ${advice.leaveLabel} 出发（${advice.reasonLabel}）"
}

@Composable
private fun ToggleCard(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    statusText: String,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = checked, onCheckedChange = onCheckedChange)
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun PermissionCard(row: PermissionRow, onOpenSettings: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (row.granted) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                MaterialTheme.colorScheme.secondaryContainer
            },
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = row.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (row.granted) "已开启" else "未开启",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (row.granted) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = row.reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!row.granted) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = row.howTo,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                TextButton(
                    onClick = onOpenSettings,
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text("去设置")
                }
            }
        }
    }
}
