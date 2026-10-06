package com.shangkele.core.jwgl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 这套判断是踩坑踩出来的：会话过期时正方返回 302 + 空响应体，
 * 早先只查响应体导致误报成「端点不对」。
 */
class ScheduleResponseClassifierTest {

    @Test
    fun `正常 JSON`() {
        val verdict = ScheduleResponseClassifier.classify(
            200,
            "application/json",
            """{"kbList":[{"kcmc":"高等数学"}],"xxmc":"某某大学"}""",
        )
        assertEquals(ScheduleResponseClassifier.Verdict.Json, verdict)
    }

    @Test
    fun `顶层是数组也算 JSON`() {
        assertEquals(
            ScheduleResponseClassifier.Verdict.Json,
            ScheduleResponseClassifier.classify(200, null, """[{"kcmc":"高数"}]"""),
        )
    }

    @Test
    fun `302 空响应体判定为会话过期`() {
        assertEquals(
            ScheduleResponseClassifier.Verdict.SessionExpired,
            ScheduleResponseClassifier.classify(302, null, ""),
        )
    }

    @Test
    fun `其他重定向状态同样判定为会话过期`() {
        listOf(301, 303, 307, 308).forEach { status ->
            assertEquals(
                "HTTP $status",
                ScheduleResponseClassifier.Verdict.SessionExpired,
                ScheduleResponseClassifier.classify(status, null, ""),
            )
        }
    }

    @Test
    fun `200 但响应体为空也判定为会话过期`() {
        assertEquals(
            ScheduleResponseClassifier.Verdict.SessionExpired,
            ScheduleResponseClassifier.classify(200, "text/html", "   "),
        )
    }

    @Test
    fun `返回登录页 HTML 判定为会话过期`() {
        val html = """<html><body><form action="/jwglxt/xtgl/login_slogin.html"></form></body></html>"""
        assertEquals(
            ScheduleResponseClassifier.Verdict.SessionExpired,
            ScheduleResponseClassifier.classify(200, "text/html;charset=UTF-8", html),
        )
    }

    @Test
    fun `404 判定为端点有问题而不是会话过期`() {
        val verdict = ScheduleResponseClassifier.classify(404, "text/html", "<html>404 Not Found</html>")
        assertTrue(verdict is ScheduleResponseClassifier.Verdict.EndpointProblem)
        assertTrue((verdict as ScheduleResponseClassifier.Verdict.EndpointProblem).detail.contains("404"))
    }

    @Test
    fun `500 判定为端点有问题`() {
        val verdict = ScheduleResponseClassifier.classify(500, null, "<html>Server Error</html>")
        assertTrue(verdict is ScheduleResponseClassifier.Verdict.EndpointProblem)
    }

    @Test
    fun `返回别的页面时带上诊断片段`() {
        val verdict = ScheduleResponseClassifier.classify(
            200,
            "text/html;charset=UTF-8",
            "<html><head><title>教学管理信息服务平台</title></head></html>",
        )
        assertTrue(verdict is ScheduleResponseClassifier.Verdict.EndpointProblem)
        val detail = (verdict as ScheduleResponseClassifier.Verdict.EndpointProblem).detail
        assertTrue(detail.contains("text/html"))
        assertTrue(detail.contains("教学管理信息服务平台"))
    }

    @Test
    fun `诊断片段不会太长`() {
        val long = "<html>" + "x".repeat(5000)
        val verdict = ScheduleResponseClassifier.classify(200, null, long)
        val detail = (verdict as ScheduleResponseClassifier.Verdict.EndpointProblem).detail
        assertTrue("诊断信息应该被截断，实际长度 ${detail.length}", detail.length < 200)
    }
}
