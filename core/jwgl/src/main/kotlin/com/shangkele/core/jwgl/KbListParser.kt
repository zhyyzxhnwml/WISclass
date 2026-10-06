package com.shangkele.core.jwgl

import com.shangkele.core.common.classroom.RoomKeyNormalizer
import com.shangkele.core.common.week.SectionCodeParser
import com.shangkele.core.common.week.WeekParser
import com.shangkele.core.model.Course
import com.shangkele.core.model.CourseSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** 课表接口的解析结果。 */
data class KbScheduleResult(
    val courses: List<Course>,
    val schoolName: String?,
    val semesterName: String?,
    val studentName: String?,
    val studentNo: String?,
    /** 因关键字段缺失被跳过的条目，用于导入后提示用户「有 N 条没能识别」 */
    val skipped: List<String>,
)

/**
 * 正方教务 `kbList` 响应解析。
 *
 * 设计取向是**尽量容错、绝不因为一条脏数据丢掉整张课表**：
 *  - 字段名做多别名兜底（不同学校/版本命名不一致）
 *  - 关键字段（课程名 / 星期 / 节次）缺失的条目单独记录并跳过
 *  - 单双周被拆成两条记录的，按时间格合并取周次并集
 *
 * 只用 kotlinx.serialization 的 JSON 树模型，不依赖编译器插件。
 */
object KbListParser {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        allowTrailingComma = true
    }

    /**
     * @param raw 课表接口原样返回的文本
     * @param totalWeeks 学期总周数，用于裁剪越界周次
     * @param source 数据来源渠道（原生 API / WebView 注入）
     */
    fun parse(
        raw: String,
        semesterId: Long,
        totalWeeks: Int = WeekParser.DEFAULT_TOTAL_WEEKS,
        source: CourseSource = CourseSource.API,
    ): KbScheduleResult {
        val text = raw.trim()
        if (text.isEmpty()) throw JwglParseException("课表接口返回了空内容")
        // 未登录时正方会直接返回登录页 HTML
        if (text.startsWith("<")) throw JwglSessionExpiredException()

        val root: JsonElement = try {
            json.parseToJsonElement(text)
        } catch (e: Exception) {
            throw JwglParseException("课表 JSON 解析失败：${e.message}")
        }

        val array: JsonArray = when (root) {
            is JsonArray -> root
            is JsonObject -> root["kbList"] as? JsonArray ?: JsonArray(emptyList())
            else -> throw JwglParseException("课表响应结构不符合预期")
        }

        val skipped = mutableListOf<String>()
        val parsed = ArrayList<Course>(array.size)
        for (element in array) {
            val obj = element as? JsonObject ?: continue
            toCourse(obj, semesterId, totalWeeks, source, skipped)?.let { parsed += it }
        }

        return KbScheduleResult(
            courses = mergeSameTimeSlots(parsed),
            schoolName = root.objectOrNull()?.str("xxmc", "xxmcBz"),
            semesterName = root.objectOrNull()?.str("xqmc", "xnmc"),
            studentName = root.objectOrNull()?.str("xsxm", "xm"),
            studentNo = root.objectOrNull()?.str("xsbh", "xh"),
            skipped = skipped,
        )
    }

    private fun toCourse(
        obj: JsonObject,
        semesterId: Long,
        totalWeeks: Int,
        source: CourseSource,
        skipped: MutableList<String>,
    ): Course? {
        val name = obj.str("kcmc", "kcmcBz")
        if (name.isNullOrBlank()) {
            skipped += "<无课程名>"
            return null
        }

        val weekdayRaw = obj.str("xq", "xqj")
        val weekday = parseWeekday(weekdayRaw)
        if (weekday == null) {
            skipped += "$name（星期无法识别：${weekdayRaw ?: "空"}）"
            return null
        }

        val sections = parseSections(obj)
        if (sections == null) {
            skipped += "$name（节次无法识别）"
            return null
        }

        val roomRaw = obj.str("cdmc", "jxdd", "skdd").orEmpty()
        return Course(
            semesterId = semesterId,
            name = name,
            teacher = obj.str("xm", "jsxm").orEmpty(),
            roomRaw = roomRaw,
            roomKey = RoomKeyNormalizer.normalize(roomRaw),
            credits = obj.str("xf")?.toFloatOrNull() ?: 0f,
            courseType = obj.str("kcxzmc"),
            teachingClass = obj.str("jxbmc"),
            weekday = weekday,
            startSection = sections.first,
            endSection = sections.last,
            weeks = parseWeeks(obj, totalWeeks),
            colorSeed = name.hashCode(),
            source = source,
        )
    }

    /** `xq`/`xqj`：1=周一 … 7=周日；少数部署用 0 表示周日。 */
    private fun parseWeekday(raw: String?): Int? {
        val value = raw?.trim()?.toIntOrNull() ?: return null
        return when (value) {
            0 -> 7
            in 1..7 -> value
            else -> null
        }
    }

    /**
     * 节次优先用数值字段 `jcor`/`jcend`，退化到 `jc` / `jcs` 这类字符串编码。
     * 反了会有风险：`jc` 在不同部署里编码方式不一，数值字段更可靠。
     */
    private fun parseSections(obj: JsonObject): IntRange? {
        val start = obj.str("jcor", "jcqs")?.toIntOrNull()
        val end = obj.str("jcend", "jcjs")?.toIntOrNull()
        if (start != null && start > 0) {
            val safeEnd = end?.takeIf { it >= start } ?: start
            return start..safeEnd
        }
        return SectionCodeParser.parse(obj.str("jc", "jcs", "jcsdm"))
    }

    /**
     * 周次优先用 `zcd`（它保留了单双周、断续等完整信息），
     * 只有它缺失时才退化为 `zcs..zce`。
     *
     * 注意与 docs/03 里的「数值优先」相反 —— 因为 `zcs/zce` 只有首尾，
     * 会丢掉 `1-16(单)` 里的单周信息，实测损失更大。
     */
    private fun parseWeeks(obj: JsonObject, totalWeeks: Int): Set<Int> {
        val zcd = obj.str("zcd", "zcmc", "zcdBz")
        if (!zcd.isNullOrBlank()) {
            val parsed = WeekParser.parse(zcd, totalWeeks)
            if (parsed.isNotEmpty()) return parsed
        }

        val from = obj.str("zcs")?.toIntOrNull()
        val to = obj.str("zce")?.toIntOrNull()
        if (from != null && from > 0) {
            val safeTo = to?.takeIf { it >= from && it <= totalWeeks } ?: totalWeeks
            return (from..safeTo).toSet()
        }
        return (1..totalWeeks).toSet()
    }

    /**
     * 合并同一时间格的重复条目。
     *
     * 正方常把「1-16(单)」和「1-16(双)」拆成两条同样时间、同样课程的记录，
     * 不合并的话课表上会叠两块色块。
     */
    private fun mergeSameTimeSlots(courses: List<Course>): List<Course> {
        val grouped = LinkedHashMap<String, MutableList<Course>>()
        for (course in courses) {
            val key = buildString {
                append(course.name).append('|')
                append(course.teacher).append('|')
                append(course.weekday).append('|')
                append(course.startSection).append('-').append(course.endSection)
            }
            grouped.getOrPut(key) { mutableListOf() } += course
        }

        return grouped.map { (_, group) ->
            if (group.size == 1) {
                group.first()
            } else {
                val weeks = group.flatMap { it.weeks }.toSortedSet()
                group.first().copy(weeks = weeks)
            }
        }
    }

    private fun JsonElement.objectOrNull(): JsonObject? = this as? JsonObject

    /** 按别名顺序取第一个非空字符串字段。 */
    private fun JsonObject.str(vararg keys: String): String? {
        for (key in keys) {
            val value = (this[key] as? JsonPrimitive)?.contentOrNull?.trim()
            if (!value.isNullOrEmpty()) return value
        }
        return null
    }
}
