package com.shangkele.core.ai.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 用云端大模型把转写原文整理成结构化笔记。
 *
 * 三个刻意坚持的设计：
 *
 * 1. **强制 JSON 输出**，不让模型自由发挥。因为下游要用这些字段做复习、
 *    自测、考点权重，一大段自然语言没法消费。
 *
 * 2. **长录音走 map-reduce 分段**。一节课 45 分钟约 5000~8000 字，
 *    多数模型塞得下；但两节连堂就可能超。分段先各自压缩、再归并，
 *    比截断（丢后半节课）或硬塞（超上下文报错）都靠谱。
 *
 * 3. **Prompt 里明确禁止补充原文之外的内容**。课堂笔记最怕模型"帮你科普"，
 *    那会写进一堆老师根本没讲的东西，期末复习时无从分辨真假。
 */
@Singleton
class LlmSummaryEngine @Inject constructor(
    private val client: OpenAiCompatibleClient,
) {

    data class Output(
        val overview: String,
        val keyPoints: List<String>,
        val terms: List<String>,
        val confusions: List<String>,
        val todos: List<String>,
        val quiz: List<String>,
    )

    /**
     * @param onStage 阶段提示，用于长录音时告诉用户「正在处理第 3/7 段」
     */
    suspend fun summarize(
        transcript: String,
        courseName: String?,
        config: LlmConfig,
        apiKey: String,
        onStage: (String) -> Unit = {},
    ): Result<Output> {
        if (transcript.isBlank()) return Result.failure(IllegalArgumentException("转写内容是空的"))

        val chunks = chunkBySentence(transcript, MAX_CHUNK_CHARS)

        return if (chunks.size <= 1) {
            onStage("正在生成摘要…")
            callAndParse(config, apiKey, transcript, courseName)
        } else {
            // map：每段先压成「要点式短笔记」
            val partials = mutableListOf<String>()
            chunks.forEachIndexed { index, chunk ->
                onStage("正在处理第 ${index + 1}/${chunks.size} 段…")
                val part = client.chat(
                    baseUrl = config.baseUrl,
                    apiKey = apiKey,
                    model = config.model,
                    systemPrompt = CHUNK_SYSTEM_PROMPT,
                    userPrompt = "课程：${courseName ?: "未知"}\n\n第 ${index + 1} 段转写：\n$chunk",
                    maxTokens = 1024,
                ).getOrElse { return Result.failure(it) }
                partials += part.trim()
            }

            // reduce：把小笔记合成最终结构
            onStage("正在汇总…")
            val merged = partials.joinToString("\n\n---\n\n")
            callAndParse(config, apiKey, merged, courseName, alreadyCondensed = true)
        }
    }

    private suspend fun callAndParse(
        config: LlmConfig,
        apiKey: String,
        content: String,
        courseName: String?,
        alreadyCondensed: Boolean = false,
    ): Result<Output> {
        val userPrompt = buildString {
            append("课程：")
            append(courseName ?: "未知")
            append('\n')
            if (alreadyCondensed) {
                append("下面是这节课分段整理出的笔记，请合并成最终结果（去掉重复项）：\n")
            } else {
                append("下面是这节课的语音转写原文（可能有错别字）：\n")
            }
            append(content)
        }

        val raw = client.chat(
            baseUrl = config.baseUrl,
            apiKey = apiKey,
            model = config.model,
            systemPrompt = if (alreadyCondensed) MERGE_SYSTEM_PROMPT else SYSTEM_PROMPT,
            userPrompt = userPrompt,
            maxTokens = 2048,
        ).getOrElse { return Result.failure(it) }

        return runCatching { parse(raw) }
    }

    // ---- JSON 解析 ----

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    internal fun parse(raw: String): Output {
        val body = extractJsonObject(raw)
            ?: error("模型没有返回可解析的 JSON。原文开头：${raw.take(200)}")
        val root = json.parseToJsonElement(body).jsonObject

        fun strings(key: String): List<String> =
            root[key]?.jsonArray?.mapNotNull {
                it.jsonPrimitive.contentOrNull?.trim()?.takeIf { s -> s.isNotEmpty() }
            } ?: emptyList()

        return Output(
            overview = root["overview"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty(),
            keyPoints = strings("keyPoints"),
            terms = strings("terms"),
            confusions = strings("confusions"),
            todos = strings("todos"),
            quiz = strings("quiz"),
        )
    }

    /**
     * 从模型输出里抠出 JSON。实现在 [ModelJson]，作业抽取那边共用同一份。
     */
    internal fun extractJsonObject(raw: String): String? = ModelJson.objectBody(raw)

    /**
     * 按句子边界把长转写切开，避免把一句话劈成两半。
     *
     * 单块过短时不切（切了反而让模型丢失上下文）。
     */
    internal fun chunkBySentence(text: String, maxChars: Int): List<String> {
        if (text.length <= maxChars) return listOf(text)

        val sentences = SentenceBoundary.split(text)
        val chunks = mutableListOf<String>()
        val buffer = StringBuilder()
        for (sentence in sentences) {
            if (buffer.isNotEmpty() && buffer.length + sentence.length > maxChars) {
                chunks += buffer.toString()
                buffer.setLength(0)
            }
            buffer.append(sentence)
        }
        if (buffer.isNotEmpty()) chunks += buffer.toString()
        return chunks.ifEmpty { listOf(text) }
    }

    private companion object {
        const val MAX_CHUNK_CHARS = 6000

        val SYSTEM_PROMPT = """
            你是一名大学课程助教，负责把课堂语音转写整理成结构化笔记。

            必须遵守：
            1. 只依据给定原文，绝对不要补充原文没提到的知识，不要科普，不要举例。原文没讲的就是没讲。
            2. 转写可能有错别字，尤其是专业术语。按上下文推断正确写法，但不要臆造原文没有的概念。
            3. 全部用中文，每条尽量一句话，简洁。
            4. 只输出 JSON 对象，不要任何解释文字，不要 markdown 代码块。

            输出格式（严格照此结构，数组元素都是字符串）：
            {
              "overview": "一句话概括这节课讲了什么",
              "keyPoints": ["3 到 8 条课堂要点"],
              "terms": ["出现的专业术语，最多 10 个"],
              "confusions": ["老师反复解释、或学生容易搞错的点"],
              "todos": ["老师布置的作业或待办，要包含他说的时间要求原文"],
              "quiz": ["2 到 5 道自测题，每题格式为 Q: 问题\nA: 答案，答案必须是老师讲过的"]
            }
        """.trimIndent()

        val CHUNK_SYSTEM_PROMPT = """
            你是一名大学课程助教。这是长课堂录音的其中一段。
            请只针对这一段，提炼出要点、术语、易错点、作业、自测题。
            只依据原文，不要补充原文没有的内容。用中文，简洁。不要输出 JSON，用简短的分行文本即可。
        """.trimIndent()

        val MERGE_SYSTEM_PROMPT = """
            你是一名大学课程助教。你会收到同一节课多个分段各自整理的笔记。
            请合并成一份最终笔记：去掉重复、合并同类项、保留全部不同的信息点。
            只依据给定内容，不要补充新的知识。全部用中文。
            只输出 JSON 对象，不要任何解释文字，不要 markdown 代码块。

            输出格式（严格照此结构，数组元素都是字符串）：
            {
              "overview": "一句话概括这节课讲了什么",
              "keyPoints": ["3 到 8 条课堂要点"],
              "terms": ["专业术语，最多 10 个"],
              "confusions": ["老师反复解释、或学生容易搞错的点"],
              "todos": ["老师布置的作业或待办，包含时间要求原文"],
              "quiz": ["2 到 5 道自测题，格式为 Q: 问题\nA: 答案"]
            }
        """.trimIndent()
    }
}

/** 按中英文句末标点切句，标点归前一句。 */
internal object SentenceBoundary {

    private val ENDS = charArrayOf('。', '！', '？', '；', '\n', '.', '!', '?', ';')

    fun split(text: String): List<String> {
        val out = mutableListOf<String>()
        val buffer = StringBuilder()
        for (ch in text) {
            buffer.append(ch)
            if (ch in ENDS) {
                val piece = buffer.toString()
                if (piece.isNotBlank()) out += piece
                buffer.setLength(0)
            }
        }
        if (buffer.isNotBlank()) out += buffer.toString()
        return out
    }
}
