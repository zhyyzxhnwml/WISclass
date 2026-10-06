package com.shangkele.core.model

/** 课表变更类型。 */
enum class ScheduleChangeType {
    ADDED,
    REMOVED,
    TIME_CHANGED,
    ROOM_CHANGED,
    TEACHER_CHANGED,
    WEEKS_CHANGED,
}

data class ScheduleChange(
    val type: ScheduleChangeType,
    val courseName: String,
    val before: Course?,
    val after: Course?,
) {
    val title: String
        get() = when (type) {
            ScheduleChangeType.ADDED -> "新增课程"
            ScheduleChangeType.REMOVED -> "课程已移除"
            ScheduleChangeType.TIME_CHANGED -> "上课时间变动"
            ScheduleChangeType.ROOM_CHANGED -> "教室变动"
            ScheduleChangeType.TEACHER_CHANGED -> "授课教师变动"
            ScheduleChangeType.WEEKS_CHANGED -> "上课周次变动"
        }

    /** 一句话描述变化，例如「周一 第1-2节 → 周三 第3-4节」。 */
    val detail: String
        get() = when (type) {
            ScheduleChangeType.ROOM_CHANGED -> "${before?.roomRaw.orEmpty()} → ${after?.roomRaw.orEmpty()}"
            ScheduleChangeType.TEACHER_CHANGED -> "${before?.teacher.orEmpty()} → ${after?.teacher.orEmpty()}"
            ScheduleChangeType.WEEKS_CHANGED -> "${formatWeeks(before?.weeks)} → ${formatWeeks(after?.weeks)}"
            ScheduleChangeType.TIME_CHANGED ->
                "${slotLabel(before)} → ${slotLabel(after)}"
            ScheduleChangeType.ADDED -> slotLabel(after)
            ScheduleChangeType.REMOVED -> slotLabel(before)
        }

    private fun slotLabel(course: Course?): String {
        if (course == null) return "—"
        val weekday = "一二三四五六日".getOrNull(course.weekday - 1) ?: '?'
        return "周$weekday 第${course.startSection}-${course.endSection}节"
    }

    private fun formatWeeks(weeks: Set<Int>?): String {
        if (weeks.isNullOrEmpty()) return "—"
        return weeks.sorted().joinToString(",")
    }
}

/**
 * 课表差异比较。
 *
 * 用途：每次从教务系统刷新后与库内旧课表比对，让「选课调整 / 换教室 / 调周次」
 * 能被主动推送给用户，而不是等他某天上课跑错教室（见 docs/03-教务系统对接.md §十）。
 *
 * 匹配策略（两级）：
 *  1. **时间格匹配**：课程名 + 星期 + 起止节次完全一致的，认为是同一条，逐字段比差异。
 *  2. **同名兜底匹配**：只在旧课表 / 只在新课表里的，若课程名相同，判为「时间变动」
 *     而不是「一增一删」——这是用户实际最关心的场景。
 *
 * 已知局限：同一门课一周上两次且两次都改了时间时，同名兜底只能配对一次，
 * 剩余的会退化成增删。要完全准确需要教务系统提供教学班 ID，正方 kbList 里
 * 的 `jxbmc` 可作改进方向，但各校填充质量不一，W2 先不做。
 */
object ScheduleDiffer {

    fun diff(old: List<Course>, new: List<Course>): List<ScheduleChange> {
        val changes = mutableListOf<ScheduleChange>()

        val oldBySlot = old.groupBy { slotKey(it) }
        val newBySlot = new.groupBy { slotKey(it) }

        val commonKeys = oldBySlot.keys intersect newBySlot.keys
        for (key in commonKeys) {
            val before = oldBySlot.getValue(key).first()
            val after = newBySlot.getValue(key).first()
            if (before.teacher != after.teacher) {
                changes += ScheduleChange(ScheduleChangeType.TEACHER_CHANGED, before.name, before, after)
            }
            if (before.roomRaw != after.roomRaw) {
                changes += ScheduleChange(ScheduleChangeType.ROOM_CHANGED, before.name, before, after)
            }
            if (before.weeks != after.weeks) {
                changes += ScheduleChange(ScheduleChangeType.WEEKS_CHANGED, before.name, before, after)
            }
        }

        val unmatchedNew = (newBySlot.keys - commonKeys).toMutableSet()
        val oldOnlyKeys = oldBySlot.keys - commonKeys

        for (key in oldOnlyKeys) {
            val before = oldBySlot.getValue(key).first()
            val matchedKey = unmatchedNew.firstOrNull { candidate ->
                newBySlot.getValue(candidate).first().name == before.name
            }
            if (matchedKey != null) {
                unmatchedNew.remove(matchedKey)
                changes += ScheduleChange(
                    ScheduleChangeType.TIME_CHANGED,
                    before.name,
                    before,
                    newBySlot.getValue(matchedKey).first(),
                )
            } else {
                changes += ScheduleChange(ScheduleChangeType.REMOVED, before.name, before, null)
            }
        }

        for (key in unmatchedNew) {
            val after = newBySlot.getValue(key).first()
            changes += ScheduleChange(ScheduleChangeType.ADDED, after.name, null, after)
        }

        return changes
    }

    private fun slotKey(course: Course): String =
        "${course.name}|${course.weekday}|${course.startSection}|${course.endSection}"
}
