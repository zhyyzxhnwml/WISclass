package com.shangkele.core.model

/**
 * 一张挂在笔记上的照片（板书 / PPT / 实验现象）。
 *
 * 真正值钱的是 [offsetMs]：**相对本次录音开始的时间轴位置**。
 * 上课时随手一拍，回看时就知道「拍这张的时候老师正在讲什么」——
 * 纯相册做不到这件事，而它恰好是复习时最需要的上下文。
 *
 * 课后再补拍的照片没有这个位置，[offsetMs] 为 null。
 */
data class NotePhoto(
    val id: Long = 0L,
    val noteId: Long,
    val semesterId: Long,
    val courseId: Long?,
    /** 相对本次录音开始的毫秒偏移；null = 不挂时间轴（课后补拍） */
    val offsetMs: Long?,
    val takenAtMs: Long,
    val path: String,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
    val createdAt: Long,
) {
    /** 时间轴上的标签。课后再补的标「课后」。 */
    val offsetLabel: String get() = offsetMs?.let { formatTimestamp(it) } ?: "课后"

    /** 「1920×1080 · 1.2 MB」，用于详情页核对是不是拍糊了。 */
    val sizeLabel: String
        get() = buildString {
            if (width > 0 && height > 0) append("$width×$height · ")
            append("%.1f MB".format(sizeBytes / 1024.0 / 1024.0))
        }
}
