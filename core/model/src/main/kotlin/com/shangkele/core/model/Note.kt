package com.shangkele.core.model

/** 笔记处理状态。取值与数据库里的字符串一一对应。 */
enum class NoteStatus {
    /** 正在录音 */
    RECORDING,

    /** 录完了，还没转写 */
    RECORDED,

    /** 正在转写（精修） */
    REFINING,

    /** 正在做摘要 */
    SUMMARIZING,

    /** 全部完成 */
    DONE,

    /** 中途失败 */
    FAILED,
}

/**
 * 一次课堂录音对应的笔记。
 *
 * 一条笔记从「录音」开始就落库（而不是录完才存），
 * 这样录音中途进程被杀也能看到一条残缺记录，用户知道发生了什么。
 */
data class Note(
    val id: Long = 0L,
    val courseId: Long?,
    val semesterId: Long,
    val weekIndex: Int,
    val dateEpochDay: Long,
    val startedAtMs: Long,
    val durationMs: Long,
    val audioPath: String?,
    val audioSizeBytes: Long,
    val audioKept: Boolean = true,
    val transcriptText: String? = null,
    val status: NoteStatus,
    val title: String?,
    val createdAt: Long,
) {
    /** 「高等数学 · 第 4 周」这类展示用标题。 */
    fun displayTitle(courseName: String?): String =
        title?.takeIf { it.isNotBlank() }
            ?: listOfNotNull(courseName ?: "临时录音", "第 $weekIndex 周").joinToString(" · ")
}
