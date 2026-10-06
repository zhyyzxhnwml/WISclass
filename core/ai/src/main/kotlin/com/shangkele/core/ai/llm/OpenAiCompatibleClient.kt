package com.shangkele.core.ai.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OpenAI 兼容接口的极简客户端。
 *
 * **只做两件事**：拉模型列表、发一次对话。不做流式、不做函数调用、
 * 不引入任何 SDK —— 聚合平台的接口就是 `POST /chat/completions` 这一条，
 * 引 SDK 只会带来版本与体积负担。
 *
 * 超时给得比较宽（读 3 分钟）：免费档的模型排队很常见，
 * 超时太短会让用户看到「失败」，其实再等十几秒就出来了。
 */
@Singleton
class OpenAiCompatibleClient @Inject constructor() {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /** 拉取可用模型。设置页用它给用户选，避免把模型名写死在代码里。 */
    suspend fun listModels(baseUrl: String, apiKey: String): Result<List<String>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url(baseUrl.trimEnd('/') + "/models")
                    .header("Authorization", "Bearer $apiKey")
                    .get()
                    .build()
                http.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    if (!response.isSuccessful) error(describeHttpError(response.code, text))
                    val root = json.parseToJsonElement(text).jsonObject
                    root["data"]?.jsonArray
                        ?.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull }
                        ?.sorted()
                        ?: emptyList()
                }
            }
        }

    /** 发一次非流式对话，返回正文。 */
    suspend fun chat(
        baseUrl: String,
        apiKey: String,
        model: String,
        systemPrompt: String,
        userPrompt: String,
        maxTokens: Int = 2048,
        temperature: Double = 0.2,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            send(
                baseUrl = baseUrl,
                apiKey = apiKey,
                payload = buildJsonObject {
                    put("model", model)
                    put("temperature", temperature)
                    put("max_tokens", maxTokens)
                    putJsonArray("messages") {
                        addJsonObject {
                            put("role", "system")
                            put("content", systemPrompt)
                        }
                        addJsonObject {
                            put("role", "user")
                            put("content", userPrompt)
                        }
                    }
                },
            )
        }
    }

    /**
     * 带一张图发一次对话（视觉模型）。
     *
     * 与 [chat] 唯一的区别是 `content` 由字符串变成数组：文字片段 + 一个 `image_url`。
     * 图片用 data URL（`data:image/jpeg;base64,...`）内联，不用先上传 ——
     * 少一次上传就少一个失败点。
     *
     * **图必须先压过**：一张 1200 万像素的照片 base64 之后十几 MB，多数接口直接 413；
     * 压到长边 1024 / JPEG 85 之后约 150~250KB。压缩在调用方完成（见 NotePhotoStore）。
     *
     * 模型不支持看图时，服务端通常回 400 并说明「不支持图片输入」——
     * 那条信息会原样透出来，不会静默变成一个空结果。
     */
    suspend fun chatWithImage(
        baseUrl: String,
        apiKey: String,
        model: String,
        systemPrompt: String,
        userPrompt: String,
        imageBase64: String,
        imageMediaType: String = "image/jpeg",
        maxTokens: Int = 2048,
        temperature: Double = 0.2,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            send(
                baseUrl = baseUrl,
                apiKey = apiKey,
                payload = buildJsonObject {
                    put("model", model)
                    put("temperature", temperature)
                    put("max_tokens", maxTokens)
                    putJsonArray("messages") {
                        addJsonObject {
                            put("role", "system")
                            put("content", systemPrompt)
                        }
                        addJsonObject {
                            put("role", "user")
                            putJsonArray("content") {
                                addJsonObject {
                                    put("type", "text")
                                    put("text", userPrompt)
                                }
                                addJsonObject {
                                    put("type", "image_url")
                                    putJsonObject("image_url") {
                                        put("url", "data:$imageMediaType;base64,$imageBase64")
                                    }
                                }
                            }
                        }
                    }
                },
            )
        }
    }

    /**
     * 真正发请求、抠出正文。
     *
     * 两个入口共用一份，免得错误处理（401/404/429 的翻译、空内容判定）
     * 写两遍 —— 那种重复迟早会只改一边。
     */
    private fun send(baseUrl: String, apiKey: String, payload: JsonObject): String {
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) error(describeHttpError(response.code, text))

            val root = json.parseToJsonElement(text).jsonObject
            val content = root["choices"]?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("message")?.jsonObject?.get("content")
                ?.jsonPrimitive?.contentOrNull

            if (content.isNullOrBlank()) {
                error("模型返回了空内容。原文：${text.take(300)}")
            }
            return content
        }
    }

    /**
     * 把 HTTP 错误码翻译成用户能看懂、能行动的话。
     *
     * 直接把 `{"error":{"message":"..."}}` 丢给用户没有意义 ——
     * 他不知道 401 意味着要去哪一页做什么。
     */
    private fun describeHttpError(code: Int, body: String): String {
        val detail = runCatching {
            json.parseToJsonElement(body).jsonObject["error"]?.jsonObject
                ?.get("message")?.jsonPrimitive?.contentOrNull
        }.getOrNull()

        val hint = when (code) {
            401 -> "API Key 无效或已失效。去平台控制台复制一个新的密钥。"
            403 -> "这个 Key 没有调用该模型的权限。"
            404 -> "地址或模型名不对。Base URL 应该填到 /v1 为止，不要带 /chat/completions。"
            429 -> "请求太频繁。免费档通常有每分钟请求数限制，等一会儿再试。"
            in 500..599 -> "模型服务端出错，通常等几分钟再试即可。"
            else -> "请求失败（HTTP $code）。"
        }
        return if (detail.isNullOrBlank()) hint else "$hint\n服务端说：$detail"
    }

    /** 一次性可用性检查，供设置页的「测试连接」用。 */
    suspend fun testConnection(baseUrl: String, apiKey: String, model: String): Result<String> =
        chat(
            baseUrl = baseUrl,
            apiKey = apiKey,
            model = model,
            systemPrompt = "你是一个测试助手。",
            userPrompt = "回复两个字：正常",
            maxTokens = 32,
            temperature = 0.0,
        ).map { it.trim() }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
