package com.shangkele.core.ai.llm

/**
 * 从模型输出里抠出 JSON 对象。
 *
 * 即使 prompt 明写「只输出 JSON」，模型仍经常套一层 ```json 代码块，
 * 或者在前后加一句「好的，以下是结果：」。所以按第一个 `{` 到最后一个 `}`
 * 截取，而不是假设整个响应就是 JSON。
 *
 * 抽成共用函数是因为摘要和作业抽取都要用 —— 两边各写一份的话，
 * 迟早只改一边（比如某天发现要处理 ` ```jsonc `，另一处就漏了）。
 */
internal object ModelJson {

    fun objectBody(raw: String): String? {
        val cleaned = raw.replace("```json", "").replace("```", "")
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return cleaned.substring(start, end + 1)
    }
}
