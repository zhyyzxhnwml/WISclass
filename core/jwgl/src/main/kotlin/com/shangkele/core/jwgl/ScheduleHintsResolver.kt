package com.shangkele.core.jwgl

import com.shangkele.core.model.TimeSlot
import com.shangkele.core.model.WeekCalculator

/**
 * 把页面线索换算成可以直接写库的东西。
 *
 * 全是纯函数，因为有两条错误代价极高的换算：
 *  - **第一周是哪天**：错一周，整张课表的「本周有没有这门课」全判错
 *  - **各节几点上课**：错了会让上课静音和「该出发了」在错误时刻触发
 *
 * 两者都不会报错，只会安静地给错信息。所以每一步都要测。
 */
object ScheduleHintsResolver {

    /**
     * 已知「今天在第 N 周」，定位到第 1 周所在的那一周（返回该周的周一）。
     *
     * **注意这只定位到「那一周」，不足以定出开学日期。** 周次是从开学那天起算的
     * （开学日是周六，第 1 周就是周六 ~ 周五），而页面只给了周次、没给开学日是周几。
     * 所以这里返回的周一只是给日期选择器一个合理的初始值，
     * 真正写入的必须是用户选中的那一天 —— 见 `ImportViewModel.applyDetectedCalendar`。
     *
     * @param todayEpochDay 今天的 epochDay
     * @param currentWeek 页面上显示的当前周次（1 起）
     */
    fun firstWeekMonday(todayEpochDay: Long, currentWeek: Int): Long {
        val week = currentWeek.coerceAtLeast(1)
        val weekday = WeekCalculator.weekdayOf(todayEpochDay) // 1=周一 … 7=周日
        val thisMonday = todayEpochDay - (weekday - 1)
        return thisMonday - (week - 1) * 7L
    }

    /**
     * 把页面上识别到的作息换算成逐节的作息表。
     *
     * 教务页面给的往往是**分块**时间（「第1-2节 08:00-09:40」），
     * 而 App 需要的是逐节的（第1节几点到几点、第2节几点到几点）。
     *
     * 分块要拆开就必须知道单节时长与课间长度，而页面没给 ——
     * 所以这里**只在拿到单节级数据时才敢直接用**；分块会按
     * [ASSUMED_PERIOD_MINUTES] 假设拆分，并在结果里标出 `assumed = true`，
     * 让界面明确告诉用户「这是推算的，请核对」。
     */
    data class Resolution(
        val slots: List<TimeSlot>,
        /** true = 拆分用到了 45 分钟假设，结果需要用户核对 */
        val assumed: Boolean,
        val notes: List<String>,
    ) {
        val isEmpty: Boolean get() = slots.isEmpty()
    }

    fun resolveTimeSlots(detected: List<DetectedSectionTime>): Resolution {
        if (detected.isEmpty()) return Resolution(emptyList(), false, emptyList())

        val single = detected.filter { it.fromSection == it.toSection }
        val blocked = detected.filter { it.fromSection != it.toSection }

        // 优先用单节数据：它是页面直接给的，不用猜
        if (single.isNotEmpty() && blocked.isEmpty()) {
            return Resolution(
                slots = single.map { TimeSlot(it.fromSection, it.startMinutes, it.endMinutes) }
                    .sortedBy { it.section },
                assumed = false,
                notes = listOf("页面给出的是逐节时间，可直接采用"),
            )
        }

        // 只有分块数据：按固定时长假设拆开
        val notes = mutableListOf<String>()
        if (single.isNotEmpty()) {
            notes += "页面同时给了逐节与分块时间，只采用逐节部分"
            return Resolution(
                slots = single.map { TimeSlot(it.fromSection, it.startMinutes, it.endMinutes) }
                    .sortedBy { it.section },
                assumed = false,
                notes = notes,
            )
        }

        val expanded = mutableListOf<TimeSlot>()
        var assumed = false
        for (block in blocked) {
            val count = block.toSection - block.fromSection + 1
            if (count <= 0) continue

            val total = block.endMinutes - block.startMinutes
            val period: Int
            val breakEach: Int
            if (count > 1) {
                // 课间 = (总时长 - 单节时长×节数) / (节数-1)
                val breakTotal = total - ASSUMED_PERIOD_MINUTES * count
                if (breakTotal < 0) {
                    // 总时长连单节都放不下，说明节次或时间解析错了，放弃这一块
                    notes += "第${block.fromSection}-${block.toSection}节的时长与节数不匹配，已跳过"
                    continue
                }
                period = ASSUMED_PERIOD_MINUTES
                breakEach = breakTotal / (count - 1)
            } else {
                period = total
                breakEach = 0
            }

            for (i in 0 until count) {
                val start = block.startMinutes + i * (period + breakEach)
                val end = start + period
                if (end > block.endMinutes) break
                expanded += TimeSlot(block.fromSection + i, start, end)
            }
            assumed = true
        }

        if (assumed) {
            notes += "页面只给了分块时间（如「第1-2节 08:00-09:40」），" +
                "已按每节 $ASSUMED_PERIOD_MINUTES 分钟推算拆分，请核对"
        }
        return Resolution(expanded.sortedBy { it.section }, assumed, notes)
    }

    /**
     * 与现有作息表对比，只列出**真的不一样**的节次。
     *
     * 返回空表示没差异，界面就不该打扰用户。
     */
    data class Difference(val section: Int, val current: String, val detected: String)

    fun diff(current: List<TimeSlot>, detected: List<TimeSlot>): List<Difference> {
        val byCurrent = current.associateBy { it.section }
        return detected.mapNotNull { slot ->
            val old = byCurrent[slot.section] ?: return@mapNotNull Difference(
                section = slot.section,
                current = "（无）",
                detected = slot.rangeLabel(),
            )
            if (old.startMinutes == slot.startMinutes && old.endMinutes == slot.endMinutes) {
                null
            } else {
                Difference(slot.section, old.rangeLabel(), slot.rangeLabel())
            }
        }
    }

    /** 每节时长。页面一般不给，所以拆分分块时只能假设。 */
    const val ASSUMED_PERIOD_MINUTES = 45
}
