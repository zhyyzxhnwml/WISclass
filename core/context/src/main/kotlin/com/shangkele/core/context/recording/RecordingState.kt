package com.shangkele.core.context.recording

/** 录音过程中对外暴露的状态。 */
sealed interface RecordingState {

    data object Idle : RecordingState

    data class Active(
        val noteId: Long,
        val startedAtMs: Long,
        /** 已录时长（毫秒），不含暂停的时段 */
        val elapsedMs: Long,
        val paused: Boolean,
        /** 关联到的课程名；临时录音时为 null */
        val courseName: String?,
        val courseId: Long?,
    ) : RecordingState {
        val isRecording: Boolean get() = !paused
    }
}

/** 录音结束后的产物。 */
data class RecordingResult(
    val noteId: Long,
    val filePath: String?,
    val durationMs: Long,
    val sizeBytes: Long,
)
