package com.shangkele.core.jwgl

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 从课表页面文本里刮出来的「时间线索」。
 *
 * 为什么要做这件事：`第几周` 和 `每节几点上课` 这两样，
 * 先前都得用户手填 —— 而填错一周，整张课表就全部错位；填错作息，
 * 上课静音和「该出发了」会在错误的时刻触发。这两件事都**不会报错**。
 * 所以能从教务页面自动读出来，就不该让人手填。
 *
 * 解析全部放在 Kotlin 侧（而不是注入的 JS 里），因为正则解析是最容易出错的
 * 一环，放 JS 字符串里没法单测。
 */
data class PageHints(
    val pageUrl: String = "",
    /** 页面上显示的当前周次，如「第 5 周」 */
    val currentWeek: Int? = null,
    val sectionTimes: List<DetectedSectionTime> = emptyList(),
    /** 页面上出现的日期字符串，原样保留便于排查 */
    val rawDates: List<String> = emptyList(),
    /** 页面文本长度（0 说明没读到内容，注入脚本可能没生效） */
    val textLength: Int = 0,
    /**
     * 页面文本的开头一段，原样保留。
     *
     * 用途只有一个：**识别不准时能看见教务页面到底长什么样**。
     * 前几次「第一周是哪天」的判断之所以靠猜，就是因为看不到原始文本；
     * 把它摆给用户（和开发者）看，比再加一条猜测规则有用得多。
     */
    val sampleText: String = "",
) {
    val hasUsableHint: Boolean get() = currentWeek != null || sectionTimes.isNotEmpty()
}

/** 页面上识别到的一段作息。 */
data class DetectedSectionTime(
    val fromSection: Int,
    val toSection: Int,
    val startMinutes: Int,
    val endMinutes: Int,
) {
    val sectionLabel: String
        get() = if (toSection != fromSection) "第${fromSection}-${toSection}节" else "第${fromSection}节"

    val timeLabel: String get() = "${fmt(startMinutes)}-${fmt(endMinutes)}"

    val label: String get() = "$sectionLabel $timeLabel"

    private fun fmt(minutes: Int): String =
        "%02d:%02d".format(minutes / 60, minutes % 60)
}

object PageHintsParser {

    /** 回传给界面的页面文本长度上限，够看清结构即可。 */
    const val SAMPLE_TEXT_LIMIT = 1500

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 注入脚本回传的载荷：`{"url":"...","text":"页面可见文本"}` */
    fun parse(payload: String): PageHints {
        val text = runCatching {
            json.parseToJsonElement(payload).jsonObject["text"]?.jsonPrimitive?.contentOrNull
        }.getOrNull().orEmpty()
        val url = runCatching {
            json.parseToJsonElement(payload).jsonObject["url"]?.jsonPrimitive?.contentOrNull
        }.getOrNull().orEmpty()

        return PageHints(
            pageUrl = url,
            currentWeek = parseCurrentWeek(text),
            sectionTimes = parseSectionTimes(text),
            rawDates = DATE.findAll(text).map { it.value }.take(10).toList(),
            textLength = text.length,
            sampleText = text.take(SAMPLE_TEXT_LIMIT),
        )
    }

    // ---- 当前周次 ----

    /** 「第N周」的基础写法。 */
    private val WEEK_ANY = Regex("""第\s*(\d{1,2})\s*周""")

    /**
     * 明确标注「本周 / 当前」的写法，优先级最高。
     *
     * `第N周(本周)` 和 `当前周 3` 这类写法是页面在**告诉我们**答案，直接用。
     */
    private val WEEK_MARKED_CURRENT = listOf(
        Regex("""第\s*(\d{1,2})\s*周\s*[（(\[【]?\s*(?:本周|当前)"""),
        Regex("""(?:本周|当前|现在)\s*第?\s*(\d{1,2})\s*周"""),
        Regex("""(?:本周|当前|现在)周[^\d\n]{0,4}(\d{1,2})"""),
        // 排除「周次：第1周 第2周 …」这种选择器写法（`第` 后面跟的是列表，不是答案）
        Regex("""周次[^\d\n第]{0,8}(\d{1,2})"""),
    )

    /** 紧跟在「第N周」之后的字样里出现这些词，说明这一周**不是**当前周。 */
    private val NOT_CURRENT_HINTS = listOf("非本周", "不是本周")

    /**
     * 从页面文本里找当前周次。**读不准就返回 null，绝不给一个错的。**
     *
     * 这里曾经是「取页面上第一个 `第N周`」，在正方课表上错得很彻底：
     * 课表页有周次选择器（列出第 1…20 周），用户在教务系统里点开「第 1 周」，
     * 我们就会把 1 当成当前周次，再反推出「第一周周一 = 今天所在周的周一」，
     * **整张课表的本周判定全错，而且不报错**。
     *
     * 所以判定分成两步：
     *  1. 页面上明确写了「本周/当前」的，用它。
     *  2. 否则只有当整页只出现**一个**不同的「第N周」时才敢用 ——
     *     出现多个就说明那是选择器或列表，不是「当前是第几周」，宁可返回 null
     *     让用户自己设。标了「非本周」的一律排除。
     */
    fun parseCurrentWeek(text: String): Int? {
        WEEK_MARKED_CURRENT.forEach { pattern ->
            val match = pattern.find(text) ?: return@forEach
            val value = match.groupValues[1].toIntOrNull() ?: return@forEach
            if (value in 1..30) return value
        }

        val distinct = WEEK_ANY.findAll(text)
            .filter { match ->
                val tailEnd = minOf(text.length, match.range.last + 8)
                val tail = text.substring(match.range.last + 1, tailEnd)
                NOT_CURRENT_HINTS.none { tail.contains(it) }
            }
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .filter { it in 1..30 }
            .toSet()

        return distinct.singleOrNull()
    }

    // ---- 作息时间 ----

    /** 节次 + 时间的完整写法，如「第1-2节 08:00-09:40」「1-2 08:00-09:40」 */
    private val SECTION_RANGE_WITH_TIME = Regex(
        """第?\s*(\d{1,2})\s*[-–—~～至]\s*(\d{1,2})\s*节?\s*[:：]?\s*""" +
            """(\d{1,2})[:：](\d{2})\s*[-–—~～至]\s*(\d{1,2})[:：](\d{2})""",
    )

    /** 单节 + 时间，如「第3节 10:00-10:45」 */
    private val SINGLE_SECTION_WITH_TIME = Regex(
        """第?\s*(\d{1,2})\s*节\s*[:：]?\s*""" +
            """(\d{1,2})[:：](\d{2})\s*[-–—~～至]\s*(\d{1,2})[:：](\d{2})""",
    )

    private val DATE = Regex("""\d{4}\s*[-/年]\s*\d{1,2}\s*[-/月]\s*\d{1,2}\s*日?""")

    /**
     * 解析作息时间。
     *
     * 两种写法都试，**按出现位置排序后去重**（同一段可能被两个正则先后命中）。
     * 单节写法要求节次显式出现，不做「看到 4 组时间就当成 1~4 节」这种推断 ——
     * 猜错了用户不一定看得出来，而作息错会连带静音和出发提醒一起错。
     */
    fun parseSectionTimes(text: String): List<DetectedSectionTime> {
        val found = mutableListOf<Pair<Int, DetectedSectionTime>>()

        // 先跑分块正则，并把命中区间记下来
        val rangeMatches = SECTION_RANGE_WITH_TIME.findAll(text).toList()
        val occupied = rangeMatches.map { it.range }

        rangeMatches.forEach { m ->
            val from = m.groupValues[1].toIntOrNull() ?: return@forEach
            val to = m.groupValues[2].toIntOrNull() ?: return@forEach
            build(from, to, m.groupValues[3], m.groupValues[4], m.groupValues[5], m.groupValues[6])
                ?.let { found += m.range.first to it }
        }

        SINGLE_SECTION_WITH_TIME.findAll(text).forEach { m ->
            // 必须跳过已被分块匹配覆盖的位置：
            // 「第1-2节 08:00-09:40」里单节正则会从「2节 08:00-09:40」再匹配一次，
            // 凭空多出一条「第2节」，把作息表写坏。
            if (occupied.any { m.range.first in it }) return@forEach

            val section = m.groupValues[1].toIntOrNull() ?: return@forEach
            build(
                section, section,
                m.groupValues[2], m.groupValues[3], m.groupValues[4], m.groupValues[5],
            )?.let { found += m.range.first to it }
        }

        return found
            .sortedBy { it.first }
            .map { it.second }
            .distinctBy { it.fromSection to it.toSection }
            .filter { it.fromSection in 1..20 }
            .sortedBy { it.fromSection }
    }

    private fun build(
        from: Int,
        to: Int,
        sh: String,
        sm: String,
        eh: String,
        em: String,
    ): DetectedSectionTime? {
        val startHour = sh.toIntOrNull() ?: return null
        val startMin = sm.toIntOrNull() ?: return null
        val endHour = eh.toIntOrNull() ?: return null
        val endMin = em.toIntOrNull() ?: return null
        if (startHour !in 0..23 || endHour !in 0..23) return null
        if (startMin !in 0..59 || endMin !in 0..59) return null

        val start = startHour * 60 + startMin
        val end = endHour * 60 + endMin
        // 结束必须晚于开始，否则是解析到了别的东西（比如两个无关的时间挨在一起）
        if (end <= start) return null
        // 单节超过 3 小时不可能是正常作息
        if (end - start > 180) return null

        return DetectedSectionTime(
            fromSection = from,
            toSection = maxOf(from, to),
            startMinutes = start,
            endMinutes = end,
        )
    }
}
