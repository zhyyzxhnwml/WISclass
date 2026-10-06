package com.shangkele.core.common.classroom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RoomKeyNormalizerTest {

    @Test
    fun `教学楼前缀与分隔符统一`() {
        assertEquals("A-101", RoomKeyNormalizer.normalize("教学楼A-101"))
        assertEquals("A-101", RoomKeyNormalizer.normalize("教A101"))
        assertEquals("A-101", RoomKeyNormalizer.normalize("A101"))
        assertEquals("A-101", RoomKeyNormalizer.normalize("  A-101 "))
        assertEquals("A-101", RoomKeyNormalizer.normalize("a-101"))
    }

    @Test
    fun `多媒体与实验楼前缀`() {
        assertEquals("B-203", RoomKeyNormalizer.normalize("多媒体教室B203"))
        assertEquals("C-305", RoomKeyNormalizer.normalize("实验楼C-305"))
        assertEquals("D-101", RoomKeyNormalizer.normalize("综合楼D101"))
    }

    @Test
    fun `纯数字教室原样返回`() {
        assertEquals("101", RoomKeyNormalizer.normalize("101"))
    }

    @Test
    fun `空值返回 null`() {
        assertNull(RoomKeyNormalizer.normalize(null))
        assertNull(RoomKeyNormalizer.normalize(""))
        assertNull(RoomKeyNormalizer.normalize("   "))
        assertNull(RoomKeyNormalizer.normalize("教学楼"))
    }

    @Test
    fun `楼栋与房间号提取`() {
        assertEquals("A", RoomKeyNormalizer.buildingOf("A-101"))
        assertEquals("101", RoomKeyNormalizer.roomNumberOf("A-101"))
        assertEquals("1205", RoomKeyNormalizer.roomNumberOf("A-1205"))
    }

    @Test
    fun `没有分隔符的楼栋也能取出`() {
        // 本校实际出现过这种写法
        assertEquals("3B", RoomKeyNormalizer.buildingOf("3B301"))
        assertEquals("1A", RoomKeyNormalizer.buildingOf("1A207"))
        assertEquals("B", RoomKeyNormalizer.buildingOf("B203"))
        assertEquals("A", RoomKeyNormalizer.buildingOf("A101"))
    }

    @Test
    fun `没有数字的场地名整体当楼栋`() {
        assertEquals("体育馆", RoomKeyNormalizer.buildingOf("体育馆"))
        assertEquals("田径场", RoomKeyNormalizer.buildingOf("田径场"))
    }

    @Test
    fun `纯数字判断不出楼栋时返回 null 而不是瞎猜`() {
        // 宁可返回 null：猜错会给出错误的出发提前量，比不给提示更糟
        assertNull(RoomKeyNormalizer.buildingOf("101"))
        assertNull(RoomKeyNormalizer.buildingOf(null))
        assertNull(RoomKeyNormalizer.buildingOf("  "))
    }

    @Test
    fun `楼层推断`() {
        assertEquals(1, RoomKeyNormalizer.floorOf("A-101"))
        assertEquals(2, RoomKeyNormalizer.floorOf("A-203"))
        assertEquals(12, RoomKeyNormalizer.floorOf("A-1205"))
        assertNull(RoomKeyNormalizer.floorOf(null))
    }
}
