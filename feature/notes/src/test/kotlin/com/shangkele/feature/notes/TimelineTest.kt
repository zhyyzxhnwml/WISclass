package com.shangkele.feature.notes

import com.shangkele.core.model.NotePhoto
import com.shangkele.core.model.TranscriptSegment
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 时间轴拼装测试。
 *
 * 排序看着简单，但「课后补拍的照片排哪儿」这类细节错了，用户看到的顺序
 * 就是错的 —— 而错顺序不会报错，只会让人把板书对应到错误的讲解上。
 */
class TimelineTest {

    private fun speech(startMs: Long, text: String = "第 $startMs 毫秒这句") = TranscriptSegment(
        id = startMs,
        noteId = 1L,
        index = 0,
        startMs = startMs,
        endMs = startMs + 3_000,
        text = text,
        isCorrected = false,
    )

    private fun photo(offsetMs: Long?, name: String = "p") = NotePhoto(
        id = offsetMs ?: -1L,
        noteId = 1L,
        semesterId = 1L,
        courseId = 1L,
        offsetMs = offsetMs,
        takenAtMs = 0L,
        path = "/tmp/$name.jpg",
        width = 100,
        height = 100,
        sizeBytes = 1024L,
        createdAt = 0L,
    )

    @Test
    fun `转写与照片按时间交错排`() {
        val timeline = buildTimeline(
            segments = listOf(speech(0), speech(20_000), speech(40_000)),
            photos = listOf(photo(10_000), photo(30_000)),
        )

        assertEquals(5, timeline.size)
        val offsets = timeline.map { it.atMs }
        assertEquals(listOf(0L, 10_000L, 20_000L, 30_000L, 40_000L), offsets)
    }

    @Test
    fun `同一时刻先排照片再排文字`() {
        // 照片是现场，文字是对它的解释，先看到画面更符合直觉
        val timeline = buildTimeline(
            segments = listOf(speech(5_000)),
            photos = listOf(photo(5_000)),
        )
        assertEquals(2, timeline.size)
        assertEquals(true, timeline[0] is TimelineItem.Photo)
        assertEquals(true, timeline[1] is TimelineItem.Speech)
    }

    @Test
    fun `课后补拍的照片排在最后`() {
        val timeline = buildTimeline(
            segments = listOf(speech(0), speech(600_000)),
            // offsetMs = null 代表课后补拍
            photos = listOf(photo(null, "homework")),
        )
        assertEquals(3, timeline.size)
        assertEquals(true, timeline.last() is TimelineItem.Photo)
        assertEquals("课后", (timeline.last() as TimelineItem.Photo).label)
    }

    @Test
    fun `只有照片或只有文字时也不出错`() {
        assertEquals(1, buildTimeline(emptyList(), listOf(photo(1_000))).size)
        assertEquals(2, buildTimeline(listOf(speech(0), speech(1_000)), emptyList()).size)
        assertEquals(0, buildTimeline(emptyList(), emptyList()).size)
    }

    @Test
    fun `时间标签沿用原有的格式化`() {
        // 75 秒 → 01:15；课后的照片标「课后」
        val timeline = buildTimeline(
            segments = emptyList(),
            photos = listOf(photo(75_000), photo(null)),
        )
        assertEquals("01:15", (timeline[0] as TimelineItem.Photo).label)
        assertEquals("课后", (timeline[1] as TimelineItem.Photo).label)
    }
}
