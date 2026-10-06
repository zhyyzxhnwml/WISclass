package com.shangkele.feature.notes

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shangkele.core.ai.transcribe.NoteTranscriber
import com.shangkele.core.context.photos.NotePhotoStore
import com.shangkele.core.database.repository.ScheduleRepository
import com.shangkele.core.model.Assignment
import com.shangkele.core.model.Note
import com.shangkele.core.model.NotePhoto
import com.shangkele.core.model.NoteSummary
import com.shangkele.core.model.ScratchNote
import com.shangkele.core.model.TranscriptSegment
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class NoteDetailUiState(
    val loading: Boolean = true,
    val note: Note? = null,
    val courseName: String? = null,
    val summary: NoteSummary? = null,
    val segments: List<TranscriptSegment> = emptyList(),
    val photos: List<NotePhoto> = emptyList(),
    /** 从这条笔记里读出来的作业 / 待办。 */
    val assignments: List<Assignment> = emptyList(),
) {
    /** 转写与摘要都还没有，界面上要给出「去转写」的引导。 */
    val needsTranscript: Boolean get() = segments.isEmpty()

    /**
     * 这条是不是「随手拍」（只有照片、没有录音）。
     *
     * 界面据此收起整条转写路径：没有音频却摆一个「转写」按钮，
     * 点下去只会得到「录音文件已丢失」—— 用户看不懂，也没法修。
     */
    val isScratch: Boolean get() = ScratchNote.isScratch(note?.title)

    /**
     * 转写句子与照片按时间拼成的一条时间轴。
     *
     * 这是拍照这个功能真正值钱的地方：回看时不是「一堆图 + 一堆字」，
     * 而是「老师讲到这句时，板书是这样」。
     */
    val timeline: List<TimelineItem> get() = buildTimeline(segments, photos)
}

@HiltViewModel
class NoteDetailViewModel @Inject constructor(
    private val repository: ScheduleRepository,
    private val transcriber: NoteTranscriber,
    private val photoStore: NotePhotoStore,
    private val homeworkScanner: HomeworkScanner,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    /** 正在等相机返回的那张照片的目标文件。 */
    private var pendingPhoto: File? = null

    private val noteId: Long = savedStateHandle.get<String>(ARG_NOTE_ID)?.toLongOrNull() ?: 0L

    private val _state = MutableStateFlow(NoteDetailUiState())
    val state: StateFlow<NoteDetailUiState> = _state.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    init {
        load()
        // 待办单独用 Flow 跟着库走：识别是在后台跑的（见 HomeworkScanner），
        // 它写进库的那一刻这一页就该更新，而不是要用户退出去再进来一次
        viewModelScope.launch {
            repository.observeAssignmentsOfNote(noteId).collect { list ->
                _state.update { it.copy(assignments = list) }
            }
        }
    }

    private fun load() {
        viewModelScope.launch {
            val note = repository.getNote(noteId)
            val semester = repository.getActiveSemester()
            val courseName = note?.courseId?.let { courseId ->
                semester?.let { s -> repository.getCourses(s.id).firstOrNull { it.id == courseId }?.name }
            }
            _state.value = NoteDetailUiState(
                loading = false,
                note = note,
                courseName = courseName,
                summary = repository.getNoteSummary(noteId),
                segments = repository.getNoteSegments(noteId),
                photos = repository.getNotePhotos(noteId),
            )
        }
    }

    // ---- 读作业 ----

    /**
     * 手动把这条笔记的照片和转写都读一遍，找老师布置的事。
     *
     * 后台那条路径（拍完自动读）在进程被杀时会丢，所以这个入口必须留着 ——
     * 否则用户遇到一次失败就再也没有第二次机会。
     *
     * 模型抽不出日期时不编日期，只留老师的原话（见 [HomeworkDueParser]）。
     */
    fun scanForHomework() {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            val today = _state.value.note?.dateEpochDay ?: LocalDate.now().toEpochDay()

            var total = 0
            var firstError: String? = null
            val photos = _state.value.photos

            photos.forEach { photo ->
                homeworkScanner.scanPhoto(noteId, photo.path, today)
                    .onSuccess { total += it }
                    .onFailure { if (firstError == null) firstError = it.message }
            }
            homeworkScanner.scanTranscript(noteId, today)
                .onSuccess { total += it }
                .onFailure { if (firstError == null) firstError = it.message }

            _message.value = when {
                total > 0 -> "读到 $total 条待办"
                firstError != null -> "没读到新待办：$firstError"
                else -> "没读到要做的事"
            }
            _busy.value = false
        }
    }

    // ---- 待办 ----

    fun setAssignmentDone(id: Long, done: Boolean) {
        viewModelScope.launch { repository.setAssignmentDone(id, done) }
    }

    /** 删掉抽错的待办。只删这一行，笔记和照片都留着。 */
    fun deleteAssignment(id: Long) {
        viewModelScope.launch { repository.deleteAssignment(id) }
    }

    // ---- 拍照 ----

    /**
     * 课后给这条笔记补拍一张板书 / 实验数据。
     *
     * 这类照片**不挂时间轴**（[NotePhoto.offsetMs] 为 null，界面上标「课后」）：
     * 录音早就结束了，硬算一个相对偏移会得到一个「74:23:11」这种荒唐的时间戳。
     */
    fun beginPhoto(): File? {
        val note = _state.value.note ?: return null
        val file = runCatching { photoStore.newPhotoFile(note.id, System.currentTimeMillis()) }
            .getOrNull()
        if (file == null) {
            _message.value = "找不到可写的目录，这张没拍成"
            return null
        }
        pendingPhoto = file
        return file
    }

    fun onPhotoResult(success: Boolean) {
        val file = pendingPhoto ?: return
        pendingPhoto = null
        if (!success) {
            runCatching { file.delete() }
            return
        }
        viewModelScope.launch {
            if (!file.isFile || file.length() == 0L) {
                _message.value = "照片没保存成功，再试一次"
                return@launch
            }
            val note = repository.getNote(noteId)
            if (note == null) {
                runCatching { file.delete() }
                _message.value = "这条笔记已经不在了"
                return@launch
            }
            val (width, height) = photoStore.readSize(file)
            repository.addNotePhoto(
                NotePhoto(
                    noteId = note.id,
                    semesterId = note.semesterId,
                    courseId = note.courseId,
                    offsetMs = null,
                    takenAtMs = file.lastModified().takeIf { it > 0 } ?: System.currentTimeMillis(),
                    path = file.absolutePath,
                    width = width,
                    height = height,
                    sizeBytes = file.length(),
                    createdAt = System.currentTimeMillis(),
                ),
            )

            // 补拍的照片也立刻读一遍。这一步要联网、几秒才回，丢后台不挡用户
            homeworkScanner.scanPhotoInBackground(note.id, file.absolutePath, note.dateEpochDay)
            _message.value = "已加入这条笔记"
            load()
        }
    }

    /** 删一张照片（连同文件）。 */
    fun deletePhoto(photoId: Long) {
        viewModelScope.launch {
            val ok = repository.deleteNotePhoto(photoId)
            _message.value = if (ok) "已删除这张照片" else "照片已经不在了"
            load()
        }
    }

    /** 重新转写（首次转写也走这里）。 */
    fun transcribe() {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                if (!transcriber.isModelReady()) {
                    val download = transcriber.downloadModel { _, _ -> }
                    if (download.isFailure) {
                        _message.value = "语音模型下载失败：${download.exceptionOrNull()?.message ?: "网络不可用"}"
                        return@launch
                    }
                }
                _message.value = when (val outcome = transcriber.transcribe(noteId)) {
                    is NoteTranscriber.Outcome.Success -> "转写完成，共 ${outcome.segmentCount} 句"
                    is NoteTranscriber.Outcome.Failed -> outcome.message
                }
                load()
            } finally {
                _busy.value = false
            }
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    companion object {
        const val ARG_NOTE_ID = "noteId"
    }
}
