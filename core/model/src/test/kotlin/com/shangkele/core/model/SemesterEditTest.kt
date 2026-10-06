package com.shangkele.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 守的是「用户在导入页改的开学日期到底有没有落到库里」。
 *
 * 之前这条路径上是直接 return 的，改动被静默丢弃，
 * 表现是「我设置了，但课表还是从旧日期开始」——
 * 这类错误不崩不报错，只能靠回归测试钉住。
 */
class SemesterEditTest {

    private fun semester(
        startDateEpochDay: Long = 20_689L,
        totalWeeks: Int = 20,
    ) = Semester(
        id = 1L,
        xnm = "2026",
        xqm = "3",
        name = "2026-2027学年第一学期",
        startDateEpochDay = startDateEpochDay,
        totalWeeks = totalWeeks,
        isActive = true,
    )

    @Test
    fun `日期不同时必须写入`() {
        val existing = semester(startDateEpochDay = 20_689L)
        assertTrue(SemesterEdit.needsDateUpdate(existing, 20_703L))
    }

    @Test
    fun `日期相同时不做无谓写入`() {
        val existing = semester(startDateEpochDay = 20_703L)
        assertFalse(SemesterEdit.needsDateUpdate(existing, 20_703L))
    }

    @Test
    fun `无效日期不覆盖已有值`() {
        val existing = semester(startDateEpochDay = 20_703L)
        // 0 是「没填」的哨兵值，不是一个合法的开学日期
        assertFalse(SemesterEdit.needsDateUpdate(existing, 0L))
        assertFalse(SemesterEdit.needsDateUpdate(existing, -1L))
        assertEquals(20_703L, SemesterEdit.merge(existing, 0L, 20).startDateEpochDay)
    }

    @Test
    fun `合并后日期与总周数都取请求值`() {
        val existing = semester(startDateEpochDay = 20_689L, totalWeeks = 20)
        val merged = SemesterEdit.merge(existing, 20_703L, 18)

        assertEquals(20_703L, merged.startDateEpochDay)
        assertEquals(18, merged.totalWeeks)
        // 学期身份不能被动到
        assertEquals(existing.id, merged.id)
        assertEquals(existing.xnm, merged.xnm)
        assertEquals(existing.xqm, merged.xqm)
    }

    @Test
    fun `总周数无效时不覆盖`() {
        val existing = semester(totalWeeks = 20)
        assertFalse(SemesterEdit.needsTotalWeeksUpdate(existing, 0))
        assertEquals(20, SemesterEdit.merge(existing, 20_689L, 0).totalWeeks)
    }

    // ---- 占位校历自愈 ----

    @Test
    fun `库里还是占位值且用户没设过时应当自愈`() {
        val placeholder = SchoolDefaults.LEGACY_PLACEHOLDER_START_DATE_EPOCH_DAY
        val existing = semester(startDateEpochDay = placeholder)
        assertTrue(SemesterEdit.shouldHealPlaceholder(existing, confirmed = false, placeholder = placeholder))
    }

    @Test
    fun `用户设过就一个字都不动`() {
        val placeholder = SchoolDefaults.LEGACY_PLACEHOLDER_START_DATE_EPOCH_DAY
        val existing = semester(startDateEpochDay = placeholder)
        // 哪怕值恰好等于占位值，只要用户确认过就不能覆盖
        assertFalse(SemesterEdit.shouldHealPlaceholder(existing, confirmed = true, placeholder = placeholder))
    }

    @Test
    fun `用户自己选的值不等于占位值所以不会被自愈碰到`() {
        val placeholder = SchoolDefaults.LEGACY_PLACEHOLDER_START_DATE_EPOCH_DAY
        // 用户选了 2026-08-29
        val existing = semester(startDateEpochDay = SchoolDefaults.DEFAULT_START_DATE_EPOCH_DAY)
        assertFalse(SemesterEdit.shouldHealPlaceholder(existing, confirmed = false, placeholder = placeholder))
    }

    @Test
    fun `占位值和真实默认值必须是不同的两天`() {
        assertEquals(
            "2026-08-24",
            WeekCalculator.fullDate(SchoolDefaults.LEGACY_PLACEHOLDER_START_DATE_EPOCH_DAY),
        )
        assertEquals(
            "2026-08-29",
            WeekCalculator.fullDate(SchoolDefaults.DEFAULT_START_DATE_EPOCH_DAY),
        )
        assertTrue(
            SchoolDefaults.LEGACY_PLACEHOLDER_START_DATE_EPOCH_DAY !=
                SchoolDefaults.DEFAULT_START_DATE_EPOCH_DAY,
        )
    }

    @Test
    fun `典型的重新导入场景不会把用户改过的日期重置`() {
        // 用户把开学日期设成 2026-09-07，之后再导入一次课表，
        // 请求里带的还是这个值 —— 库里的值应当纹丝不动
        val existing = semester(startDateEpochDay = 20_703L)
        val merged = SemesterEdit.merge(existing, 20_703L, existing.totalWeeks)
        assertEquals(20_703L, merged.startDateEpochDay)
    }
}
