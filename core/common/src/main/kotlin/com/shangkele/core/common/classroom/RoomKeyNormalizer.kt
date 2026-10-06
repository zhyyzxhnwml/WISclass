package com.shangkele.core.common.classroom

/**
 * 教室名归一化。
 *
 * 教务系统里同一个教室的写法五花八门，必须先归一化成稳定的 POI 键，
 * 「该出发了」的路程计算才能命中校园 POI（见 docs/01-产品定位与差异化.md §3.2）。
 *
 * ```
 * "教学楼A-101" / "教A101" / "A101" / " A-101 "  ->  "A-101"
 * "多媒体教室B203"                               ->  "B-203"
 * "101"                                          ->  "101"（无法推断楼栋时原样返回）
 * ```
 */
object RoomKeyNormalizer {

    /** 需剥离的前缀，按长度从长到短匹配，避免「教学楼」被「教」先吃掉。 */
    private val PREFIXES = listOf(
        "多媒体教室", "阶梯教室", "教学楼", "实验楼", "综合楼", "信息楼", "工科楼",
        "理科楼", "文科楼", "艺术楼", "图书馆", "教学楼群", "大楼", "多媒体", "实验", "教室", "楼", "教",
    ).sortedByDescending { it.length }

    private val BUILDING_ROOM = Regex("""^([A-Z]+)\s*-?\s*(\d{2,4})$""")
    private val TRAILING_ROOM = Regex("""(\d{2,4})$""")
    /** 楼栋前缀：可选的数字 + 字母，如 "A" / "3B" / "1A"。 */
    private val BUILDING_PREFIX = Regex("""^(\d*[A-Z]+)""")

    /**
     * 归一化教室名。
     * @return 归一化后的键；无法归一化时返回 null
     */
    fun normalize(raw: String?): String? {
        val text = toHalfWidth(raw).replace(" ", "").uppercase()
        if (text.isEmpty()) return null

        var body = text
        var changed = true
        while (changed) {
            changed = false
            if (body.isEmpty()) break
            for (prefix in PREFIXES) {
                if (body.startsWith(prefix)) {
                    body = body.removePrefix(prefix)
                    changed = true
                    break
                }
            }
        }
        // 整串都是前缀词（如「教学楼」），视为无法归一化
        if (body.isEmpty()) return null

        BUILDING_ROOM.find(body)?.let { match ->
            return "${match.groupValues[1]}-${match.groupValues[2]}"
        }
        return body
    }

    /**
     * 取楼栋。
     *
     * ```
     * "A-101"  -> "A"        （有分隔符，直接取前半段）
     * "3B301"  -> "3B"       （没分隔符，取「数字+字母」前缀）
     * "1A207"  -> "1A"
     * "体育馆"  -> "体育馆"    （没有数字的场地名，整体当楼栋）
     * "101"    -> null       （纯数字，判断不出楼栋，宁可返回 null 也不要瞎猜）
     * ```
     *
     * 返回值会被「该出发了」用来判断「要不要换楼」，所以**宁可返回 null 也不能猜错**：
     * 猜错会给出错误的提前量，比不给提示更糟。
     */
    fun buildingOf(roomKey: String?): String? {
        val key = roomKey?.trim()?.uppercase().orEmpty()
        if (key.isEmpty()) return null

        val beforeDash = key.substringBefore('-')
        if (beforeDash.isNotBlank() && beforeDash != key) return beforeDash

        // 没有分隔符：没有数字的（体育馆、田径场）整体就是楼栋
        if (key.none { it.isDigit() }) return key

        return BUILDING_PREFIX.find(key)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
    }

    /** 从归一化键中取出房间号，如 "A-101" -> "101"。 */
    fun roomNumberOf(roomKey: String?): String? =
        roomKey?.substringAfterLast('-', "")?.takeIf { it.isNotBlank() } ?: roomKey

    /**
     * 楼层推断：用于「该出发了」的电梯排队时间估算。
     * `A-101` -> 1 楼；`A-1205` -> 12 楼。
     */
    fun floorOf(roomKey: String?): Int? {
        val room = roomName(roomKey) ?: return null
        val digits = TRAILING_ROOM.find(room)?.value ?: return null
        return when (digits.length) {
            2 -> 1
            3 -> digits.substring(0, 1).toIntOrNull()
            4 -> digits.substring(0, 2).toIntOrNull()
            else -> null
        }
    }

    private fun roomName(roomKey: String?): String? {
        val key = roomKey?.trim().orEmpty()
        if (key.isEmpty()) return null
        val tail = key.substringAfterLast('-', "")
        return if (tail.isNotBlank()) tail else key
    }

    /** 全角转半角。 */
    private fun toHalfWidth(raw: String?): String {
        if (raw == null) return ""
        val sb = StringBuilder(raw.length)
        for (ch in raw.trim()) {
            val mapped = when {
                ch.code == 0x3000 -> ' '
                ch.code in 0xFF01..0xFF5E -> (ch.code - 0xFEE0).toChar()
                else -> ch
            }
            sb.append(mapped)
        }
        return sb.toString()
    }
}
