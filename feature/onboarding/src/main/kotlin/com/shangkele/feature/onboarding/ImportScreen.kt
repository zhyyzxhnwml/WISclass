package com.shangkele.feature.onboarding

import android.app.DatePickerDialog
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shangkele.core.jwgl.JwglPageScript
import com.shangkele.core.model.SemesterResolver
import com.shangkele.core.model.WeekCalculator
import com.shangkele.feature.onboarding.component.JwglWebController
import com.shangkele.feature.onboarding.component.JwglWebView
import java.time.LocalDate

/**
 * 课表导入页。
 *
 * 两步走：先确认学期（学年/学期决定接口参数 xnm/xqm，错了一门课都抓不到），
 * 再去官方页面登录并抓取。
 *
 * 抓取放在 WebView 页面内做（见 `core:jwgl` 的 JwglPageScript），
 * 所以「开始导入」按钮执行的是注入脚本，而不是直接发网络请求。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(
    onBack: () -> Unit,
    onImported: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ImportViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(if (state.tab == ImportTab.WebLogin) "登录教务系统" else "导入课表")
                },
                navigationIcon = {
                    TextButton(
                        onClick = {
                            if (state.tab == ImportTab.WebLogin) viewModel.backToSetup() else onBack()
                        },
                    ) {
                        Text("返回")
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when (state.tab) {
                ImportTab.Setup -> SetupContent(state, viewModel, onImported)
                ImportTab.WebLogin -> WebLoginContent(state, viewModel)
            }
        }
    }
}

@Composable
private fun SetupContent(
    state: ImportUiState,
    viewModel: ImportViewModel,
    onImported: () -> Unit,
) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text(
            text = "从学校教务系统导入真实课表",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "登录在教务系统官方页面完成，密码不会被本应用保存；" +
                "只保存登录会话，并用系统密钥库加密后落盘。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(16.dp))
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("确认学期", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = state.semesterName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "开学日期：${LocalDate.ofEpochDay(state.startEpochDay)}" +
                                "（${WeekCalculator.weekdayLabel(state.startEpochDay)}）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "这是按常规校历猜的。不是你学校的就改一下 —— 周次全按它算。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(
                        onClick = {
                            pickStartDate(context, state.startEpochDay, viewModel::setStartDate)
                        },
                    ) {
                        Text("改")
                    }
                }

                Spacer(Modifier.height(12.dp))
                Text("学年", style = MaterialTheme.typography.labelMedium)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    state.yearOptions.forEach { year ->
                        FilterChip(
                            selected = state.xnm == year,
                            onClick = { viewModel.selectYear(year) },
                            label = { Text(year) },
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
                Text("学期", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SemesterResolver.TERM_OPTIONS.forEach { (code, label) ->
                        FilterChip(
                            selected = state.xqm == code,
                            onClick = { viewModel.selectTerm(code) },
                            label = { Text(label) },
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Button(
            onClick = viewModel::openWebLogin,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("登录教务系统并导入")
        }

        if (state.hasSavedSession) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = viewModel::openWebLoginForRefresh,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("用已保存的登录刷新课表")
            }
            Text(
                text = "会打开教务系统页面并自动抓取。如果登录已经过期，" +
                    "直接在弹出的页面里重新登录再点一次导入即可。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.savedEndpoint?.let { endpoint ->
                Text(
                    text = "已记住接口：$endpoint",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(
                onClick = viewModel::forgetLogin,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("忘记登录信息")
            }
        }

        state.detected?.takeIf { it.hasAnything }?.let { detected ->
            Spacer(Modifier.height(16.dp))
            DetectedHintsCard(detected, viewModel)
        }

        Spacer(Modifier.height(16.dp))
        StatusBlock(state.status, onImported, viewModel::dismissStatus)
    }
}

/**
 * 自动识别到的时间线索，等用户确认。
 *
 * **不自动应用**：周次错一周会让整张课表的「本周有没有这门课」全判错，
 * 作息错会让上课静音和「该出发了」在错误时刻触发 —— 两者都不报错，
 * 只会安静地给错信息。所以先把识别结果摆出来。
 */
/**
 * 系统日期选择器。
 *
 * 与「设置 → 校历设置」里的是同一套做法（那边是另一个模块的私有实现，
 * 不为这十几行代码拉一条模块依赖）。
 */
private fun pickStartDate(context: Context, currentEpochDay: Long, onPicked: (Long) -> Unit) {
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
private fun DetectedHintsCard(detected: DetectedHintsUi, viewModel: ImportViewModel) {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "从教务页面识别到",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(6.dp))

            if (detected.hasCalendar) {
                Text(
                    text = "教务系统显示「现在是第 ${detected.currentWeek} 周」" +
                        " → 第 1 周落在 ${LocalDate.ofEpochDay(detected.startEpochDay!!)} 那一周",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = "周次从**开学那天**起算，每 7 天一周（开学日是周六，第 1 周就是周六 ~ 周五），" +
                        "所以最后还得你指出开学是哪一天。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Button(
                    onClick = {
                        pickStartDate(context, detected.startEpochDay!!) {
                            viewModel.applyDetectedCalendar(it)
                        }
                    },
                ) {
                    Text("选开学日期")
                }
            }

            if (detected.hasSlots) {
                if (detected.hasCalendar) Spacer(Modifier.height(12.dp))
                val preview = detected.slots.take(4).joinToString("　") {
                    "第${it.section}节 ${it.rangeLabel()}"
                }
                Text(
                    text = "作息时间：$preview" + if (detected.slots.size > 4) " …" else "",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (detected.assumed) {
                    Text(
                        text = "页面只给了分块时间，下面的逐节时间是按每节 45 分钟推算的，请核对",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                detected.differences.take(5).forEach { d ->
                    Text(
                        text = "· 第${d.section}节：现在 ${d.current} → 识别为 ${d.detected}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (detected.differences.isEmpty()) {
                    Text(
                        text = "与当前作息一致，无需改动",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Button(onClick = viewModel::applyDetectedTimeSlots) { Text("采用这个作息时间") }
            }

            if (!detected.hasCalendar && !detected.hasSlots) {
                Text(
                    text = if (detected.pageTextLength == 0) {
                        "页面文本没读到（注入脚本可能没生效）"
                    } else {
                        "页面上没有明确的「本周是第几周」。课表的周次选择器里会列出全部周次，" +
                            "那种不能被当成当前周次 —— 请直接到设置里选开学日期。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            detected.notes.forEach {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "· $it",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // 识别不准时最有用的一块：把教务页面原文摆出来。
            // 之前几轮「第一周到底是几月几号」全靠猜，就是因为看不到页面上到底写了什么。
            if (detected.pageSample.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                var expanded by remember { mutableStateOf(false) }
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "收起教务页面原文" else "看不清？展开教务页面原文")
                }
                if (expanded) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 220.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        SelectionContainer {
                            Text(
                                text = detected.pageSample,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            TextButton(onClick = viewModel::dismissDetected) { Text("忽略") }
        }
    }
}

@Composable
private fun WebLoginContent(state: ImportUiState, viewModel: ImportViewModel) {
    val controller = remember { JwglWebController() }
    var progress by remember { mutableStateOf(100) }
    var canGoBack by remember { mutableStateOf(false) }

    // 让系统返回键先走 WebView 历史，避免一点返回就整个退出登录页
    BackHandler(enabled = canGoBack) { controller.goBack() }

    fun startFetch() {
        viewModel.beginWebImport()
        controller.evaluate(JwglPageScript.buildFetchScript(state.xnm, state.xqm))
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (progress < 100) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        JwglWebView(
            controller = controller,
            modifier = Modifier.weight(1f),
            onProgressChanged = { progress = it },
            onPageFinishedWith = { url ->
                canGoBack = controller.canGoBack()
                // 带会话进来时页面一加载完就自动抓一次，用户不用再点按钮。
                // 落在登录页说明会话已过期，就等用户手动登录后再抓。
                if (state.autoFetchPending && !isLoginPage(url)) {
                    viewModel.consumeAutoFetch()
                    startFetch()
                }
                // 顺便读一次页面上的时间线索（当前周次 / 作息时间）。
                // 读不到就什么都不发生，不会打扰用户 —— 解析完还得用户点确认才会写入。
                if (!isLoginPage(url)) {
                    controller.evaluate(JwglPageScript.READ_PAGE_HINTS)
                }
            },
            onScheduleJson = viewModel::ingestScheduleJson,
            onFetchFailed = viewModel::onWebFetchFailed,
            onPageHints = viewModel::ingestPageHints,
        )

        Surface(tonalElevation = 3.dp) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = if (state.autoFetchPending) {
                        "正在尝试用已保存的登录自动抓取…"
                    } else {
                        "登录成功后点下面的按钮抓取课表；" +
                            "也可以直接在页面里点「个人课表查询」，抓到数据会自动导入。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { startFetch() },
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (state.busy) "正在抓取课表…" else "我已登录，开始导入")
                }

                val status = state.status
                if (status is ImportStatus.Failure) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = status.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

/** 判断当前是不是停在登录/统一认证页面上。 */
private fun isLoginPage(url: String): Boolean =
    url.contains("login_slogin", ignoreCase = true) ||
        url.contains("authserver", ignoreCase = true)

@Composable
private fun StatusBlock(
    status: ImportStatus,
    onImported: () -> Unit,
    onDismiss: () -> Unit,
) {
    when (status) {
        is ImportStatus.Idle -> Unit

        is ImportStatus.Running -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            Text("正在从教务系统抓取课表…", style = MaterialTheme.typography.bodyMedium)
        }

        is ImportStatus.Success -> Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "导入成功",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(4.dp))
                status.studentName?.let {
                    Text("同学：$it", style = MaterialTheme.typography.bodySmall)
                }
                Text("共 ${status.courseCount} 条课程记录", style = MaterialTheme.typography.bodySmall)
                status.sourceUrl?.let {
                    Text(
                        text = "数据来源：$it",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (status.changes.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "检测到 ${status.changes.size} 处课表变化",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    status.changes.take(5).forEach { change ->
                        Text(
                            text = "· ${change.courseName}：${change.title}（${change.detail}）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (status.skipped.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "有 ${status.skipped.size} 条记录没能识别",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    status.skipped.take(3).forEach {
                        Text(
                            text = "· $it",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                Button(onClick = onImported, modifier = Modifier.fillMaxWidth()) {
                    Text("去看课表")
                }
            }
        }

        is ImportStatus.Failure -> Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
            ),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "导入失败",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = status.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onDismiss) { Text("知道了") }
            }
        }
    }
}
