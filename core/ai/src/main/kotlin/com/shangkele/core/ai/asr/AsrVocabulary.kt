package com.shangkele.core.ai.asr

import kotlinx.serialization.json.Json

/**
 * 词表。SenseVoice 的 `tokens.json` 是一个 25055 项的字符串数组。
 *
 * 用不可变 `Array<String>` 而不是 List：解码时每帧都要按 id 取词，
 * 一个 90 分钟的课有几十万次调用，装箱开销不值得。
 */
class AsrVocabulary(private val tokens: Array<String>) {

    val size: Int get() = tokens.size

    operator fun get(id: Int): String? = tokens.getOrNull(id)

    /** 某个 id 是否属于控制标记（`<|zh|>`、`<|NEUTRAL|>` 这类）。 */
    fun isControlToken(id: Int): Boolean {
        val t = tokens.getOrNull(id) ?: return false
        return t.length >= 4 && t.startsWith("<|") && t.endsWith("|>")
    }

    companion object {
        fun of(tokens: List<String>): AsrVocabulary = AsrVocabulary(tokens.toTypedArray())

        /**
         * 解析资源里的 `tokens.json`。
         *
         * 用 `ignoreUnknownKeys` 之类都无所谓，因为这里就是纯字符串数组；
         * 但**长度必须校验** —— 词表比模型输出小的话，`getOrNull` 会静默返回 null，
         * 表现成「后半段识别不出字」，很难查。
         */
        fun parse(json: String, expectedSize: Int = SenseVoiceSpec.VOCAB_SIZE): AsrVocabulary {
            val list = Json.decodeFromString<List<String>>(json)
            require(list.size == expectedSize) {
                "词表长度 ${list.size} 与模型期望的 $expectedSize 不一致"
            }
            return of(list)
        }
    }
}
