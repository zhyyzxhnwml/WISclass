package com.shangkele.core.jwgl

/**
 * 登录页解析。
 *
 * 从 `/jwglxt/xtgl/login_slogin.html` 的 HTML 里取出提交登录所必需的隐藏字段。
 * 纯字符串处理，可在 JVM 单测里直接跑。
 *
 * 真实页面里这些字段既可能写成 `<input>`，也可能写在 `<script>` 里，
 * 所以两种形态都要兜。
 */
object JwglLoginPageParser {

    data class LoginPageInfo(
        /** CSRF 令牌，提交登录时必须原样带回 */
        val csrfToken: String?,
        /** mmsfjm = 1 表示密码需要前端 RSA 加密 */
        val passwordEncrypted: Boolean,
        /** yzcskz：连续失败几次后开始要求验证码 */
        val captchaTriggerCount: Int?,
        val schoolCode: String?,
        /** 页面是否已出现验证码组件；出现则必须回退到 WebView 认证 */
        val hasCaptcha: Boolean,
    ) {
        val canAttemptNativeLogin: Boolean
            get() = !hasCaptcha && !csrfToken.isNullOrBlank()
    }

    private val INPUT_TAG = Regex("""<input\b[^>]*>""", RegexOption.IGNORE_CASE)

    fun parse(html: String): LoginPageInfo {
        val inputs = INPUT_TAG.findAll(html).map { it.value }.toList()

        return LoginPageInfo(
            csrfToken = valueOf(html, inputs, "csrftoken"),
            passwordEncrypted = valueOf(html, inputs, "mmsfjm") == "1",
            captchaTriggerCount = valueOf(html, inputs, "yzcskz")?.toIntOrNull(),
            schoolCode = valueOf(html, inputs, "xxdm"),
            hasCaptcha = hasCaptcha(html),
        )
    }

    private fun hasCaptcha(html: String): Boolean {
        val lower = html.lowercase()
        return lower.contains("kaptcha") || lower.contains("id=\"yzm\"") || lower.contains("验证码")
    }

    /**
     * 取字段值：先按 `<input id="name" ... value="...">` 找，
     * 再退化为脚本里的 `name = '...'` / `name: '...'`。
     */
    private fun valueOf(html: String, inputs: List<String>, name: String): String? {
        val byId = Regex("""\bid\s*=\s*["']""" + Regex.escape(name) + """["']""", RegexOption.IGNORE_CASE)
        inputs.firstOrNull { byId.containsMatchIn(it) }
            ?.let { tag -> attributeOf(tag, "value") }
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        val script = Regex(
            """\b""" + Regex.escape(name) + """\s*[:=]\s*["']([^"']*)["']""",
            RegexOption.IGNORE_CASE,
        )
        return script.find(html)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
    }

    private fun attributeOf(tag: String, attribute: String): String? {
        val regex = Regex("""\b$attribute\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)
        return regex.find(tag)?.groupValues?.get(1)
    }
}
