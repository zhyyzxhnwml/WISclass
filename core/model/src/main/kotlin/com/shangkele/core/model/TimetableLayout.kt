package com.shangkele.core.model

/**
 * 决定某个周次下课表要画哪些课，以及哪些要灰显。
 *
 * 灰显（「非本周」）是课程表类 App 的标配：一眼能看出这个格子平时有课、
 * 只是这周不上，而不是「这里什么都没排」。
 *
 * **但已经上完的课不灰显，直接不画**（见 [isFinishedBy]）：一周一周往后翻时，
 * 那些再也不会上的课不该继续占着格子。
 *
 * ## 最关键的一条规则：不许叠
 *
 * 本周课程与灰显课程**不能占同一块地方**，否则文字会叠在一起看不清。
 * 注意判定必须用**节次区间相交**，不能用「起止节次完全相同」：
 * 正方把同一门课拆成「1 节」和「1-2 节」两条是常事，用相等判断会漏掉，
 * 结果就是两块色块画在同一个位置、字糊成一团（真机踩过）。
 */
object TimetableLayout {

    data class Result(
        /** 本周要上的课，正常配色 */
        val currentWeek: List<Course>,
        /** 本周不上但需要灰显的课，彼此之间也不会重叠 */
        val offWeek: List<Course>,
    ) {
        val isEmpty: Boolean get() = currentWeek.isEmpty() && offWeek.isEmpty()

        val total: Int get() = currentWeek.size + offWeek.size
    }

    /** 同一天、且节次区间有交集。**不能**用相等代替。 */
    fun overlapsSlot(a: Course, b: Course): Boolean =
        a.weekday == b.weekday &&
            a.startSection <= b.endSection &&
            b.startSection <= a.endSection

    /**
     * 这门课是不是已经**上完了**（[week] 已经超过它的最后一个周次）。
     *
     * 上完的课再往后翻，每周都画一块灰色的「非本周」没有任何信息量 ——
     * 它不会再上了，教务系统也是直接不显示。所以直接从课表上拿掉。
     *
     * 只在「知道最后一节是第几周」时才敢拿掉：周次集合为空说明这条记录
     * 没解析出周次，那种情况宁可继续灰着让人看见，也不能让它静默消失 ——
     * 看不见的数据比难看的数据危险得多。
     */
    fun isFinishedBy(course: Course, week: Int): Boolean =
        course.weeks.isNotEmpty() && week > course.weeks.max()

    fun layout(courses: List<Course>, week: Int): Result {
        // 已上完的课整条丢弃，不参与「本周」也不参与灰显
        val active = courses.filterNot { isFinishedBy(it, week) }

        val current = active.filter { it.occursInWeek(week) }

        // 1. 先排除与本周课程有任何节次重叠的
        // 2. 再在剩下的里面贪心挑，保证灰显块彼此也不重叠
        val offWeek = active.asSequence()
            .filter { !it.occursInWeek(week) }
            .filter { candidate -> current.none { overlapsSlot(it, candidate) } }
            .sortedWith(
                compareBy(
                    { it.weekday },
                    { it.startSection },
                    // 同一位置优先留跨度大的，信息更完整
                    { -(it.endSection - it.startSection) },
                    // 再优先留覆盖周次多的
                    { -it.weeks.size },
                ),
            )
            .fold(mutableListOf<Course>()) { picked, candidate ->
                if (picked.none { overlapsSlot(it, candidate) }) picked.add(candidate)
                picked
            }

        return Result(currentWeek = current, offWeek = offWeek)
    }
}
