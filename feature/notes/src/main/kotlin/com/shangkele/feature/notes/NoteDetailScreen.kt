package com.shangkele.feature.notes

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shangkele.core.model.NotePhoto
import com.shangkele.core.model.NoteSummary
import com.shangkele.feature.notes.component.rememberPhotoCapture
import com.shangkele.feature.notes.component.rememberPhotoThumbnail
import kotlinx.coroutines.launch
import java.io.File

/**
 * 笔记详情：**摘要 + 时间轴**。
 *
 * 时间轴把转写句子和板书照片按时间排在一起 —— 回看时看到的是
 * 「老师讲到这一句时，板书长这样」，而不是「一堆图 + 一堆字」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteDetailScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: NoteDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingDelete by remember { mutableStateOf<NotePhoto?>(null) }

    val takePhoto = rememberPhotoCapture(
        createTarget = viewModel::beginPhoto,
        onResult = { _, success -> viewModel.onPhotoResult(success) },
    )

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
                title = { Text(state.note?.title ?: "笔记") },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
                actions = {
                    // 课后补拍的入口在这里；上课时的拍照在录音页（那里知道录音进度）
                    TextButton(onClick = takePhoto, enabled = state.note != null) { Text("加照片") }
                    TextButton(onClick = viewModel::transcribe, enabled = !busy) {
                        Text(if (state.needsTranscript) "转写" else "重新转写")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        when {
            state.loading -> Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            state.note == null -> Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) { Text("这条笔记已经不在了") }

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item { MetaCard(state, busy, onTranscribe = viewModel::transcribe) }

                if (state.summary != null) {
                    item { SummaryCard(state.summary!!) }
                }

                if (state.timeline.isNotEmpty()) {
                    item {
                        Text(
                            text = if (state.photos.isEmpty()) "完整转写" else "时间轴",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    items(state.timeline) { item ->
                        when (item) {
                            is TimelineItem.Speech -> TranscriptRow(item.label, item.text)
                            is TimelineItem.Photo -> TimelinePhotoRow(
                                photo = item.photo,
                                onOpen = {
                                    // 板书要能点开看大图，内嵌的缩略图看不清字
                                    if (!openExternally(context, item.photo.path)) {
                                        scope.launch {
                                            snackbarHostState.showSnackbar("没有能打开这张图的应用")
                                        }
                                    }
                                },
                                onLongPress = { pendingDelete = item.photo },
                            )
                        }
                    }
                }
            }
        }
    }

    pendingDelete?.let { photo ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删掉这张照片？") },
            text = { Text("只删这张图，录音和文字都保留。删了无法恢复。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deletePhoto(photo.id)
                        pendingDelete = null
                    },
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun MetaCard(state: NoteDetailUiState, busy: Boolean, onTranscribe: () -> Unit) {
    val note = state.note ?: return
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = state.courseName ?: "临时录音",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "第 ${note.weekIndex} 周 · ${formatDuration(note.durationMs)} · " +
                    "${"%.1f".format(note.audioSizeBytes / 1024.0 / 1024.0)} MB" +
                    if (state.photos.isNotEmpty()) " · ${state.photos.size} 张照片" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.needsTranscript) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "这条还没转写。点右上角「转写」把录音变成文字与要点。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onTranscribe, enabled = !busy) {
                    Text(if (busy) "处理中…" else "开始转写")
                }
            }
            if (busy) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.width(16.dp).height(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("正在处理，长录音需要几分钟", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun SummaryCard(summary: NoteSummary) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            if (summary.isTemplate) {
                // 关键：说清「没有用到 AI」，并给出让它变成 AI 的具体动作。
                Text(
                    text = "规则抽取 · 没有用到 AI",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    text = "下面是按强调词和字数规则挑出来的句子，不是模型总结的。" +
                        "想要真正的 AI 摘要，去「设置 → 模型管理 → 去配置 AI 摘要」填一个云端大模型的地址与 Key；" +
                        "填好之后重新转写即可。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
            }

            Text(text = summary.overview, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = "来源：${summary.modelName}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )

            SummarySection("要点", summary.keyPoints)
            SummarySection("疑难点", summary.confusions)
            SummarySection("作业与待办", summary.todos)
            SummarySection("自测题", summary.quiz)

            if (summary.terms.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))
                Text("术语", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                // 用简单的一行拼接而不是 FlowRow：FlowRow 需要 experimental API，
                // 为了几个词引入不稳定接口不值得
                Text(
                    text = summary.terms.joinToString("　"),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

/** 摘要里的一节。空列表直接不渲染，避免出现「疑难点」下面什么都没写。 */
@Composable
private fun SummarySection(title: String, items: List<String>) {
    if (items.isEmpty()) return
    Spacer(Modifier.height(12.dp))
    HorizontalDivider()
    Spacer(Modifier.height(12.dp))
    Text(text = title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(6.dp))
    items.forEach { item ->
        Row(modifier = Modifier.padding(vertical = 3.dp)) {
            Text("· ", fontWeight = FontWeight.Bold)
            // 自测题里带换行（Q: / A:），要保留
            Text(text = item, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun TranscriptRow(timestamp: String, text: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = timestamp,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(52.dp),
        )
        Text(text = text, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * 时间轴上的一张照片。
 *
 * 长按删除（和笔记列表一致的手势）。缩略图按需解码并降采样，
 * 见 [rememberPhotoThumbnail]：直接解原图会 OOM。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TimelinePhotoRow(
    photo: NotePhoto,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
) {
    val bitmap = rememberPhotoThumbnail(photo.path)

    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = photo.offsetLabel,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(52.dp),
        )
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onOpen, onLongClick = onLongPress),
        ) {
            Column {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = "板书照片 ${photo.offsetLabel}",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 280.dp)
                            .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)),
                    )
                } else {
                    // 文件没了或解码失败：明确说出来，不要留一块空白让人以为在加载
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "照片读不出来了",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    text = photo.sizeLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
    }
}

/**
 * 用外部应用打开照片看大图。
 *
 * 走 FileProvider 授一个只读 Uri 出去，而不是把图片路径当 `file://` 传 ——
 * Android 7 起直接传 file:// 会抛 `FileUriExposedException`。
 *
 * @return false 表示设备上没有能处理图片的应用（或文件已经不在了）
 */
private fun openExternally(context: Context, path: String): Boolean {
    if (!File(path).isFile) return false
    return runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(path))
        context.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "image/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
        )
        true
    }.getOrDefault(false)
}
