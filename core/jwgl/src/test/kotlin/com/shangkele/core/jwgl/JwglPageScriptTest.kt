package com.shangkele.core.jwgl

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 注入脚本是拼字符串拼出来的，最容易出错的地方是转义和桥名不一致，
 * 这里把它当作纯字符串产物来守。
 */
class JwglPageScriptTest {

    private val script = JwglPageScript.buildFetchScript("2026", "3")

    @Test
    fun `桥名与脚本里调用的一致`() {
        assertTrue(script.contains("window.${JwglPageScript.BRIDGE_NAME}.onScheduleJson"))
        assertTrue(script.contains("window.${JwglPageScript.BRIDGE_NAME}.onFetchFailed"))
    }

    @Test
    fun `XHR 钩子包含桥调用与课表特征`() {
        assertTrue(JwglPageScript.HOOK_XHR.contains("window.${JwglPageScript.BRIDGE_NAME}.onScheduleJson"))
        assertTrue(JwglPageScript.HOOK_XHR.contains("kbList"))
        assertTrue(JwglPageScript.HOOK_XHR.contains("__sklHooked"))
    }

    @Test
    fun `携带全部候选端点`() {
        JwglEndpoints.SCHEDULE_ENDPOINTS.forEach { endpoint ->
            val relative = endpoint.removePrefix(JwglEndpoints.BASE_URL)
            assertTrue("候选缺少 $relative", script.contains(relative))
        }
    }

    @Test
    fun `携带学期参数与模块码`() {
        assertTrue(script.contains("\"2026\""))
        assertTrue(script.contains("\"3\""))
        assertTrue(script.contains("gnmkdm=${JwglEndpoints.GNMKDM_STUDENT_SCHEDULE}"))
    }

    @Test
    fun `会在页面里自发现地址`() {
        assertTrue(script.contains("xskbcx"))
        assertTrue(script.contains("__discover__"))
    }

    @Test
    fun `不在教务域名下会直接报错而不是白跑`() {
        assertTrue(script.contains("location.host.indexOf('jwgl')"))
    }

    @Test
    fun `参数里的引号会被转义`() {
        val evil = JwglPageScript.buildFetchScript("20\"26", "3\\x")
        assertTrue(evil.contains("\\\"26"))
        assertTrue(evil.contains("3\\\\x"))
        // 转义后不应出现裸露的结束引号把脚本截断
        assertTrue(!evil.contains("\"20\"26\""))
    }
}
