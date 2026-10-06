package com.shangkele.core.model

/**
 * 作息表校验。
 *
 * 作息表由用户手改，很容易填错（结束早于开始、两节时间重叠、第 3 节比第 2 节还早）。
 * 这些错不会崩，但会让「该出发了」和上课静音在错误的时刻触发 ——
 * 安静的错比崩溃更难发现，所以要主动查出来摆到界面上。
 *
 * 只提示不阻止保存：用户可能只是在分步修改，中途状态本来就不合法。
 */
object TimeSlotValidator {

    data class Issue(val section: Int, val message: String)

    fun validate(slots: List<TimeSlot>): List<Issue> {
        if (slots.isEmpty()) return emptyList()
        val sorted = slots.sortedBy { it.section }
        val issues = mutableListOf<Issue>()

        sorted.forEachIndexed { index, slot ->
            if (slot.endMinutes <= slot.startMinutes) {
                issues += Issue(slot.section, "结束时间不晚于开始时间")
            }

            val previous = sorted.getOrNull(index - 1) ?: return@forEachIndexed
            if (previous.section == slot.section) return@forEachIndexed

            when {
                slot.startMinutes < previous.startMinutes ->
                    issues += Issue(slot.section, "比第 ${previous.section} 节还早")

                slot.startMinutes < previous.endMinutes ->
                    issues += Issue(slot.section, "与第 ${previous.section} 节时间重叠")
            }
        }
        return issues
    }

    /** 整天第一节的开始时间，用来快速核对「我们以为几点上课」。 */
    fun firstStartMinutes(slots: List<TimeSlot>): Int? =
        slots.minByOrNull { it.section }?.startMinutes

    /** 整天最后一节的结束时间。 */
    fun lastEndMinutes(slots: List<TimeSlot>): Int? =
        slots.maxByOrNull { it.section }?.endMinutes
}
