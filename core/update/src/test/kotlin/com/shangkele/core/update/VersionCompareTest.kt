package com.shangkele.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 版本比较测试。
 *
 * 这条逻辑错了的后果很具体：**永远收不到更新提示**，而且不报错 ——
 * 用户只会以为「作者没发新版」。
 */
class VersionCompareTest {

    @Test
    fun `逐段按整数比而不是按字符串比`() {
        // 字符串比较下 "0.9.9" > "0.10.0"（'9' > '1'），
        // 那样升到 0.10.0 之后就再也收不到更新了
        assertTrue(VersionCompare.isNewer("0.10.0", "0.9.9"))
        assertTrue(VersionCompare.isNewer("0.10.0-w6", "0.9.9-w5"))
        assertFalse(VersionCompare.isNewer("0.9.9", "0.10.0"))
    }

    @Test
    fun `里程碑后缀不参与比较`() {
        // w6/w7 是周次代号，不是版本高低
        assertEquals(0, VersionCompare.compare("0.10.0-w7", "0.10.0-w6"))
        // 只改后缀不算更新，改数字才算
        assertTrue(VersionCompare.isNewer("0.10.1-w6", "0.10.0-w7"))
    }

    @Test
    fun `容忍前导 v 与段数不齐`() {
        assertEquals(0, VersionCompare.compare("v0.10.0", "0.10.0"))
        assertEquals(0, VersionCompare.compare("1.2", "1.2.0"))
        assertTrue(VersionCompare.isNewer("1.2.1", "1.2"))
    }

    @Test
    fun `段里混了非数字也不会崩`() {
        // "beta" 段取不出数字，当 0 处理；末尾补 0 后与 0.10.0 等价
        assertEquals(0, VersionCompare.compare("0.10.0.beta", "0.10.0"))
        assertTrue(VersionCompare.isNewer("0.10.1.beta", "0.10.0"))
    }

    @Test
    fun `相同版本不算更新`() {
        assertFalse(VersionCompare.isNewer("0.10.0-w6", "0.10.0-w6"))
    }

    @Test
    fun `空字符串被当成 0 而不是崩溃`() {
        assertEquals(0, VersionCompare.compare("", ""))
        assertTrue(VersionCompare.isNewer("0.1", ""))
    }
}
