package com.shangkele.core.common.week

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SectionCodeParserTest {

    @Test
    fun `四位节次串`() {
        assertEquals(1..2, SectionCodeParser.parse("0102"))
        assertEquals(3..4, SectionCodeParser.parse("0304"))
        assertEquals(9..10, SectionCodeParser.parse("0910"))
        assertEquals(11..12, SectionCodeParser.parse("1112"))
    }

    @Test
    fun `连堂实验`() {
        assertEquals(1..3, SectionCodeParser.parse("0103"))
        assertEquals(1..4, SectionCodeParser.parse("0104"))
    }

    @Test
    fun `单位数节次`() {
        assertEquals(5..5, SectionCodeParser.parse("05"))
        assertEquals(7..7, SectionCodeParser.parse("7"))
    }

    @Test
    fun `短横线写法`() {
        assertEquals(1..2, SectionCodeParser.parse("1-2"))
        assertEquals(3..4, SectionCodeParser.parse("3 ~ 4"))
    }

    @Test
    fun `非法输入返回 null`() {
        assertNull(SectionCodeParser.parse(null))
        assertNull(SectionCodeParser.parse(""))
        assertNull(SectionCodeParser.parse("abc"))
        assertNull(SectionCodeParser.parse("0000"))
    }
}
