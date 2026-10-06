package com.shangkele.core.ai.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 从「一张板书照片」或「一段课堂转写」里找出**老师布置的事情**。
 *
 * 只管抽，不管时间换算：产出的是 [Draft.dueRawText]（老师原话），
 * 换算成日期交给 `HomeworkDueParser` —— 那是纯函数、有单测，
 * 而模型每次给的措辞都不一样，让模型自己算日期是这次最容易踩的坑。
 *
 * 温度固定 0.0：这是抽取任务，要的是稳定复现，不是创造力。
 */
@Singleton
class HomeworkExtractor @Inject constructor(
    private val client: OpenAiCompatibleClient,
) {

    /** 抽出来的一条待办。日期还没解析成 epochDay。 */
    data class Draft(
        val title: String,
        /** 老师原话里的时间说法，没有则为 null。 */
        val dueRawText: String?,
        val confidence: Float,
    )

    /** 从一张照片（base64）里读。模型必须支持看图，否则服务端会明确报错。 */
    suspend fun fromImage(
        imageBase64: String,
        config: LlmConfig,
        apiKey: String,
    ): Result<List<Draft>> =
        client.chatWithImage(
            baseUrl = config.baseUrl,
            apiKey = apiKey,
            model = config.model,
            systemPrompt = SYSTEM_PROMPT,
            userPrompt = IMAGE_USER_PROMPT,
            imageBase64 = imageBase64,
            maxTokens = 1024,
            temperature = 0.0,
        ).mapCatching { parse(it) }

    /** 从一段转写里读。 */
    suspend fun fromTranscript(
        transcript: String,
        config: LlmConfig,
        apiKey: String,
    ): Result<List<Draft>> {
        if (transcript.isBlank()) return Result.success(emptyList())
        return client.chat(
            baseUrl = config.baseUrl,
            apiKey = apiKey,
            model = config.model,
            systemPrompt = SYSTEM_PROMPT,
            userPrompt = "下面是课堂语音转写（可能有错别字）：\n$transcript",
            maxTokens = 1024,
            temperature = 0.0,
        ).mapCatching { parse(it) }
    }

    // ---- 解析 ----

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 模型答不出格式时返回空列表，而不是抛异常 —— 这只是一条附加信息。 */
    internal fun parse(raw: String): List<Draft> {
        val body = ModelJson.objectBody(raw) ?: return emptyList()
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return emptyList()
        val todos = root["todos"]?.jsonArray ?: return emptyList()

        return todos.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null

            val title = obj["title"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            // 没有标题的丢掉：一条不知道要做什么的待办对用户没有任何价值
            if (title.isEmpty()) return@mapNotNull null

            Draft(
                title = title,
                dueRawText = obj["dueRawText"]?.jsonPrimitive?.contentOrNull
                    ?.trim()
                    // 模型经常把「没有时间」写成字符串 "null"，而不是 JSON null
                    ?.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) },
                confidence = obj["confidence"]?.jsonPrimitive?.contentOrNull
                    ?.toFloatOrNull()
                    ?.coerceIn(0f, 1f)
                    ?: DEFAULT_CONFIDENCE,
            )
        }
    }

    private companion object {
        const val DEFAULT_CONFIDENCE = 0.6f

        val SYSTEM_PROMPT = """
            你负责从课堂材料里找出「老师布置的事情」。

            必须遵守：
            1. 只找**明确交代要做的**事：作业、预习、复习、交报告、下次课要带的东西。
            2. 讲课内容、知识点、例题、考试范围都不是待办。
            3. **没有布置任何事就返回空数组。** 不要为了凑数编一条出来 ——
               用户会因此去做一件根本不存在的事。
            4. dueRawText 必须是**材料里出现的那个时间说法的原话**，例如「下周三前」「10月20日」。
               不要换算成日期，不要推测。材料里没写时间就填 null。
            5. title 用一句话说清「要做什么」，不要带时间。
            6. 只输出 JSON 对象，不要解释文字，不要 markdown 代码块。

            输出格式：
            {"todos":[{"title":"...","dueRawText":"下周三前 或 null","confidence":0.9}]}
        """.trimIndent()

        val IMAGE_USER_PROMPT = """
            这是一张课堂板书（或 PPT）的照片。请找出上面写的作业、待办，
            以及上面**出现的时间说法的原话**（例如「下周三前」「10月20日」）。
            什么都没有就返回空数组。
        """.trimIndent()
    }
}
