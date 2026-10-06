package com.shangkele.core.model

/**
 * 「导入时，请求里的校历要不要覆盖库里已有的值」这条判断。
 *
 * 单独拎出来是因为它曾经出过一次**不报错的错**：
 * `ensureActiveSemester` 在学期已存在时直接返回，导入请求里的
 * `startDateEpochDay` 被静默丢弃 —— 用户在导入页把开学日期改对了，
 * 课表却照旧按旧日期算周次，而且界面上没有任何异常。
 *
 * 规则本身很简单，但它决定「用户点的按钮到底有没有生效」，
 * 所以固化成纯函数并配单测。
 */
object SemesterEdit {

    /** 请求里的开学日期有效、且与库里不同 → 需要写入。 */
    fun needsDateUpdate(existing: Semester, requestedStartEpochDay: Long): Boolean =
        requestedStartEpochDay > 0L && existing.startDateEpochDay != requestedStartEpochDay

    /** 请求里的总周数有效、且与库里不同 → 需要写入。 */
    fun needsTotalWeeksUpdate(existing: Semester, requestedTotalWeeks: Int): Boolean =
        requestedTotalWeeks > 0 && existing.totalWeeks != requestedTotalWeeks

    /**
     * 该不该把库里的**占位校历**换成真实值。
     *
     * 两个条件缺一不可：
     *  - 库里存的恰好是我早期写死的占位值 —— 说明它不是你设的；
     *  - 你**从没亲自设过**（`confirmed == false`）—— 设过就绝不碰。
     *
     * 加这条自愈是因为已经装过的机器库里留着占位值，光改常量它们永远修不好；
     * 而让用户自己去设置里改一遍，本身就是在为我的错误埋单。
     */
    fun shouldHealPlaceholder(
        existing: Semester,
        confirmed: Boolean,
        placeholder: Long,
    ): Boolean = !confirmed && existing.startDateEpochDay == placeholder

    /**
     * 把请求里的值合到已有学期上。
     *
     * 无效值（≤ 0）一律不覆盖 —— 那是「没填」而不是「填成 0」。
     */
    fun merge(existing: Semester, requestedStartEpochDay: Long, requestedTotalWeeks: Int): Semester =
        existing.copy(
            startDateEpochDay = if (needsDateUpdate(existing, requestedStartEpochDay)) {
                requestedStartEpochDay
            } else {
                existing.startDateEpochDay
            },
            totalWeeks = if (needsTotalWeeksUpdate(existing, requestedTotalWeeks)) {
                requestedTotalWeeks
            } else {
                existing.totalWeeks
            },
        )
}
