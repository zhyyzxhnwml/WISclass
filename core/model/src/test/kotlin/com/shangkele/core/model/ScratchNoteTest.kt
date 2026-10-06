package com.shangkele.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class ScratchNoteTest {

    private val day20261006 = LocalDate.of(2026, 10, 6).toEpochDay()

    @Test
    fun `标题带当天日期`() {
        assertEquals("随手拍 · 10-06", ScratchNote.titleFor(day20261006))
    }

    @Test
    fun `靠标题认出随手拍`() {
        assertTrue(ScratchNote.isScratch("随手拍 · 10-06"))
        assertTrue(ScratchNote.isScratch("随手拍"))

        assertFalse(ScratchNote.isScratch("高等数学 · 第 4 周"))
        assertFalse(ScratchNote.isScratch(null))
        // 用户自己起的「随手拍了拍」不能被误判 —— 判错了，那条笔记的「转写」按钮会消失，
        // 而且界面上看不出为什么
        assertFalse(ScratchNote.isScratch("随手拍了拍"))
    }

    /**
     * 起点必须落在**本地** 0 点。
     *
     * 写成 `epochDay * 86_400_000` 也能过「标题」那几条测试，但那算的是 UTC 0 点：
     * 东八区会整体偏 8 小时，照片标签显示 `06:32` 而实际是 `14:32` —— 错了但看着正常。
     */
    @Test
    fun `起点是当天本地 0 点`() {
        val start = ScratchNote.startOfDayMs(day20261006)
        val back = Instant.ofEpochMilli(start).atZone(ZoneId.systemDefault())
        assertEquals(day20261006, back.toLocalDate().toEpochDay())
        assertEquals(0, back.hour)
        assertEquals(0, back.minute)
        assertEquals(0, back.second)
    }

    /**
     * 这是整个设计的立足点：笔记起点定在当天 0 点之后，
     * 照片的偏移量**就是它的钟点**，于是现有渲染直接给出「14:32:05」。
     */
    @Test
    fun `照片偏移量等于拍摄钟点`() {
        val start = ScratchNote.startOfDayMs(day20261006)
        val taken = ZonedDateTime.of(2026, 10, 6, 14, 32, 5, 0, ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

        assertEquals("14:32:05", formatTimestamp(taken - start))
    }

    @Test
    fun `同一天早拍的照片偏移量更小`() {
        val start = ScratchNote.startOfDayMs(day20261006)
        fun at(hour: Int, minute: Int): Long =
            ZonedDateTime.of(2026, 10, 6, hour, minute, 0, 0, ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli() - start

        assertTrue(at(8, 5) < at(14, 32))
        assertTrue(at(23, 59) > at(14, 32))
    }

    @Test
    fun `跨天的笔记起点不同`() {
        val today = ScratchNote.startOfDayMs(day20261006)
        val tomorrow = ScratchNote.startOfDayMs(day20261006 + 1)
        assertEquals(24 * 60 * 60 * 1000L, tomorrow - today)
    }
}
