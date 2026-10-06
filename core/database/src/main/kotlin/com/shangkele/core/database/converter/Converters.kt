package com.shangkele.core.database.converter

import androidx.room.TypeConverter
import com.shangkele.core.common.week.WeeksCodec

/**
 * Room 类型转换器。
 *
 * 周次集合统一存成 JSON 数组文本（如 `[1,2,3,4]`），编解码统一走 [WeeksCodec]，
 * 避免 SQL 层与 Kotlin 层出现两套实现。查询策略见 docs/05-数据模型.md §三。
 */
class Converters {

    @TypeConverter
    fun weeksToString(weeks: Set<Int>): String = WeeksCodec.encode(weeks)

    @TypeConverter
    fun stringToWeeks(raw: String): Set<Int> = WeeksCodec.decode(raw)
}
