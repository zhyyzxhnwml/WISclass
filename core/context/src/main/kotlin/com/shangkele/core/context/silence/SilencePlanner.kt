package com.shangkele.core.context.silence

import com.shangkele.core.model.Course
import com.shangkele.core.model.TimeSlot
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 一次静音决策的结果。 */
data class ClassSilencePlan(
    /** 现在是否应该静音 */
    val shouldBeSilent: Boolean,
    /** 正在上的课（不静音时为 null） */
    val currentCourseName: String?,
    /** 下一次需要重新计算的时刻（epoch millis）；null 表示暂时不需要再算 */
    val nextBoundaryAtMillis: Long?,
)

/**
 * 上课自动静音的纯逻辑。
 *
 * 调度本身（AlarmManager / 广播）不好测，但「现在该不该静音」「下一次什么时候重算」
 * 是纯函数，单独抽出来用单测守住 —— 这块算错了表现是「上课手机突然响了」，
 * 用户体感极差。
 */
object SilencePlanner {

    /** 今天没有更多边界时的兜底重算时刻：次日 00:05。 */
    private const val FALLBACK_MINUTES_OF_DAY = 5

    data class Window(val courseName: String, val startMinutes: Int, val endMinutes: Int)

    fun plan(
        coursesToday: List<Course>,
        slots: Map<Int, TimeSlot>,
        nowMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): ClassSilencePlan {
        val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
        val nowMinutes = now.hour * 60 + now.minute

        val windows = coursesToday
            .mapNotNull { course ->
                val start = slots[course.startSection] ?: return@mapNotNull null
                val end = slots[course.endSection] ?: return@mapNotNull null
                if (end.startMinutes <= start.startMinutes) return@mapNotNull null
                Window(course.name, start.startMinutes, end.endMinutes)
            }
            .sortedBy { it.startMinutes }

        // 用 until：下课那一分钟就该恢复，不能算作「还在上课」
        val current = windows.firstOrNull { nowMinutes in it.startMinutes until it.endMinutes }

        val nextBoundaryMinutes = windows
            .asSequence()
            .flatMap { sequenceOf(it.startMinutes, it.endMinutes) }
            .filter { it > nowMinutes }
            .minOrNull()

        val nextBoundaryMillis = if (nextBoundaryMinutes != null) {
            atMinutesOfDay(now.toLocalDate(), nextBoundaryMinutes, zone)
        } else {
            // 今天没有边界了（放假、或者课全上完了），次日凌晨再重算
            atMinutesOfDay(now.toLocalDate().plusDays(1), FALLBACK_MINUTES_OF_DAY, zone)
        }

        return ClassSilencePlan(
            shouldBeSilent = current != null,
            currentCourseName = current?.courseName,
            nextBoundaryAtMillis = nextBoundaryMillis,
        )
    }

    private fun atMinutesOfDay(date: LocalDate, minutes: Int, zone: ZoneId): Long =
        date.atStartOfDay(zone).plusMinutes(minutes.toLong()).toInstant().toEpochMilli()
}
