package com.shangkele.core.model

import java.time.LocalDate
import java.time.ZoneId

/**
 * 「随手拍」——**没有录音、只有照片**的那条笔记，桌面小组件拍照就落在这里。
 *
 * 为什么不给这类照片另建一套表：`photo` 的外键指向 `note.id`，
 * 一张照片必须挂在某条笔记上。与其为「没录音的照片」再写一套表和一套渲染，
 * 不如给它一条**没有音频的笔记** —— 现有时间轴、缩略图、按时间排序、
 * 长按删除、外键级联全都照用，同一套代码。
 *
 * 关键在 [startOfDayMs]：把这条笔记的「录音起点」定在**当天 0 点**。
 * 于是每张照片的 `offsetMs` 恰好等于它拍摄的**钟点**，[NotePhoto.offsetLabel]
 * 直接显示成 `14:32:05`，同一天内的先后也天然排对 —— 渲染侧一行都不用改。
 */
object ScratchNote {

    /** 认这类笔记靠标题前缀。见 [isScratch]。 */
    const val TITLE_PREFIX = "随手拍"

    /** 标题里的分隔符，`随手拍 · 10-06`。 */
    private const val SEPARATOR = " · "

    /** 当天那条笔记的标题，「随手拍 · 10-06」。 */
    fun titleFor(dateEpochDay: Long): String {
        val date = LocalDate.ofEpochDay(dateEpochDay)
        return TITLE_PREFIX + SEPARATOR + "%02d-%02d".format(date.monthValue, date.dayOfMonth)
    }

    /**
     * 当天 0 点的 epoch 毫秒，**按本地时区**算。
     *
     * 不能图省事写成 `dateEpochDay * 86_400_000`：那是 UTC 的 0 点。
     * 东八区会整体差 8 小时，照片标签全变成 `06:32` 这种错位钟点 ——
     * 而这个错误在界面上看起来完全正常，只是时间是错的。
     */
    fun startOfDayMs(dateEpochDay: Long): Long =
        LocalDate.ofEpochDay(dateEpochDay)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

    /**
     * 这条笔记是不是「随手拍」。
     *
     * 要求「前缀 + 分隔符」完整匹配，而不是只判 `startsWith(TITLE_PREFIX)`：
     * 否则用户自己起了个「随手拍了拍」的标题，也会被当成没有录音的笔记，
     * 界面上那个「转写」按钮就再也找不到了。
     */
    fun isScratch(title: String?): Boolean =
        title != null && (title == TITLE_PREFIX || title.startsWith(TITLE_PREFIX + SEPARATOR))
}
