package com.shangkele.core.jwgl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 页面文本解析测试。
 *
 * 这类解析的失败模式很讨厌：**不报错，只是给一个错的周次**。
 * 而错一周会让整张课表的「本周有没有这门课」全判错。所以宁可返回 null 不猜。
 */
class PageHintsParserTest {

    // ---- 当前周次 ----

    @Test
    fun `识别「第N周」`() {
        assertEquals(5, PageHintsParser.parseCurrentWeek("同学们，这是第5周的内容"))
        assertEquals(12, PageHintsParser.parseCurrentWeek("第 12 周课表"))
    }

    @Test
    fun `识别周次选择器的写法`() {
        assertEquals(7, PageHintsParser.parseCurrentWeek("周次：7"))
        assertEquals(3, PageHintsParser.parseCurrentWeek("当前周 3"))
    }

    @Test
    fun `页面上没有周次就返回 null 而不是猜一个`() {
        assertNull(PageHintsParser.parseCurrentWeek("这是一段完全无关的文字"))
        assertNull(PageHintsParser.parseCurrentWeek(""))
    }

    @Test
    fun `超出合理范围的周次被丢弃`() {
        // 「第99周」不可能是真实周次，多半是解析到了别的东西（比如教室号）
        assertNull(PageHintsParser.parseCurrentWeek("第99周"))
        assertNull(PageHintsParser.parseCurrentWeek("第0周"))
    }

    @Test
    fun `同一个周次重复出现仍可用`() {
        assertEquals(3, PageHintsParser.parseCurrentWeek("第3周 共20周 第3周"))
    }

    @Test
    fun `明确标注本周的直接采用`() {
        assertEquals(7, PageHintsParser.parseCurrentWeek("第7周（本周）"))
        assertEquals(7, PageHintsParser.parseCurrentWeek("第7周(本周)"))
        assertEquals(4, PageHintsParser.parseCurrentWeek("本周是第4周"))
        assertEquals(4, PageHintsParser.parseCurrentWeek("当前第4周"))
    }

    /**
     * 这是最要命的一种页面：正方课表的周次选择器把第 1…20 周都列了出来。
     *
     * 旧实现「取第一个」在这里会拿到 1 —— 而 1 是**用户点开的那一周**，
     * 不是当前周次。再拿它反推「第一周周一」就等于把开学日期设成今天，
     * 整张课表全错，且界面上没有任何异常。
     */
    @Test
    fun `页面上列出多个周次时拒绝猜测`() {
        assertNull(PageHintsParser.parseCurrentWeek("第1周 第2周 第3周 第4周 第5周"))
        assertNull(PageHintsParser.parseCurrentWeek("周次：第1周 第2周 ... 第20周"))
    }

    @Test
    fun `标了非本周的那一周不算当前周`() {
        // 正方课表页在选中周不是本周时会写成「第1周(非本周)」
        assertNull(PageHintsParser.parseCurrentWeek("第1周(非本周)"))
        // 另一处标了本周的仍然算数
        assertEquals(5, PageHintsParser.parseCurrentWeek("第1周(非本周) 第5周(本周)"))
    }

    // ---- 作息时间 ----

    @Test
    fun `识别分块写法「第1-2节 0800-0940」`() {
        val result = PageHintsParser.parseSectionTimes("作息时间 第1-2节 08:00-09:40")
        assertEquals(1, result.size)
        assertEquals(1, result[0].fromSection)
        assertEquals(2, result[0].toSection)
        assertEquals(8 * 60, result[0].startMinutes)
        assertEquals(9 * 60 + 40, result[0].endMinutes)
    }

    @Test
    fun `识别单节写法`() {
        val result = PageHintsParser.parseSectionTimes("第3节 10:00-10:45")
        assertEquals(1, result.size)
        assertEquals(3, result[0].fromSection)
        assertEquals(3, result[0].toSection)
    }

    @Test
    fun `识别常见的全角冒号与短横变体`() {
        val result = PageHintsParser.parseSectionTimes("第1-2节：08：00～09：40")
        assertEquals(1, result.size)
        assertEquals(8 * 60, result[0].startMinutes)
    }

    @Test
    fun `完整的作息表能被整段解析出来`() {
        val text = """
            作息时间
            第1-2节 08:30-10:10
            第3-4节 10:20-12:00
            第5-6节 14:00-15:40
            第7-8节 15:50-17:30
        """.trimIndent()
        val result = PageHintsParser.parseSectionTimes(text)
        assertEquals(4, result.size)
        assertEquals(listOf(1, 3, 5, 7), result.map { it.fromSection })
        assertEquals(8 * 60 + 30, result[0].startMinutes)
        assertEquals(17 * 60 + 30, result[3].endMinutes)
    }

    @Test
    fun `结束早于开始的时间被丢弃`() {
        // 多半是两个无关的时间凑到了一起
        val result = PageHintsParser.parseSectionTimes("第1-2节 10:00-09:00")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `单节时长超过三小时被丢弃`() {
        val result = PageHintsParser.parseSectionTimes("第1节 08:00-15:00")
        assertTrue("明显不是一个课时，不能采信", result.isEmpty())
    }

    @Test
    fun `没有作息信息时返回空列表`() {
        assertTrue(PageHintsParser.parseSectionTimes("今天天气不错").isEmpty())
        assertTrue(PageHintsParser.parseSectionTimes("").isEmpty())
    }

    @Test
    fun `重复命中的同一节次会去重`() {
        // 分块正则和单节正则可能先后命中同一段
        val text = "第1-2节 08:00-09:40 第1节 08:00-08:45"
        val result = PageHintsParser.parseSectionTimes(text)
        assertEquals("第1节只应出现一次", 1, result.count { it.fromSection == 1 && it.toSection == 1 })
    }

    // ---- 载荷解析 ----

    @Test
    fun `能解析注入脚本回传的载荷`() {
        val payload = """{"url":"http://jwgl.example.edu.cn/x.html","text":"第6周 第1-2节 08:30-10:10"}"""
        val hints = PageHintsParser.parse(payload)
        assertEquals(6, hints.currentWeek)
        assertEquals(1, hints.sectionTimes.size)
        assertTrue(hints.pageUrl.contains("jwgl"))
        assertTrue(hints.hasUsableHint)
    }

    @Test
    fun `载荷格式不对时不崩溃`() {
        val hints = PageHintsParser.parse("这不是 JSON")
        assertNull(hints.currentWeek)
        assertTrue(hints.sectionTimes.isEmpty())
        assertEquals(0, hints.textLength)
    }

    @Test
    fun `页面上什么都没有时如实反映为读不到`() {
        val hints = PageHintsParser.parse("""{"url":"","text":""}""")
        assertEquals(0, hints.textLength)
        assertTrue(!hints.hasUsableHint)
    }
}
