package com.shangkele.core.update

/**
 * 版本名比较。
 *
 * 版本名形如 `0.10.0-w6`：**只有 `-` 前面那部分参与比较**，后缀是里程碑代号
 * （w6 = 第 6 周），它跟版本高低无关 —— `0.10.0-w6` 之后的 `0.10.1-w6` 才是更新，
 * 换成 `0.10.0-w7` 并不代表更"新"。
 *
 * 数字部分必须**逐段当整数比**，不能比字符串：
 * 字符串比较下 `"0.9.9" > "0.10.0"`（因为 '9' > '1'），
 * 结果就是升到 0.10.0 之后永远收不到更新提示 —— 而且不会有任何报错。
 */
object VersionCompare {

    /** @return 负数 = [a] 比 [b] 旧；0 = 相同；正数 = [a] 比 [b] 新 */
    fun compare(a: String, b: String): Int {
        val left = numericParts(a)
        val right = numericParts(b)
        val size = maxOf(left.size, right.size)
        for (i in 0 until size) {
            // 段数不齐时短的补 0：1.2 == 1.2.0
            val l = left.getOrElse(i) { 0 }
            val r = right.getOrElse(i) { 0 }
            if (l != r) return l.compareTo(r)
        }
        return 0
    }

    /** [candidate] 是否比 [current] 新。 */
    fun isNewer(candidate: String, current: String): Boolean = compare(candidate, current) > 0

    /**
     * 取出版本名里的数字段。
     *
     * 容忍三种常见写法：前导 `v`（`v0.10.0`）、后缀代号（`0.10.0-w6`）、
     * 以及段里混了非数字（`0.10.0.beta` → 0.10.0）。
     */
    private fun numericParts(version: String): List<Int> {
        val head = version.trim()
            .removePrefix("v")
            .removePrefix("V")
            .substringBefore('-')
            .substringBefore('+')
        return head.split('.')
            .map { segment -> segment.takeWhile { it.isDigit() } }
            .map { it.toIntOrNull() ?: 0 }
    }
}
