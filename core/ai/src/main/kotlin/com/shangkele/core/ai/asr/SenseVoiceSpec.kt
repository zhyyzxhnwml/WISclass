package com.shangkele.core.ai.asr

/**
 * SenseVoice ONNX 模型的固定契约。
 *
 * 这些值不是从文档抄的，是**拿真模型跑出来验证过的**：
 * ```
 * 输入  speech          float32 [batch, T, 560]
 *       speech_lengths  int32   [batch]
 *       language        int32   [batch]
 *       textnorm        int32   [batch]
 * 输出  ctc_logits      float32 [batch, T', 25055]
 *       encoder_out_lens int32   [batch]
 * ```
 */
object SenseVoiceSpec {

    const val INPUT_SPEECH = "speech"
    const val INPUT_SPEECH_LENGTHS = "speech_lengths"
    const val INPUT_LANGUAGE = "language"
    const val INPUT_TEXTNORM = "textnorm"
    const val OUTPUT_CTC_LOGITS = "ctc_logits"
    const val OUTPUT_ENCODER_LENS = "encoder_out_lens"

    /** 80 mel × LFR 7 帧 */
    const val FEATURE_DIM = 560

    /** 词表大小，等于 tokens.json 的条目数 */
    const val VOCAB_SIZE = 25055

    /**
     * CTC blank 的下标。
     *
     * 实测确认：tokens.json 的第 0 项是 `<unk>` 而不是 `<blank>`，
     * 说明 blank 不在词表里、独占 id 0，词表项直接按 id 对应。
     */
    const val BLANK_ID = 0

    const val LFR_M = 7
    const val LFR_N = 6

    /**
     * 语言 id。**填 0（自动）即可**：实测中英文都能正确自动判别，
     * 不需要先做语种检测。
     */
    object Language {
        const val AUTO = 0
        const val ZH = 3
        const val EN = 4
        const val YUE = 7
        const val JA = 11
        const val KO = 12
        const val NO_SPEECH = 13
    }

    /**
     * 文本规整 id。
     *
     * - [WITH_ITN] = 14：**带标点、数字转阿拉伯数字**（「早上9点至下午5点。」）
     * - [WITHOUT_ITN] = 15：纯汉字数字、无标点（「早上九点至下午五点」）
     *
     * 选 [WITH_ITN]：标点对后续分段、摘要、作业抽取都至关重要，
     * 没有标点的转写基本上不可读。
     */
    object TextNorm {
        const val WITH_ITN = 14
        const val WITHOUT_ITN = 15
    }

    /** 模型输出开头的一串控制标记，如 `<|zh|><|NEUTRAL|><|Speech|><|withitn|>`。 */
    private val SPECIAL_TOKEN = Regex("<\\|[^|]*\\|>")

    /** 句首标记（`▁`）在 SentencePiece 里代表空格。 */
    private const val WORD_PREFIX = '\u2581'

    fun stripSpecialTokens(text: String): String = SPECIAL_TOKEN.replace(text, "")

    fun normalizeText(raw: String): String =
        stripSpecialTokens(raw).replace(WORD_PREFIX, ' ').trim()
}
