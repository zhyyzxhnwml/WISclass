package com.shangkele.app.update

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * 自动更新的全部弹窗。
 *
 * 放在 App 层而不是设置页里，是因为**自动检查发生在开 App 时**，
 * 那时用户根本不在设置页。弹窗挂在根节点上才能被看到。
 */
@Composable
fun UpdateDialogHost(viewModel: UpdateViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    when (val s = state) {
        is UpdateUiState.Checking -> AlertDialog(
            onDismissRequest = {},
            title = { Text("检查更新") },
            text = { Text("正在查询 ${viewModel.currentVersion} 之后有没有新版本…") },
            confirmButton = {},
        )

        is UpdateUiState.Available -> AlertDialog(
            onDismissRequest = { viewModel.skip(s.manifest.version) },
            title = { Text("有新版本 ${s.manifest.version}") },
            text = {
                Column {
                    Text(
                        text = "当前 ${viewModel.currentVersion} → 新版 ${s.manifest.version}" +
                            "（${s.manifest.sizeLabel()}）",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    val notes = viewModel.notesPreview(s.manifest)
                    if (notes.isNotBlank()) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = notes,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = viewModel::download) { Text("下载") } },
            dismissButton = {
                TextButton(onClick = { viewModel.skip(s.manifest.version) }) { Text("以后再说") }
            },
        )

        is UpdateUiState.Downloading -> AlertDialog(
            onDismissRequest = {},
            title = { Text("正在下载 ${s.percent}%") },
            text = {
                Column {
                    LinearProgressIndicator(
                        progress = { if (s.total > 0) s.downloaded.toFloat() / s.total else 0f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "装包会下到缓存目录，装完系统会自己清理。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {},
        )

        is UpdateUiState.ReadyToInstall -> AlertDialog(
            onDismissRequest = viewModel::dismiss,
            title = { Text("下载好了") },
            text = {
                Text(
                    "接下来会跳系统安装界面，确认安装即可。\n\n" +
                        "你的课表、录音、笔记都在数据库和文件里，升级不会丢。",
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.install(s.apk) }) { Text("安装") }
            },
            dismissButton = { TextButton(onClick = viewModel::dismiss) { Text("稍后") } },
        )

        is UpdateUiState.NeedInstallPermission -> AlertDialog(
            onDismissRequest = viewModel::dismiss,
            title = { Text("还差一步授权") },
            text = {
                Text(
                    "Android 不允许应用自己装应用，需要你去系统里给「上课啦」打开\n" +
                        "「安装未知应用」，回来点「安装」就行。装包已经下好了，不用重下。",
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::openInstallPermissionSettings) { Text("去打开") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.install(s.apk) }) { Text("已经开了，安装") }
            },
        )

        is UpdateUiState.UpToDate -> AlertDialog(
            onDismissRequest = viewModel::consumeResult,
            title = { Text("已是最新版") },
            text = { Text("当前 ${s.version}，更新源上没有更新的版本。") },
            confirmButton = { TextButton(onClick = viewModel::consumeResult) { Text("好") } },
        )

        is UpdateUiState.Failed -> AlertDialog(
            onDismissRequest = viewModel::consumeResult,
            title = { Text("更新没成功") },
            text = {
                Column {
                    Text(s.message, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "App 本身不受影响，可以正常用。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = { TextButton(onClick = viewModel::consumeResult) { Text("知道了") } },
        )

        UpdateUiState.Idle -> Unit
    }
}
