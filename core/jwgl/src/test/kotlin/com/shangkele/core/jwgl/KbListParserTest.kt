package com.shangkele.core.jwgl

import com.shangkele.core.model.CourseSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class KbListParserTest {

    private val semesterId = 7L

    @Test
    fun `解析标准响应`() {
        val json = """
            {
              "kbList": [
                {"kcmc":"高等数学","kcxzmc":"必修","xf":"5.0","cdmc":"教学楼A-101","xm":"张明",
                 "jc":"0102","xq":"1","zcd":"01-16","jxbmc":"高等数学-01班","bjmc":"软件工程1班",
                 "zcs":"01","zce":"16","jcor":"01","jcend":"02"}
              ],
              "xxmc":"某某大学","xqmc":"2026-2027学年第一学期","xsxm":"张三","xsbh":"2026001"
            }
        """.trimIndent()

        val result = KbListParser.parse(json, semesterId)

        assertEquals(1, result.courses.size)
        assertEquals("某某大学", result.schoolName)
        assertEquals("2026-2027学年第一学期", result.semesterName)
        assertEquals("张三", result.studentName)
        assertEquals("2026001", result.studentNo)
        assertTrue(result.skipped.isEmpty())

        val course = result.courses.first()
        assertEquals("高等数学", course.name)
        assertEquals("张明", course.teacher)
        assertEquals("教学楼A-101", course.roomRaw)
        assertEquals("A-101", course.roomKey)
        assertEquals(1, course.weekday)
        assertEquals(1, course.startSection)
        assertEquals(2, course.endSection)
        assertEquals((1..16).toSet(), course.weeks)
        assertEquals(5.0f, course.credits, 0.001f)
        assertEquals("必修", course.courseType)
        assertEquals(semesterId, course.semesterId)
        assertEquals(CourseSource.API, course.source)
    }

    @Test
    fun `单双周拆成两条时按时间格合并`() {
        val json = """
            {"kbList":[
              {"kcmc":"大学英语","xm":"李静","cdmc":"B-203","xq":"4","jcor":"03","jcend":"04","zcd":"1-16(单)"},
              {"kcmc":"大学英语","xm":"李静","cdmc":"B-203","xq":"4","jcor":"03","jcend":"04","zcd":"1-16(双)"}
            ]}
        """.trimIndent()

        val courses = KbListParser.parse(json, semesterId).courses

        assertEquals(1, courses.size)
        assertEquals((1..16).toSet(), courses[0].weeks)
    }

    @Test
    fun `断周次的两条记录合并后并集正确`() {
        val json = """
            {"kbList":[
              {"kcmc":"体育","xm":"周涛","xq":"4","jcor":"05","jcend":"06","zcd":"1-8"},
              {"kcmc":"体育","xm":"周涛","xq":"4","jcor":"05","jcend":"06","zcd":"10-16"}
            ]}
        """.trimIndent()

        val courses = KbListParser.parse(json, semesterId).courses

        assertEquals(1, courses.size)
        assertEquals((1..8).toSet() + (10..16).toSet(), courses[0].weeks)
    }

    @Test
    fun `只有 zcd 时周次仍完整`() {
        val json = """{"kbList":[{"kcmc":"高数","xq":"1","jcor":"1","jcend":"2","zcd":"1-16(单)"}]}"""
        val course = KbListParser.parse(json, semesterId).courses.single()
        assertEquals((1..16).filter { it % 2 == 1 }.toSet(), course.weeks)
    }

    @Test
    fun `只有 zcs 和 zce 时退化解析`() {
        val json = """{"kbList":[{"kcmc":"高数","xq":"1","jcor":"1","jcend":"2","zcs":"3","zce":"12"}]}"""
        val course = KbListParser.parse(json, semesterId).courses.single()
        assertEquals((3..12).toSet(), course.weeks)
    }

    @Test
    fun `zce 越界时裁到总周数`() {
        val json = """{"kbList":[{"kcmc":"高数","xq":"1","jcor":"1","jcend":"2","zcs":"1","zce":"30"}]}"""
        val course = KbListParser.parse(json, semesterId, totalWeeks = 20).courses.single()
        assertEquals((1..20).toSet(), course.weeks)
    }

    @Test
    fun `星期为零时视为周日`() {
        val json = """{"kbList":[{"kcmc":"选修课","xq":"0","jcor":"1","jcend":"2","zcd":"1-8"}]}"""
        assertEquals(7, KbListParser.parse(json, semesterId).courses.single().weekday)
    }

    @Test
    fun `字段别名 xqj jcs jxdd 也能解析`() {
        val json = """{"kbList":[{"kcmc":"大学物理","jsxm":"刘伟","jxdd":"综合楼D-101","xqj":"3","jcs":"0506","zcd":"1-16"}]}"""
        val course = KbListParser.parse(json, semesterId).courses.single()

        assertEquals(3, course.weekday)
        assertEquals(5, course.startSection)
        assertEquals(6, course.endSection)
        assertEquals("刘伟", course.teacher)
        assertEquals("D-101", course.roomKey)
    }

    @Test
    fun `只有 jc 编码时也能算节次`() {
        val json = """{"kbList":[{"kcmc":"高数","xq":"1","jc":"1112","zcd":"1-16"}]}"""
        val course = KbListParser.parse(json, semesterId).courses.single()
        assertEquals(11, course.startSection)
        assertEquals(12, course.endSection)
    }

    @Test
    fun `缺课程名或星期的条目被跳过并记录`() {
        val json = """
            {"kbList":[
              {"xq":"1","jcor":"1","jcend":"2"},
              {"kcmc":"数据结构","xq":"9","jcor":"1","jcend":"2"},
              {"kcmc":"线性代数","xq":"2","jcor":"3","jcend":"4","zcd":"1-16"}
            ]}
        """.trimIndent()

        val result = KbListParser.parse(json, semesterId)

        assertEquals(1, result.courses.size)
        assertEquals("线性代数", result.courses[0].name)
        assertEquals(2, result.skipped.size)
        assertTrue(result.skipped.any { it.contains("数据结构") })
    }

    @Test
    fun `顶层直接是数组也能解析`() {
        val json = """[{"kcmc":"高数","xq":"1","jcor":"1","jcend":"2","zcd":"1-16"}]"""
        assertEquals(1, KbListParser.parse(json, semesterId).courses.size)
    }

    @Test
    fun `kbList 为空返回空课表而不是报错`() {
        val result = KbListParser.parse("""{"kbList":[],"xxmc":"某某大学"}""", semesterId)
        assertTrue(result.courses.isEmpty())
        assertEquals("某某大学", result.schoolName)
    }

    @Test
    fun `返回 HTML 时判定为会话失效`() {
        assertThrows(JwglSessionExpiredException::class.java) {
            KbListParser.parse("<html><body>请先登录</body></html>", semesterId)
        }
    }

    @Test
    fun `返回空内容时报解析异常`() {
        assertThrows(JwglParseException::class.java) {
            KbListParser.parse("   ", semesterId)
        }
    }

    @Test
    fun `返回非法 JSON 时报解析异常`() {
        assertThrows(JwglParseException::class.java) {
            KbListParser.parse("{not json at all", semesterId)
        }
    }

    @Test
    fun `未知字段不影响解析`() {
        val json = """{"kbList":[{"kcmc":"高数","xq":"1","jcor":"1","jcend":"2","zcd":"1-16","xqmc":"2026-2027-1","newField":"X"}]}"""
        assertEquals(1, KbListParser.parse(json, semesterId).courses.size)
    }

    @Test
    fun `WebView 渠道会标记来源为 WEB`() {
        val json = """{"kbList":[{"kcmc":"高数","xq":"1","jcor":"1","jcend":"2","zcd":"1-16"}]}"""
        val course = KbListParser.parse(json, semesterId, source = CourseSource.WEB).courses.single()
        assertEquals(CourseSource.WEB, course.source)
    }
}
