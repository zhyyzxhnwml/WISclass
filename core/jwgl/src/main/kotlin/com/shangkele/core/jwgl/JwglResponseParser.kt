package com.shangkele.core.jwgl

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/** 教务系统零散响应的解析：公钥、错误提示。 */
object JwglResponseParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** `/jwglxt/xtgl/login_getPublicKey.html` → `{"modulus":"...","exponent":"AQAB"}` */
    fun parsePublicKey(raw: String): JwglPublicKey {
        val text = raw.trim()
        if (text.isEmpty() || text.startsWith("<")) throw JwglSessionExpiredException()

        val obj = try {
            json.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            throw JwglParseException("公钥接口返回内容无法解析：${e.message}")
        }

        val modulus = (obj["modulus"] as? JsonPrimitive)?.contentOrNull?.trim()
        val exponent = (obj["exponent"] as? JsonPrimitive)?.contentOrNull?.trim()
        if (modulus.isNullOrEmpty() || exponent.isNullOrEmpty()) {
            throw JwglParseException("公钥接口缺少 modulus 或 exponent")
        }
        return JwglPublicKey(modulus = modulus, exponent = exponent)
    }

    /** 从失败的登录页里抠出提示文案，取不到就给一个泛化文案。 */
    fun extractLoginError(html: String): String {
        val patterns = listOf(
            Regex("""<div[^>]*id\s*=\s*["'](?:tips|errorMsg|message)["'][^>]*>(.*?)</div>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)),
            Regex("""<span[^>]*class\s*=\s*["'][^"']*error[^"']*["'][^>]*>(.*?)</span>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)),
        )
        for (pattern in patterns) {
            val text = pattern.find(html)?.groupValues?.get(1)?.stripHtmlTags()
            if (!text.isNullOrBlank()) return text
        }
        return "账号或密码不正确"
    }

    private fun String.stripHtmlTags(): String =
        replace(Regex("<[^>]*>"), "").replace("&nbsp;", " ").trim()
}
