package com.shangkele.core.jwgl

/**
 * 正方教务 V9 的接口地址与功能模块码。
 *
 * **这里没有学校信息。** 域名由本机的 `school.properties` 在构建时注入
 * （见 `core/jwgl/build.gradle.kts`），仓库里只有一份空模板 ——
 * 仓库是公开的，「哪所学校」不该成为可被检索到的公开信息。
 *
 * 没配置时 host 是 `*.invalid`（IANA 保留 TLD，保证解析不到），
 * 所以「连不上」是明确的失败，而不是请求悄悄发到了别的地方。
 *
 * 注意 `xskbcx_*.html` 这类路径、`N2151` 这类模块码是**正方 V9 的通用值**，
 * 所有用正方的学校都一样，不属于学校标识，留着无妨（见 docs/03）。
 */
object JwglEndpoints {

    /** 教务系统根地址。多数部署仍是 http，见 app 的 network_security_config 生成逻辑。 */
    val BASE_URL = "http://${BuildConfig.JWGL_HOST}"

    /** 统一身份认证（走 WebView 时由官方页面自己跳转，这里仅记录）。 */
    val CAS_BASE_URL = "https://${BuildConfig.CAS_HOST}"

    val LOGIN_PAGE = "$BASE_URL/jwglxt/xtgl/login_slogin.html"
    val PUBLIC_KEY = "$BASE_URL/jwglxt/xtgl/login_getPublicKey.html"

    /** 功能模块码：学生个人课表。各校不同，所以实际用时以「从页面里刮到的」为准。 */
    const val GNMKDM_STUDENT_SCHEDULE = "N2151"

    /**
     * 课表接口候选。
     *
     * 正方 V9 在不同学校/版本上用过两个命名，依序尝试，谁先返回 JSON 用谁。
     * 全部失败会抛出 [JwglEndpointException]。
     */
    val SCHEDULE_ENDPOINTS = listOf(
        "$BASE_URL/jwglxt/kbcx/xskbcx_cxXsgrkb.html",
        "$BASE_URL/jwglxt/kbcx/xskbcx_cxXsKb.html",
    )

    /** 学期码：第一学期 / 第二学期 / 短学期。 */
    val SEMESTER_CODES = mapOf("3" to "第一学期", "12" to "第二学期", "16" to "短学期")

    /**
     * 这个 host 是不是本校域名。
     *
     * 带着教务会话 Cookie 的 WebView 不该满世界跑，站外链接一律丢给系统浏览器。
     *
     * **根域名留空时返回 true（不限制导航）**：那是「没配置 school.properties」的情况，
     * 返回 false 会让 WebView 连登录页都打不开（每次跳转都被丢到外部浏览器），
     * 表现是「导入功能彻底不可用」—— 比放开导航更糟，而且看不出原因。
     */
    fun isSchoolHost(host: String): Boolean {
        val suffix = BuildConfig.SCHOOL_DOMAIN_SUFFIX
        if (suffix.isEmpty()) return true
        return host == suffix || host.endsWith(".$suffix")
    }

    /**
     * 把相对地址补成绝对地址。
     *
     * WebView 里截到的地址是相对的（如 `/jwglxt/kbcx/xskbcx_cxXsgrkb.html?...`），
     * 直接丢给 OkHttp 会报 `Expected URL scheme 'http' or 'https'`。
     */
    fun resolveUrl(raw: String?): String? {
        val url = raw?.trim().orEmpty()
        if (url.isEmpty()) return null
        return when {
            url.startsWith("http://", ignoreCase = true) -> url
            url.startsWith("https://", ignoreCase = true) -> url
            url.startsWith("//") -> "http:$url"
            url.startsWith("/") -> BASE_URL + url
            else -> "$BASE_URL/$url"
        }
    }

    /** 取地址里的 `gnmkdm` 模块码。各校不一样，所以以页面里刮到的为准，不硬编码。 */
    fun gnmkdmOf(url: String?): String? {
        val value = url?.substringAfter("gnmkdm=", "")?.substringBefore('&')?.trim()
        return value?.takeIf { it.isNotEmpty() }
    }

    /**
     * 补全 `gnmkdm` 参数。已经带了就原样返回 —— 不能无脑追加，
     * 否则会拼出 `?gnmkdm=<页面上刮到的>&gnmkdm=N2151` 这种请求。
     */
    fun withGnmkdm(url: String, fallback: String = GNMKDM_STUDENT_SCHEDULE): String {
        if (url.contains("gnmkdm=")) return url
        val separator = if (url.contains('?')) '&' else '?'
        return "$url$separator" + "gnmkdm=$fallback"
    }
}

/** 教务对接相关异常。 */
sealed class JwglException(message: String) : Exception(message)

/** 会话失效（Cookie 过期或未登录），需要回到 WebView 重新认证。 */
class JwglSessionExpiredException :
    JwglException("教务系统会话已失效，需要重新登录")

/** 返回内容无法解析为预期结构。 */
class JwglParseException(message: String) : JwglException(message)

/** 所有候选端点都不可用。 */
class JwglEndpointException(message: String) : JwglException(message)

/** 登录被拒绝（账号密码错误 / 触发验证码 / 被锁定）。 */
class JwglLoginException(message: String) : JwglException(message)
