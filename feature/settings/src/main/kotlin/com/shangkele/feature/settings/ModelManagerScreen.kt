package com.shangkele.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * 模型管理。
 *
 * 建这个页面的直接原因：语音模型原本只能在「点转写」时被动下载，
 * 界面上既看不到状态、也没法主动下、更没法删；而 AI 摘要模型压根不存在，
 * 界面上却写着「未接模型」，被理解成了「模型没下载」。
 *
 * 这一页要把两件事彻底分开：
 *  - **语音识别**：已实现 —— 可以下载、可以暂停、可以删
 *  - **AI 摘要**：**功能还没写** —— 所以没有可下载的模型，不是缺文件，是缺代码
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelManagerScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenLlmConfig: () -> Unit = {},
    viewModel: ModelManagerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

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
                title = { Text("模型管理") },
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
            AsrModelCard(state, viewModel)

            Spacer(Modifier.height(20.dp))

            Text(
                text = "AI 摘要模型",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            LlmModelCard(onOpenLlmConfig)
        }
    }
}

@Composable
private fun AsrModelCard(state: ModelManagerUiState, viewModel: ModelManagerViewModel) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "语音识别模型",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = state.statusLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (state.asrReady) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }

            Spacer(Modifier.height(4.dp))
            Text(
                text = "SenseVoice Small · 约 ${state.totalLabel} · 词表与归一化参数已内置",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (state.downloading) {
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { state.fraction },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "支持断点续传，中途退出不会白下",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(12.dp))
            when {
                state.downloading -> OutlinedButton(onClick = viewModel::cancelDownload) {
                    Text("暂停下载")
                }

                state.asrReady -> OutlinedButton(onClick = viewModel::delete) {
                    Text("删除（省约 230MB）")
                }

                else -> Button(onClick = viewModel::download) {
                    Text(if (state.downloadedBytes > 0) "继续下载" else "开始下载")
                }
            }

            if (!state.asrReady && !state.downloading) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "不下载也能用课表、提醒和小组件。只有要把录音转成文字时才需要它。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * AI 摘要模型。
 *
 * **刻意不提供下载按钮** —— 因为文件下下来也没有代码去加载它。
 * 给个按钮会造成「下完就能用」的错误预期，那比直说"没做"更让人失望。
 */
@Composable
private fun LlmModelCard(onOpenLlmConfig: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "端侧跑不了，改用云端",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "AI 摘要需要大模型。把可行路径逐条试过，端侧这条路目前走不通：",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(6.dp))
            Bullet("推理引擎：onnxruntime-genai 与 MediaPipe 在可达的镜像上都不存在")
            Bullet("编译工具：本机没有 NDK 与 CMake，而 dl.google.com 不通，装不上")
            Bullet("模型文件：GGUF 反倒能下（Qwen2.5-1.5B 约 1GB），但没有引擎加载它")

            Spacer(Modifier.height(12.dp))
            Text(
                text = "所以这里**没有可下载的 AI 模型** —— 缺的不是文件，是引擎和工具链。" +
                    "上面那个语音模型是完全独立的一件事，它已经能用。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(12.dp))
            Text(
                text = "要真正的 AI 摘要，现在可行的做法是接一个云端大模型：" +
                    "填服务地址与 API Key 即可，不需要额外下载。",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(10.dp))
            Button(onClick = onOpenLlmConfig) { Text("去配置 AI 摘要") }

            Spacer(Modifier.height(8.dp))
            Text(
                text = "不配置也能用：摘要退回「规则抽取」，详情页里会标明它不是 AI 生成的。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Bullet(text: String) {
    Row(modifier = Modifier.padding(vertical = 2.dp)) {
        Text(text = "· ", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
        Text(text = text, style = MaterialTheme.typography.bodySmall)
    }
}
