package com.shangkele.feature.notes

import com.shangkele.core.model.NotePhoto
import com.shangkele.core.model.TranscriptSegment

/**
 * 时间轴上的一项：要么是一句转写，要么是一张照片。
 *
 * 把两者放进同一条轴，是「拍照」这个功能唯一比相册强的地方：
 * 回看时看到的不再是「一堆图 + 一堆字」，而是
 * 「老师讲到这一句的时候，板书长这样」。
 *
 * 拆成纯函数 [buildTimeline] 是为了能单测 —— 排序看着简单，
 * 但「没有时间轴位置的照片排哪儿」这类细节错了会直接让人看错顺序。
 */
sealed interface TimelineItem {
    val atMs: Long

    /** 一句转写。 */
    data class Speech(val segment: TranscriptSegment) : TimelineItem {
        override val atMs: Long get() = segment.startMs
        val label: String get() = segment.timestampLabel
        val text: String get() = segment.text
    }

    /** 一张照片。 */
    data class Photo(val photo: NotePhoto) : TimelineItem {
        /**
         * 课后补拍的照片没有时间轴位置，用 `Long.MAX_VALUE` 排到整条轴的最后。
         * 用 0 会让它们聚在开头，读者会以为那是上课一开始拍的。
         */
        override val atMs: Long get() = photo.offsetMs ?: Long.MAX_VALUE
        val label: String get() = photo.offsetLabel
    }
}

/**
 * 转写句子与照片按时间拼成一条时间轴。
 *
 * 同一时刻先排照片再排文字：照片是「现场」，文字是对它的解释，
 * 先看到画面更符合直觉。`sortedWith` 是稳定排序，所以这个先后由比较器决定即可。
 */
fun buildTimeline(
    segments: List<TranscriptSegment>,
    photos: List<NotePhoto>,
): List<TimelineItem> =
    (segments.map { TimelineItem.Speech(it) } + photos.map { TimelineItem.Photo(it) })
        .sortedWith(
            compareBy(
                { it.atMs },
                { if (it is TimelineItem.Photo) 0 else 1 },
            ),
        )
