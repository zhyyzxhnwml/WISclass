package com.shangkele.core.common.week

import java.util.TreeSet

/**
 * 周次集合的文本编解码，格式为 JSON 数组文本，如 `[1,2,3,4]`。
 *
 * 数据库列类型是 TEXT，Room 侧不参与比较；「本周有哪些课」在 Kotlin 侧过滤，
 * 理由见 docs/05-数据模型.md §三。
 */
object WeeksCodec {

    private const val EMPTY = "[]"

    fun encode(weeks: Set<Int>): String =
        if (weeks.isEmpty()) EMPTY
        else weeks.sorted().joinToString(prefix = "[", postfix = "]", separator = ",")

    fun decode(raw: String?): Set<Int> {
        val body = raw?.trim()?.removePrefix("[")?.removeSuffix("]") ?: return emptySet()
        if (body.isBlank()) return emptySet()
        val result = TreeSet<Int>()
        for (part in body.split(',')) {
            val value = part.trim().toIntOrNull() ?: continue
            if (value > 0) result.add(value)
        }
        return result
    }
}
