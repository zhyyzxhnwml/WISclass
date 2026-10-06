package com.shangkele.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimetableLayoutTest {

    private fun course(
        name: String,
        weekday: Int = 1,
        start: Int = 1,
        end: Int = 2,
        weeks: Set<Int> = (1..16).toSet(),
    ) = Course(
        semesterId = 1L,
        name = name,
        teacher = "老师",
        roomRaw = "A-101",
        roomKey = "A-101",
        credits = 3f,
        courseType = "必修",
        teachingClass = null,
        weekday = weekday,
        startSection = start,
        endSection = end,
        weeks = weeks,
        colorSeed = name.hashCode(),
    )

    @Test
    fun `本周课程进 currentWeek`() {
        val result = TimetableLayout.layout(listOf(course("高数", weeks = setOf(3, 4, 5))), week = 4)
        assertEquals(listOf("高数"), result.currentWeek.map { it.name })
        assertTrue(result.offWeek.isEmpty())
    }

    @Test
    fun `非本周课程在空格子里灰显`() {
        val courses = listOf(
            course("高数", weekday = 1, start = 1, weeks = setOf(3, 4)),
            course("物理", weekday = 2, start = 3, weeks = setOf(9, 10)),
        )
        val result = TimetableLayout.layout(courses, week = 4)

        assertEquals(listOf("高数"), result.currentWeek.map { it.name })
        assertEquals(listOf("物理"), result.offWeek.map { it.name })
        assertEquals(2, result.total)
    }

    // ---- 课上完之后不再显示 ----

    @Test
    fun `周次超过课程最大周次后整条不再显示`() {
        val math = course("高数", weekday = 1, start = 1, weeks = (1..8).toSet())
        val physics = course("物理", weekday = 2, start = 3, weeks = (1..16).toSet())

        // 第 8 周（高数的最后一周）它还在
        assertEquals(
            listOf("高数"),
            TimetableLayout.layout(listOf(math), week = 8).currentWeek.map { it.name },
        )

        // 第 9 周起高数整条消失：既不进本周，也不再灰显
        val week9 = TimetableLayout.layout(listOf(math, physics), week = 9)
        assertEquals(listOf("物理"), week9.currentWeek.map { it.name })
        assertTrue("已上完的课不该再占格子", week9.offWeek.isEmpty())
    }

    @Test
    fun `最后一节课所在的那一周本身还要显示`() {
        val c = course("高数", weeks = setOf(1, 2, 3))
        assertFalse(TimetableLayout.isFinishedBy(c, 3))
        assertTrue(TimetableLayout.isFinishedBy(c, 4))
    }

    @Test
    fun `还没开始的课仍然灰显`() {
        // 第 4 周翻到一门 9 周才开始的课：灰着提示「这格以后有课」才有意义
        val result = TimetableLayout.layout(
            listOf(course("物理", weekday = 2, start = 3, weeks = (9..16).toSet())),
            week = 4,
        )
        assertTrue(result.currentWeek.isEmpty())
        assertEquals(listOf("物理"), result.offWeek.map { it.name })
    }

    @Test
    fun `断面周次的课要到最后一节之后才消失`() {
        // 1-4 周 + 9-12 周断开：第 5~8 周属于「本周没课但还会再上」→ 仍灰显
        val c = course("断面课", weeks = (1..4).toSet() + (9..12).toSet())
        assertFalse(TimetableLayout.isFinishedBy(c, 8))
        assertEquals(listOf("断面课"), TimetableLayout.layout(listOf(c), week = 6).offWeek.map { it.name })
        // 第 13 周起才是真的上完了
        assertTrue(TimetableLayout.isFinishedBy(c, 13))
        assertTrue(TimetableLayout.layout(listOf(c), week = 13).offWeek.isEmpty())
    }

    @Test
    fun `周次解析失败的记录不会被静默隐藏`() {
        // 周次集合为空说明没解析出来。宁可继续灰着让人看见，也不能让它凭空消失 ——
        // 看不见的数据比难看的数据危险得多。
        val broken = course("解析失败的课", weeks = emptySet())
        assertFalse(TimetableLayout.isFinishedBy(broken, 20))
        assertEquals(
            listOf("解析失败的课"),
            TimetableLayout.layout(listOf(broken), week = 20).offWeek.map { it.name },
        )
    }

    // ---- 叠字回归：真机上出现「本周课和非本周课画在同一格」----

    @Test
    fun `本节次范围完全相同时不灰显`() {
        val courses = listOf(
            course("本周课", weekday = 3, start = 1, end = 2, weeks = setOf(4)),
            course("非本周课", weekday = 3, start = 1, end = 2, weeks = setOf(9)),
        )
        val result = TimetableLayout.layout(courses, week = 4)
        assertEquals(listOf("本周课"), result.currentWeek.map { it.name })
        assertTrue("完全重合时不该灰显，否则会叠字", result.offWeek.isEmpty())
    }

    @Test
    fun `本周是连堂 非本周是单节 也不能叠`() {
        // 这是真机上出问题的那种：一条 1-2 节、一条 1 节，起止节次不相同
        val courses = listOf(
            course("连堂本周课", weekday = 1, start = 1, end = 2, weeks = setOf(4)),
            course("单节非本周课", weekday = 1, start = 1, end = 1, weeks = setOf(9)),
        )
        val result = TimetableLayout.layout(courses, week = 4)
        assertEquals(listOf("连堂本周课"), result.currentWeek.map { it.name })
        assertTrue(result.offWeek.isEmpty())
    }

    @Test
    fun `非本周课程与本周课程部分重叠时也要排除`() {
        val courses = listOf(
            course("本周课", weekday = 1, start = 1, end = 2, weeks = setOf(4)),
            // 2-3 节与 1-2 节在第 2 节重叠
            course("非本周课", weekday = 1, start = 2, end = 3, weeks = setOf(9)),
        )
        val result = TimetableLayout.layout(courses, week = 4)
        assertTrue(result.offWeek.isEmpty())
    }

    @Test
    fun `非本周课程被本周课程完全包住时排除`() {
        val courses = listOf(
            course("本周大课", weekday = 1, start = 1, end = 4, weeks = setOf(4)),
            course("非本周小课", weekday = 1, start = 2, end = 3, weeks = setOf(9)),
        )
        assertTrue(TimetableLayout.layout(courses, week = 4).offWeek.isEmpty())
    }

    @Test
    fun `不重叠时仍然灰显`() {
        val courses = listOf(
            course("本周课", weekday = 1, start = 1, end = 2, weeks = setOf(4)),
            course("非本周课", weekday = 1, start = 3, end = 4, weeks = setOf(9)),
        )
        val result = TimetableLayout.layout(courses, week = 4)
        assertEquals(listOf("非本周课"), result.offWeek.map { it.name })
    }

    @Test
    fun `不同星期不算重叠`() {
        val courses = listOf(
            course("本周课", weekday = 1, start = 1, end = 2, weeks = setOf(4)),
            course("非本周课", weekday = 2, start = 1, end = 2, weeks = setOf(9)),
        )
        assertEquals(
            listOf("非本周课"),
            TimetableLayout.layout(courses, week = 4).offWeek.map { it.name },
        )
    }

    @Test
    fun `overlapsSlot 用区间相交而不是相等`() {
        val a = course("a", weekday = 1, start = 1, end = 2)
        val b = course("b", weekday = 1, start = 1, end = 1)
        val c = course("c", weekday = 1, start = 2, end = 3)
        val d = course("d", weekday = 1, start = 3, end = 4)
        val otherDay = course("e", weekday = 2, start = 1, end = 2)

        assertTrue(TimetableLayout.overlapsSlot(a, b))
        assertTrue(TimetableLayout.overlapsSlot(a, c))
        assertFalse(TimetableLayout.overlapsSlot(a, d))
        assertFalse(TimetableLayout.overlapsSlot(a, otherDay))
    }

    // ---- 灰显块彼此也不能叠 ----

    @Test
    fun `多项非本周课程互相重叠时只留一条`() {
        val courses = listOf(
            course("小节", weekday = 4, start = 1, end = 1, weeks = setOf(9)),
            course("连堂", weekday = 4, start = 1, end = 2, weeks = setOf(9)),
        )
        val result = TimetableLayout.layout(courses, week = 5)
        assertEquals(listOf("连堂"), result.offWeek.map { it.name })
    }

    @Test
    fun `同一位置多条灰显课程留覆盖周次最广的`() {
        // 两条都是「这周不上、但后面还会上」，所以要挑覆盖周次多的那条，信息更全。
        // 注意选的周次必须落在**两条课程都还没上完**的范围里 ——
        // 上完的课不会被灰显，那样就测不到这条规则了。
        val courses = listOf(
            course("只上两头的选修", weekday = 4, start = 7, end = 8, weeks = setOf(1, 12)),
            course("覆盖更多周的选修", weekday = 4, start = 7, end = 8, weeks = setOf(1, 3, 5, 9, 12)),
        )
        val result = TimetableLayout.layout(courses, week = 8)

        assertTrue(result.currentWeek.isEmpty())
        assertEquals(listOf("覆盖更多周的选修"), result.offWeek.map { it.name })
    }

    @Test
    fun `单双周课程在本周正常 另一条不灰显`() {
        val courses = listOf(
            course("单周课", weekday = 3, start = 5, end = 6, weeks = (1..16).filter { it % 2 == 1 }.toSet()),
            course("双周课", weekday = 3, start = 5, end = 6, weeks = (1..16).filter { it % 2 == 0 }.toSet()),
        )

        val oddWeek = TimetableLayout.layout(courses, week = 5)
        assertEquals(listOf("单周课"), oddWeek.currentWeek.map { it.name })
        assertTrue(oddWeek.offWeek.isEmpty())

        val evenWeek = TimetableLayout.layout(courses, week = 6)
        assertEquals(listOf("双周课"), evenWeek.currentWeek.map { it.name })
        assertTrue(evenWeek.offWeek.isEmpty())
    }

    @Test
    fun `空课表`() {
        assertTrue(TimetableLayout.layout(emptyList(), week = 1).isEmpty)
    }

    @Test
    fun `灰显块按星期与节次排序`() {
        // 三条都「第 10 周不上、但第 20 周还有」，用来验证排序稳定
        val sparse = setOf(1, 20)
        val courses = listOf(
            course("晚的", weekday = 5, start = 9, weeks = sparse),
            course("早的", weekday = 5, start = 1, weeks = sparse),
            course("周二", weekday = 2, start = 3, weeks = sparse),
        )
        val result = TimetableLayout.layout(courses, week = 10)
        assertTrue(result.currentWeek.isEmpty())
        assertEquals(listOf("周二", "早的", "晚的"), result.offWeek.map { it.name })
    }
}
