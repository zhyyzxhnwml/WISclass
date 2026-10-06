package com.shangkele.core.context.departure

import com.shangkele.core.common.classroom.RoomKeyNormalizer
import com.shangkele.core.model.Course
import com.shangkele.core.model.TimeSlot
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 为什么要留这么多提前量。 */
enum class DepartureReason {
    /** 和上节课在同一栋楼，走几步就到 */
    SAME_BUILDING,

    /** 换了栋楼，得留出走路时间 */
    DIFFERENT_BUILDING,

    /** 拿不到楼栋信息（如纯数字教室），用默认值 */
    UNKNOWN_BUILDING,

    /** 课间比需要的提前量还短，一下课就得动身 */
    TIGHT_GAP,
}

data class DepartureAdvice(
    val courseName: String,
    val roomLabel: String,
    val startMinutes: Int,
    val leaveAtMinutes: Int,
    /** 实际采用的提前量 */
    val leadMinutes: Int,
    val reason: DepartureReason,
    /** 与上一节课之间的空闲分钟数；今天第一节为 null */
    val gapMinutes: Int?,
) {
    val startLabel: String get() = TimeSlot.formatMinutes(startMinutes)

    val leaveLabel: String get() = TimeSlot.formatMinutes(leaveAtMinutes)

    fun minutesUntilLeave(nowMinutes: Int): Int = leaveAtMinutes - nowMinutes

    /** 一句话说明为什么是这个提前量。 */
    val reasonLabel: String
        get() = when (reason) {
            DepartureReason.SAME_BUILDING -> "同楼，留 $leadMinutes 分钟足够"
            DepartureReason.DIFFERENT_BUILDING -> "要换楼，留 $leadMinutes 分钟"
            DepartureReason.UNKNOWN_BUILDING -> "留 $leadMinutes 分钟"
            DepartureReason.TIGHT_GAP -> "课间只有 ${gapMinutes ?: 0} 分钟，一下课就得走"
        }
}

data class DeparturePlan(
    val advice: DepartureAdvice?,
    /** 现在就该提醒出发 */
    val shouldNotifyNow: Boolean,
    /** 下次重新计算的时刻 */
    val nextBoundaryAtMillis: Long?,
)

/**
 * 「该出发了」的零配置推算。
 *
 * 不要求用户标地图，只用两条现成信息：
 *  1. **要不要换楼** —— 从教室名里解析出楼栋前缀（`A-101` → `A`，`3B301` → `3B`）
 *  2. **课间有多长** —— 上节课结束到这节课开始的空闲分钟数
 *
 * 得到的提前量：
 *
 * | 情况 | 提前量 |
 * |---|---|
 * | 同楼 | 5 分钟 |
 * | 换楼 | 15 分钟 |
 * | 楼栋信息拿不到 | 10 分钟（默认） |
 * | 课间比提前量还短 | 一下课就走 |
 *
 * 局限（写清楚，免得日后误判）：校园 POI 与步行时间是 W3 之后再补的增强，
 * 这里只做相对判断，不知道教学楼之间到底有多远。
 */
object DeparturePlanner {

    const val DEFAULT_LEAD_MINUTES = 10
    const val SAME_BUILDING_LEAD_MINUTES = 5
    const val DIFFERENT_BUILDING_LEAD_MINUTES = 15

    private const val FALLBACK_MINUTES_OF_DAY = 5

    private data class Window(
        val course: Course,
        val startMinutes: Int,
        val endMinutes: Int,
        val building: String?,
    )

    fun plan(
        coursesToday: List<Course>,
        slots: Map<Int, TimeSlot>,
        nowMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): DeparturePlan {
        val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
        val nowMinutes = now.hour * 60 + now.minute

        val windows = coursesToday
            .mapNotNull { course ->
                val start = slots[course.startSection] ?: return@mapNotNull null
                val end = slots[course.endSection] ?: return@mapNotNull null
                if (end.endMinutes <= start.startMinutes) return@mapNotNull null
                Window(
                    course = course,
                    startMinutes = start.startMinutes,
                    endMinutes = end.endMinutes,
                    building = RoomKeyNormalizer.buildingOf(
                        course.roomKey ?: RoomKeyNormalizer.normalize(course.roomRaw),
                    ),
                )
            }
            .sortedBy { it.startMinutes }

        val upcoming = windows.firstOrNull { it.startMinutes > nowMinutes }
            ?: return DeparturePlan(
                advice = null,
                shouldNotifyNow = false,
                nextBoundaryAtMillis = fallbackBoundary(now.toLocalDate(), zone),
            )

        val previous = windows.lastOrNull { it.endMinutes <= upcoming.startMinutes }
        val advice = buildAdvice(upcoming, previous)

        return DeparturePlan(
            advice = advice,
            shouldNotifyNow = nowMinutes in advice.leaveAtMinutes until upcoming.startMinutes,
            nextBoundaryAtMillis = atMinutesOfDay(
                now.toLocalDate(),
                if (nowMinutes < advice.leaveAtMinutes) advice.leaveAtMinutes else upcoming.startMinutes,
                zone,
            ),
        )
    }

    private fun buildAdvice(upcoming: Window, previous: Window?): DepartureAdvice {
        val gap = previous?.let { upcoming.startMinutes - it.endMinutes }
        val sameBuilding = previous != null &&
            previous.building != null &&
            previous.building == upcoming.building
        val bothKnown = previous?.building != null && upcoming.building != null

        val reason = when {
            previous == null -> DepartureReason.UNKNOWN_BUILDING
            sameBuilding -> DepartureReason.SAME_BUILDING
            bothKnown -> DepartureReason.DIFFERENT_BUILDING
            else -> DepartureReason.UNKNOWN_BUILDING
        }
        val lead = when (reason) {
            DepartureReason.SAME_BUILDING -> SAME_BUILDING_LEAD_MINUTES
            DepartureReason.DIFFERENT_BUILDING -> DIFFERENT_BUILDING_LEAD_MINUTES
            else -> DEFAULT_LEAD_MINUTES
        }

        val tight = previous != null && gap != null && gap < lead
        val leaveAt = if (tight) previous.endMinutes else upcoming.startMinutes - lead

        return DepartureAdvice(
            courseName = upcoming.course.name,
            roomLabel = upcoming.course.roomRaw.ifBlank { "教室待定" },
            startMinutes = upcoming.startMinutes,
            leaveAtMinutes = leaveAt,
            leadMinutes = if (tight) gap ?: 0 else lead,
            reason = if (tight) DepartureReason.TIGHT_GAP else reason,
            gapMinutes = gap,
        )
    }

    private fun fallbackBoundary(today: LocalDate, zone: ZoneId): Long =
        atMinutesOfDay(today.plusDays(1), FALLBACK_MINUTES_OF_DAY, zone)

    private fun atMinutesOfDay(date: LocalDate, minutes: Int, zone: ZoneId): Long =
        date.atStartOfDay(zone).plusMinutes(minutes.toLong()).toInstant().toEpochMilli()
}
