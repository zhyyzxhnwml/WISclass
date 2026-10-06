package com.shangkele.feature.schedule.theme

import androidx.compose.ui.graphics.Color

/**
 * 课程配色。
 *
 * 用课程名 hash 作种子，保证同一门课跨周次、跨学期配色稳定；
 * 深色模式单独一套（见 docs/07-荣耀200适配清单.md §七）。
 */
object CoursePalette {

    private val LIGHT = listOf(
        Color(0xFF3B6FD8),
        Color(0xFF2E9E7E),
        Color(0xFFD9822B),
        Color(0xFF8E5BB5),
        Color(0xFFD9534F),
        Color(0xFF2D9CDB),
        Color(0xFF6B8E23),
        Color(0xFFB33771),
        Color(0xFF00838F),
        Color(0xFF8E6E53),
    )

    private val DARK = listOf(
        Color(0xFF7FA6F0),
        Color(0xFF6FD3B4),
        Color(0xFFF0B36B),
        Color(0xFFC99BE0),
        Color(0xFFED8A86),
        Color(0xFF6FC6ED),
        Color(0xFFAFCB6B),
        Color(0xFFE58BAF),
        Color(0xFF4FBFC9),
        Color(0xFFC2A98F),
    )

    fun color(seed: Int, dark: Boolean = false): Color {
        val palette = if (dark) DARK else LIGHT
        val index = ((seed % palette.size) + palette.size) % palette.size
        return palette[index]
    }
}
