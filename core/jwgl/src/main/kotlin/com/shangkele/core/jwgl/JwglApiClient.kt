package com.shangkele.core.jwgl

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** `/jwglxt/xtgl/login_getPublicKey.html` 的返回。 */
data class JwglPublicKey(
    val modulus: String,
    val exponent: String,
)

/** 原生登录的结果。 */
sealed interface JwglLoginOutcome {
    data object Success : JwglLoginOutcome

    /** 账号密码错误，或被锁定 */
    data class Rejected(val reason: String) : JwglLoginOutcome

    /** 已触发验证码，必须回退到 WebView */
    data object CaptchaRequired : JwglLoginOutcome
}

/**
 * 教务系统 HTTP 客户端。
 *
 * 刻意**不用 OkHttp 的 CookieJar**：整个会话就是一个 `Cookie` 请求头字符串，
 * 这样从 WebView 拿到的会话可以直接注入，认证通道与抓取通道彻底解耦
 * （L1 WebView 认证 / L2 原生抓取，见 docs/03-教务系统对接.md §二）。
 *
 * 所有请求串行 + 限速。
 */
@Singleton
class JwglApiClient @Inject constructor(
    private val rateLimiter: JwglRateLimiter,
) {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        // 手动处理 302，才能判断登录是否成功
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    @Volatile
    var cookieHeader: String? = null
        private set

    fun setCookies(raw: String?) {
        cookieHeader = raw?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun clearCookies() {
        cookieHeader = null
    }

    /** GET 登录页，用于取 csrftoken。 */
    suspend fun fetchLoginPage(): String = withContext(Dispatchers.IO) {
        rateLimiter.await()
        val request = Request.Builder()
            .url(JwglEndpoints.LOGIN_PAGE)
            .header("User-Agent", USER_AGENT)
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            response.captureCookies()
            response.body?.string().orEmpty()
        }
    }

    /** GET RSA 公钥。 */
    suspend fun fetchPublicKey(): JwglPublicKey = withContext(Dispatchers.IO) {
        rateLimiter.await()
        val request = Request.Builder()
            .url(JwglEndpoints.PUBLIC_KEY)
            .header("User-Agent", USER_AGENT)
            .applyCookie()
            .get()
            .build()
        val text = client.newCall(request).execute().use { response ->
            response.captureCookies()
            response.body?.string().orEmpty()
        }
        JwglResponseParser.parsePublicKey(text)
    }

    /**
     * POST 登录。若该学校本轮需要验证码，返回 [JwglLoginOutcome.CaptchaRequired]，
     * 调用方必须回退到 WebView。
     */
    suspend fun login(
        username: String,
        encryptedPassword: String,
        csrfToken: String,
        timestampMs: Long = System.currentTimeMillis(),
    ): JwglLoginOutcome = withContext(Dispatchers.IO) {
        rateLimiter.await()
        val form = FormBody.Builder()
            .add("csrftoken", csrfToken)
            .add("yhm", username)
            .add("mm", encryptedPassword)
            .add("language", "zh_CN")
            .build()

        val request = Request.Builder()
            .url("${JwglEndpoints.LOGIN_PAGE}?time=$timestampMs")
            .header("User-Agent", USER_AGENT)
            .header("X-Requested-With", "XMLHttpRequest")
            .addHeader("Referer", JwglEndpoints.LOGIN_PAGE)
            .applyCookie()
            .post(form)
            .build()

        client.newCall(request).execute().use { response ->
            response.captureCookies()
            val code = response.code
            val body = response.body?.string().orEmpty()

            when {
                code == 301 || code == 302 -> JwglLoginOutcome.Success
                body.contains("kaptcha", ignoreCase = true) -> JwglLoginOutcome.CaptchaRequired
                body.contains("验证码") -> JwglLoginOutcome.CaptchaRequired
                body.contains("用户名") || body.contains("密码") -> {
                    JwglLoginOutcome.Rejected(JwglResponseParser.extractLoginError(body))
                }
                else -> JwglLoginOutcome.Rejected("登录失败（HTTP $code）")
            }
        }
    }

    /**
     * 抓取课表原始文本（JSON）。
     *
     * 正方 V9 在不同部署用过两个端点命名，逐个尝试；只有全都拿不到 JSON 时才报错。
     */
    suspend fun fetchScheduleRaw(
        xnm: String,
        xqm: String,
        gnmkdm: String = JwglEndpoints.GNMKDM_STUDENT_SCHEDULE,
        preferredEndpoint: String? = null,
    ): String = withContext(Dispatchers.IO) {
        val form = FormBody.Builder()
            .add("xnm", xnm)
            .add("xqm", xqm)
            .add("kzlx", "ck")
            .add("xsdm", "")
            .build()

        var lastMessage = "没有可用的课表接口"
        val deadline = System.currentTimeMillis() + 60_000

        // 实测过的地址排最前（来自 WebView 里官方页面自己发的请求，含本校正确的 gnmkdm）
        val endpoints = buildList {
            preferredEndpoint?.takeIf { it.isNotBlank() }?.let { add(it) }
            addAll(JwglEndpoints.SCHEDULE_ENDPOINTS)
        }
            // 截下来的是相对地址，必须补成绝对地址，否则 OkHttp 直接报没有 scheme
            .mapNotNull { JwglEndpoints.resolveUrl(it) }
            .distinct()

        for (endpoint in endpoints) {
            if (System.currentTimeMillis() > deadline) break
            rateLimiter.await()

            // 已带 gnmkdm 的原样用；没有才补默认值
            val target = JwglEndpoints.withGnmkdm(endpoint, gnmkdm)
            val gnmkdmForReferer = JwglEndpoints.gnmkdmOf(target) ?: gnmkdm

            val request = Request.Builder()
                .url(target)
                .header("User-Agent", USER_AGENT)
                .header("X-Requested-With", "XMLHttpRequest")
                .addHeader(
                    "Referer",
                    "${JwglEndpoints.BASE_URL}/jwglxt/kbcx/xskbcx_cxXskbcxIndex.html" +
                        "?gnmkdm=$gnmkdmForReferer&layout=default",
                )
                .applyCookie()
                .post(form)
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    response.captureCookies()
                    val body = response.body?.string().orEmpty()
                    val verdict = ScheduleResponseClassifier.classify(
                        status = response.code,
                        contentType = response.header("Content-Type"),
                        body = body,
                    )
                    when (verdict) {
                        is ScheduleResponseClassifier.Verdict.Json -> return@withContext body.trim()

                        is ScheduleResponseClassifier.Verdict.SessionExpired -> {
                            // 会话没了就没必要再试别的地址，立刻收手并如实上报，
                            // 让界面把用户导回登录页
                            throw JwglSessionExpiredException()
                        }

                        is ScheduleResponseClassifier.Verdict.EndpointProblem -> {
                            lastMessage = "端点 $endpoint：${verdict.detail}"
                        }
                    }
                }
            } catch (e: JwglSessionExpiredException) {
                throw e
            } catch (e: Exception) {
                lastMessage = "端点 $endpoint 请求失败：${e.message}"
            }
        }

        throw JwglEndpointException(lastMessage)
    }

    private fun Request.Builder.applyCookie(): Request.Builder {
        cookieHeader?.let { addHeader("Cookie", it) }
        return this
    }

    /** 收集 `Set-Cookie` 并合并进当前会话（只取 name=value，丢弃属性）。 */
    private fun Response.captureCookies() {
        val pairs = headers("Set-Cookie").mapNotNull { raw ->
            val pair = raw.substringBefore(';').trim()
            if (pair.contains('=')) pair else null
        }
        if (pairs.isEmpty()) return

        val merged = LinkedHashMap<String, String>()
        cookieHeader?.split(';')?.forEach { part ->
            val trimmed = part.trim()
            val name = trimmed.substringBefore('=', "")
            if (name.isNotEmpty()) merged[name] = trimmed
        }
        pairs.forEach { pair ->
            val name = pair.substringBefore('=')
            merged[name] = pair
        }
        cookieHeader = merged.values.joinToString("; ")
    }

    companion object {
        /** 正方对 UA 有检测，用移动端 Chrome 的标识。 */
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 15; ELI-AN00) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    }
}
