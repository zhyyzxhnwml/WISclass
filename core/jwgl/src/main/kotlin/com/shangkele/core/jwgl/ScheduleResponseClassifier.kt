package com.shangkele.core.jwgl

/**
 * 判断课表接口的一次响应到底属于哪种情况。
 *
 * 抽成纯函数是因为这里踩过坑：**会话过期时正方返回的是 302 重定向，响应体是空的**。
 * 早先只检查响应体里有没有 `login_slogin` 字样，于是空响应被误判成「端点不对」，
 * 既给了用户错误的提示，也没清掉已经失效的会话，下次刷新还会再错一遍。
 */
internal object ScheduleResponseClassifier {

    sealed interface Verdict {
        /** 拿到了课表 JSON */
        data object Json : Verdict

        /** 会话失效，需要重新登录 */
        data object SessionExpired : Verdict

        /** 这个地址本身有问题（不存在 / 服务端错误 / 返回别的页面） */
        data class EndpointProblem(val detail: String) : Verdict
    }

    private const val SNIPPET_LENGTH = 80

    fun classify(status: Int, contentType: String?, body: String): Verdict {
        val trimmed = body.trim()

        if (trimmed.startsWith("{") || trimmed.startsWith("[")) return Verdict.Json

        // 重定向到登录页是最常见的过期表现，且响应体为空
        if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
            return Verdict.SessionExpired
        }

        if (trimmed.isEmpty()) {
            // 2xx/3xx 却没有任何内容，实际就是被静默重定向了；
            // 4xx/5xx 空响应则更像是地址不对
            return if (status < 400) {
                Verdict.SessionExpired
            } else {
                Verdict.EndpointProblem("HTTP $status，响应为空")
            }
        }

        if (trimmed.contains("login_slogin") ||
            trimmed.contains("用户登录") ||
            trimmed.contains("请先登录")
        ) {
            return Verdict.SessionExpired
        }

        val snippet = trimmed.take(SNIPPET_LENGTH).replace('\n', ' ').replace('\r', ' ')
        val type = contentType?.substringBefore(';')?.trim().orEmpty()
        return Verdict.EndpointProblem(
            "HTTP $status，Content-Type=$type，开头：$snippet",
        )
    }
}
