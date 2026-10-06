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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shangkele.core.ai.llm.LlmConfig

/**
 * AI 摘要配置（云端大模型）。
 *
 * 之所以把「取模型列表」做成按钮而不是写死模型名：聚合平台的模型会增删改名，
 * 写死一个名字过几个月就失效，而用户不知道去哪里改。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LlmConfigScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LlmConfigViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("AI 摘要") },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            PrivacyNotice()

            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "开启 AI 摘要",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "开启后，转写完成会自动调用云端模型整理要点。关掉则用本机的规则抽取。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = state.enabled, onCheckedChange = viewModel::setEnabled)
            }

            Spacer(Modifier.height(16.dp))
            SectionTitle("服务地址")
            OutlinedTextField(
                value = state.baseUrl,
                onValueChange = viewModel::setBaseUrl,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Base URL") },
                supportingText = {
                    Text("填到 /v1 为止，不要带 /chat/completions", style = MaterialTheme.typography.labelSmall)
                },
            )
            Spacer(Modifier.height(4.dp))
            LlmConfig.KNOWN_BASE_URLS.forEach { url ->
                if (url != state.baseUrl) {
                    Text(
                        text = "用这个：$url",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clickable { viewModel.setBaseUrl(url) }
                            .padding(vertical = 4.dp),
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            SectionTitle("API Key")
            if (state.hasApiKey) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "已保存：${state.maskedKey ?: "****"}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = viewModel::clearApiKey) { Text("删除") }
                }
            }
            OutlinedTextField(
                value = state.apiKeyInput,
                onValueChange = viewModel::setApiKeyInput,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(if (state.hasApiKey) "换一个 Key" else "粘贴 API Key") },
                visualTransformation = PasswordVisualTransformation(),
                supportingText = {
                    Text(
                        text = "用 AES-256-GCM 加密后存在本机，密钥由系统 Keystore 保管，不会上传",
                        style = MaterialTheme.typography.labelSmall,
                    )
                },
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = viewModel::saveApiKey,
                enabled = state.apiKeyInput.isNotBlank(),
            ) { Text("保存 Key") }

            Spacer(Modifier.height(16.dp))
            SectionTitle("模型")
            OutlinedTextField(
                value = state.model,
                onValueChange = viewModel::setModel,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("模型名") },
                supportingText = {
                    Text("可以手填，也可以点下面从平台拉取", style = MaterialTheme.typography.labelSmall)
                },
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = viewModel::loadModels,
                    enabled = state.canFetchModels && !state.loadingModels,
                ) {
                    if (state.loadingModels) {
                        CircularProgressIndicator(modifier = Modifier.height(16.dp).padding(end = 8.dp))
                    }
                    Text(if (state.loadingModels) "获取中…" else "从平台获取模型列表")
                }
                Button(
                    onClick = viewModel::testConnection,
                    enabled = state.canTest && !state.testing,
                ) { Text(if (state.testing) "测试中…" else "测试连接") }
            }

            if (state.models.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Card(modifier = Modifier.fillMaxWidth()) {
                    // 用普通 Column 而不是 LazyColumn：外层已经 verticalScroll，
                    // 嵌套滚动容器会抛 IllegalStateException
                    Column {
                        state.models.forEachIndexed { index, name ->
                            if (index > 0) HorizontalDivider()
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.setModel(name) }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(text = name, style = MaterialTheme.typography.bodySmall)
                                if (name == state.model) {
                                    Text(
                                        text = "已选",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            state.status?.let { status ->
                Spacer(Modifier.height(12.dp))
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.statusIsError) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
    )
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun PrivacyNotice() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "开启后，转写内容会发送到云端",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "这是本项目唯一会把你的数据送出手机的功能。录音和转写仍然只存在本机，" +
                    "但生成摘要时，这节课的文字会被发送到你填的那个服务地址。\n\n" +
                    "不能接受的话就别开 —— 关着也能用，只是摘要退回「规则抽取」，质量差一些。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
