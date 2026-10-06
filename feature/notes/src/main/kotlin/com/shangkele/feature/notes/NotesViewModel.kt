package com.shangkele.feature.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shangkele.core.ai.model.AsrModelSpec
import com.shangkele.core.ai.transcribe.NoteTranscriber
import com.shangkele.core.context.now.NowClassResolver
import com.shangkele.core.context.photos.NotePhotoStore
import com.shangkele.core.context.recording.ClassNoteRecorder
import com.shangkele.core.context.recording.RecordingController
import com.shangkele.core.context.recording.RecordingState
import com.shangkele.core.database.repository.ScheduleRepository
import com.shangkele.core.model.Course
import com.shangkele.core.model.Note
import com.shangkele.core.model.NotePhoto
import com.shangkele.core.model.NoteStatus
import com.shangkele.core.model.Semester
import com.shangkele.core.model.TimeSlot
import com.shangkele.core.model.WeekCalculator
import com.shangkele.core.model.formatTimestamp
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject

/** 转写的进行状态。同一时刻只允许一个任务。 */
sealed interface TranscribeUiState {
    data object Idle : TranscribeUiState

    /** 首次使用要先下 230MB 的语音模型 */
    data class DownloadingModel(val downloaded: Long, val total: Long) : TranscribeUiState {
        val fraction: Float
            get() = if (total > 0) (downloaded.toFloat() / total).coerceIn(0f, 1f) else 0f

        val percent: Int get() = (fraction * 100).toInt()
    }

    data class Running(
        val noteId: Long,
        val done: Int,
        val total: Int,
        /** 摘要阶段的文案（分段处理时会更新），非空时优先展示 */
        val stage: String? = null,
    ) : TranscribeUiState {
        val percent: Int
            get() = if (total > 0) (done * 100 / total).coerceIn(0, 100) else 0

        fun label(): String = stage ?: if (total > 0) "转写中 $done/$total" else "准备中…"
    }
}

data class NotesUiState(
    val recording: RecordingState = RecordingState.Idle,
    /** 现在正在上的课（决定新录音会自动归到哪门课） */
    val currentCourseName: String? = null,
    val recentNotes: List<Note> = emptyList(),
    /** courseId -> 课程名，用于列表展示 */
    val courseNames: Map<Long, String> = emptyMap(),
    val transcribing: TranscribeUiState = TranscribeUiState.Idle,
) {
    val isRecording: Boolean get() = recording is RecordingState.Active

    val activeState: RecordingState.Active? get() = recording as? RecordingState.Active

    /** 这条笔记当前是否正在被转写。 */
    fun isTranscribing(noteId: Long): Boolean =
        transcribing is TranscribeUiState.Running && transcribing.noteId == noteId
}

/** 每 30 秒刷新的时间快照，避免每次都要重算一遍学期与周次。 */
private data class TimeSnapshot(
    val courses: List<Course>,
    val slots: Map<Int, TimeSlot>,
    val semester: Semester?,
    val nowMinutes: Int,
    val weekday: Int,
    val week: Int,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class NotesViewModel @Inject constructor(
    private val repository: ScheduleRepository,
    private val recorder: ClassNoteRecorder,
    private val controller: RecordingController,
    private val transcriber: NoteTranscriber,
    private val photoStore: NotePhotoStore,
) : ViewModel() {

    private val _message = MutableStateFlow<String?>(null)

    /**
     * 正在等相机返回的那张照片。
     *
     * **必须在按快门之前就把「属于哪条笔记」定下来**：相机打开期间用户完全可能
     * 把录音结束掉，等相机返回再去读录音状态就晚了 —— 照片会挂不上任何笔记。
     */
    private class PendingPhoto(val file: File, val noteId: Long)

    private var pendingPhoto: PendingPhoto? = null

    private val _transcribe = MutableStateFlow<TranscribeUiState>(TranscribeUiState.Idle)

    /** 界面用的一次性提示（权限没给、没有课表等）。 */
    val message: StateFlow<String?> = _message.asStateFlow()

    /** 每 30 秒推进一次，够用来刷新「现在上的是哪节课」。 */
    private val nowFlow: Flow<LocalTime> = flow {
        while (true) {
            emit(LocalTime.now())
            delay(30_000L)
        }
    }

    private val semesterFlow = repository.observeActiveSemester()

    private val coursesFlow = semesterFlow.flatMapLatest { semester ->
        if (semester == null) flowOf(emptyList<Course>()) else repository.observeCourses(semester.id)
    }

    private val slotsFlow = semesterFlow.flatMapLatest { semester ->
        if (semester == null) flowOf(emptyList<TimeSlot>()) else repository.observeTimeSlots(semester.id)
    }

    // combine 最多支持 5 个流，所以先合成时间快照，再与录音状态、笔记列表合并
    private val snapshotFlow: Flow<TimeSnapshot> =
        combine(coursesFlow, slotsFlow, semesterFlow, nowFlow) { courses, slots, semester, _ ->
            val zoned = Instant.now().atZone(ZoneId.systemDefault())
            val epochDay = zoned.toLocalDate().toEpochDay()
            TimeSnapshot(
                courses = courses,
                slots = slots.associateBy { it.section },
                semester = semester,
                nowMinutes = zoned.hour * 60 + zoned.minute,
                weekday = WeekCalculator.weekdayOf(epochDay),
                week = semester?.let {
                    WeekCalculator.currentWeek(it.startDateEpochDay, epochDay, it.totalWeeks)
                } ?: 1,
            )
        }

    val uiState: StateFlow<NotesUiState> = combine(
        snapshotFlow,
        controller.state,
        repository.observeRecentNotes(),
        _transcribe,
    ) { snapshot, recording, notes, transcribing ->
        NotesUiState(
            recording = recording,
            currentCourseName = NowClassResolver.current(
                courses = snapshot.courses,
                slots = snapshot.slots,
                weekday = snapshot.weekday,
                week = snapshot.week,
                nowMinutes = snapshot.nowMinutes,
            )?.course?.name,
            recentNotes = notes,
            courseNames = snapshot.courses.associate { it.id to it.name },
            transcribing = transcribing,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = NotesUiState(),
    )

    fun consumeMessage() {
        _message.value = null
    }

    /** 麦克风权限被拒时的提示 —— 不弹二次申请，直接说明后果。 */
    fun onPermissionDenied() {
        _message.value = "没有麦克风权限就没法录音。可以在系统设置里给「上课啦」打开。"
    }

    fun toggleRecording() {
        if (controller.isActive) stopRecording() else startRecording()
    }

    fun startRecording() {
        viewModelScope.launch {
            when (val outcome = recorder.start()) {
                is ClassNoteRecorder.StartOutcome.Started ->
                    _message.value = outcome.courseName
                        ?.let { "开始记录《$it》" }
                        ?: "开始录音（没匹配到课程，记为临时笔记）"

                is ClassNoteRecorder.StartOutcome.NoSemester ->
                    _message.value = "还没有课表，请先导入课表"

                is ClassNoteRecorder.StartOutcome.Failed ->
                    _message.value = outcome.message
            }
        }
    }

    fun stopRecording() {
        viewModelScope.launch {
            val result = recorder.stop()
            _message.value = result?.let { "已保存，时长 ${formatDuration(it.durationMs)}" }
        }
    }

    fun pauseRecording() = recorder.pause()

    fun resumeRecording() = recorder.resume()

    // ---- 拍照 ----

    /**
     * 按快门前调用，返回相机要写入的目标文件；不在录音中则返回 null。
     *
     * 只有录音进行中才给拍：照片的价值就在于「它挂在录音的某个时刻上」，
     * 没在录音就不知道该挂到哪儿。
     */
    fun beginPhoto(): File? {
        val active = uiState.value.activeState
        if (active == null) {
            _message.value = "先开始录音，拍的板书才能挂到对应的时间轴上"
            return null
        }
        val file = runCatching { photoStore.newPhotoFile(active.noteId, System.currentTimeMillis()) }
            .getOrNull()
        if (file == null) {
            _message.value = "找不到可写的目录，这张没拍成"
            return null
        }
        pendingPhoto = PendingPhoto(file, active.noteId)
        return file
    }

    /**
     * 相机返回后调用。
     *
     * 时间轴位置取**文件的最后写入时间**，而不是「用户点我们按钮的时间」：
     * 相机是在按快门那一下才写盘的，而用户可能举着手机框了十几秒。
     * 用写盘时间算出来的偏移，才真的对应「这张拍到的是老师讲到哪一句」。
     */
    fun onPhotoResult(success: Boolean) {
        val pending = pendingPhoto ?: return
        pendingPhoto = null

        if (!success) {
            runCatching { pending.file.delete() }
            return
        }

        viewModelScope.launch {
            if (!pending.file.isFile || pending.file.length() == 0L) {
                _message.value = "照片没保存成功，再试一次"
                return@launch
            }
            val note = repository.getNote(pending.noteId)
            if (note == null) {
                runCatching { pending.file.delete() }
                _message.value = "这条笔记已经不在了"
                return@launch
            }

            val (width, height) = photoStore.readSize(pending.file)
            val takenAtMs = pending.file.lastModified().takeIf { it > 0 } ?: System.currentTimeMillis()
            val offsetMs = (takenAtMs - note.startedAtMs).takeIf { it >= 0 }

            repository.addNotePhoto(
                NotePhoto(
                    noteId = note.id,
                    semesterId = note.semesterId,
                    courseId = note.courseId,
                    offsetMs = offsetMs,
                    takenAtMs = takenAtMs,
                    path = pending.file.absolutePath,
                    width = width,
                    height = height,
                    sizeBytes = pending.file.length(),
                    createdAt = System.currentTimeMillis(),
                ),
            )
            _message.value = offsetMs
                ?.let { "已挂到时间轴 ${formatTimestamp(it)}" }
                ?: "已加入这条笔记"
        }
    }

    /** 删除一条笔记（连同音频文件与照片）。 */
    fun deleteNote(noteId: Long) {
        viewModelScope.launch {
            // 正在录的那条不能删：音频还在往里写，删了记录后文件就成孤儿了，
            // 而且录音还在继续、界面却已经没有了那条记录
            val active = controller.state.value
            if (active is RecordingState.Active && active.noteId == noteId) {
                _message.value = "这条正在录音，先结束再删"
                return@launch
            }
            val ok = repository.deleteNote(noteId)
            _message.value = if (ok) "已删除" else "删除失败，记录可能已经不存在了"
        }
    }

    /**
     * 转写一条笔记。
     *
     * 首次使用会先下 230MB 语音模型 —— 不静默开始，而是把下载进度显示出来，
     * 否则用户会以为 App 卡死了。
     */
    fun transcribe(noteId: Long) {
        if (_transcribe.value is TranscribeUiState.Running) return
        viewModelScope.launch {
            if (!transcriber.isModelReady()) {
                _transcribe.value = TranscribeUiState.DownloadingModel(
                    downloaded = transcriber.downloadedBytes(),
                    total = AsrModelSpec.ASR_MODEL.sizeBytes,
                )
                val download = transcriber.downloadModel { done, total ->
                    _transcribe.value = TranscribeUiState.DownloadingModel(done, total)
                }
                if (download.isFailure) {
                    _transcribe.value = TranscribeUiState.Idle
                    _message.value = "语音模型下载失败：${download.exceptionOrNull()?.message ?: "网络不可用"}"
                    return@launch
                }
            }

            _transcribe.value = TranscribeUiState.Running(noteId, 0, 0)
            // 必须用具名参数：transcribe 的最后一个参数是 onStage，
            // 用尾随 lambda 会绑到它上面，顺手把 onProgress 写成 lambda 就会报类型不符
            val outcome = transcriber.transcribe(
                noteId = noteId,
                onProgress = { done, total ->
                    _transcribe.value = TranscribeUiState.Running(noteId, done, total)
                },
                onStage = { stage ->
                    _transcribe.value = TranscribeUiState.Running(noteId, 0, 0, stage)
                },
            )
            _transcribe.value = TranscribeUiState.Idle
            _message.value = when (outcome) {
                is NoteTranscriber.Outcome.Success -> "转写完成，共 ${outcome.segmentCount} 句"
                is NoteTranscriber.Outcome.Failed -> outcome.message
            }
        }
    }
}

fun formatDuration(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return if (minutes > 0) "${minutes}分${seconds}秒" else "${seconds}秒"
}

/** 列表里显示的状态文案。 */
fun NoteStatus.label(): String = when (this) {
    NoteStatus.RECORDING -> "录音中"
    NoteStatus.RECORDED -> "待转写"
    NoteStatus.REFINING -> "转写中"
    NoteStatus.SUMMARIZING -> "生成摘要"
    NoteStatus.DONE -> "已完成"
    NoteStatus.FAILED -> "失败"
}
