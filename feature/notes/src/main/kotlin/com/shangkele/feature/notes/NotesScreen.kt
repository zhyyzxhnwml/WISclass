package com.shangkele.feature.notes

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shangkele.core.context.recording.RecordingService
import com.shangkele.core.model.Note
import com.shangkele.core.model.ScratchNote
import com.shangkele.feature.notes.component.rememberPhotoCapture
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesScreen(
    modifier: Modifier = Modifier,
    onOpenNote: (Long) -> Unit = {},
    viewModel: NotesViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var pendingDelete by remember { mutableStateOf<Note?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) viewModel.startRecording() else viewModel.onPermissionDenied()
    }

    // 拍照走系统相机（不申请 CAMERA 权限，见 rememberPhotoCapture 的说明）
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
            // 顶层页面，没有「返回」——它的入口是底部导航
            TopAppBar(title = { Text("课堂笔记") })
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            CurrentCourseBanner(
                courseName = state.currentCourseName,
                isRecording = state.isRecording,
            )

            RecordingPanel(
                state = state,
                onToggle = {
                    if (state.isRecording) {
                        viewModel.stopRecording()
                    } else {
                        val granted = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.RECORD_AUDIO,
                        ) == PackageManager.PERMISSION_GRANTED
                        if (granted) viewModel.startRecording() else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                onPause = viewModel::pauseRecording,
                onResume = viewModel::resumeRecording,
                onTakePhoto = takePhoto,
            )

            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "最近的录音",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.recentNotes.isNotEmpty()) {
                    Text(
                        text = "长按可删除",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))

            if (state.recentNotes.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "还没有录音。上课时点上面的按钮就能开始记笔记。",
                        style = MaterialTheme.typography.bodySmall,
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
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.recentNotes, key = { it.id }) { note ->
                        NoteRow(
                            note = note,
                            courseName = note.courseId?.let { state.courseNames[it] },
                            transcribing = state.transcribing,
                            onTranscribe = { viewModel.transcribe(note.id) },
                            onOpen = { onOpenNote(note.id) },
                            onRequestDelete = { pendingDelete = note },
                        )
                    }
                }
            }
        }
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除这条笔记？") },
            text = {
                Text(
                    "会一并删掉录音文件和转写内容，无法恢复。\n\n" +
                        (target.title ?: "临时录音") +
                        " · 第 ${target.weekIndex} 周 · ${formatDuration(target.durationMs)}",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteNote(target.id)
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
private fun CurrentCourseBanner(courseName: String?, isRecording: Boolean) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (courseName != null) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = if (courseName != null) "正在上课" else "当前不在上课时间",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = courseName ?: "录音会记为「临时笔记」，之后可以手动归到某门课",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            if (isRecording) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "本次录音会自动归档到这门课",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun RecordingPanel(
    state: NotesUiState,
    onToggle: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onTakePhoto: () -> Unit,
) {
    val active = state.activeState

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            modifier = Modifier
                .size(120.dp)
                .clickable(onClick = onToggle),
            shape = CircleShape,
            color = if (state.isRecording) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.primaryContainer
            },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = if (state.isRecording) "结束" else "开始\n录音",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (state.isRecording) {
                        MaterialTheme.colorScheme.onErrorContainer
                    } else {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    },
                )
            }
        }

        if (active != null) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = RecordingService.formatElapsed(active.elapsedMs),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = if (active.paused) "已暂停" else "正在录音（16kHz 单声道 AAC）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = if (active.paused) onResume else onPause) {
                    Text(if (active.paused) "继续" else "暂停")
                }
                // 拍照只在录音时给：照片的价值就在于「挂在录音的某个时刻上」，
                // 课后再拍就不知道该对到哪句话了
                OutlinedButton(onClick = onTakePhoto) { Text("拍板书") }
            }
            Text(
                text = "拍完会自动挂到此刻的进度上，回看笔记时按时间轴排在一起",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        } else {
            // 没在录音时把「拍照」这件事说出来。
            // 按钮只在录音中出现，否则拍的照片没有时间轴位置可挂 ——
            // 不解释的话，用户会以为这个 App 没有拍照功能。
            Spacer(Modifier.height(10.dp))
            Text(
                text = "开始录音后会出现「拍板书」：拍的照片会挂到那一刻的进度上，" +
                    "回看时和转写文字排在同一条时间轴上。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NoteRow(
    note: Note,
    courseName: String?,
    transcribing: TranscribeUiState,
    onTranscribe: () -> Unit,
    onOpen: () -> Unit,
    onRequestDelete: () -> Unit,
) {
    val dateText = remember(note.dateEpochDay) {
        LocalDate.ofEpochDay(note.dateEpochDay).format(DateTimeFormatter.ofPattern("MM-dd"))
    }
    val busy = transcribing is TranscribeUiState.DownloadingModel || transcribing is TranscribeUiState.Running
    val transcript = note.transcriptText?.takeIf { it.isNotBlank() }
    // 「随手拍」没有录音：时长、转写、状态那一整套路子对它都不成立，得换一套说法
    val scratch = ScratchNote.isScratch(note.title)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onOpen, onLongClick = onRequestDelete),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (scratch) ScratchNote.TITLE_PREFIX else (courseName ?: "临时录音"),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        // 没有录音还写「0:00」，看起来像录音坏了
                        text = if (scratch) {
                            "$dateText · 第 ${note.weekIndex} 周 · 只有照片，没有录音"
                        } else {
                            "$dateText · 第 ${note.weekIndex} 周 · ${formatDuration(note.durationMs)}"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = note.status.label(),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (note.status.name == "FAILED") {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }

            when {
                // 「随手拍」没有录音，不给它转写入口 —— 点下去必然报
                // 「录音文件已丢失」，而用户完全不知道为什么
                scratch -> {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "照片按拍摄时间排在笔记里，点开就能看",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                // 模型下载是首次使用绕不过的一步，明确把 230MB 的事说清楚
                transcribing is TranscribeUiState.DownloadingModel -> {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "首次使用需下载语音模型 ${transcribing.percent}%（230MB，仅一次）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                transcribing is TranscribeUiState.Running && transcribing.noteId == note.id -> {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = transcribing.label(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                transcript != null -> {
                    Spacer(Modifier.height(6.dp))
                    // 明确标出这是「原文」，避免被当成摘要 —— 摘要走的是另一条流水线（本地 LLM），
                    // 还没接上。不写清楚的话，用户会以为这就是最终成果。
                    Text(
                        text = "转写原文",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = transcript,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = onTranscribe, enabled = !busy) { Text("重新转写") }
                    }
                }

                // 录音还在进行中，没什么可转的
                note.status.name == "RECORDING" -> Unit

                else -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "还没有文字记录",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = onTranscribe, enabled = !busy) { Text("转写") }
                    }
                }
            }
        }
    }
}
