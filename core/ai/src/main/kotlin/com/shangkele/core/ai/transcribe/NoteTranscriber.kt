package com.shangkele.core.ai.transcribe

import com.shangkele.core.ai.audio.AacAudioDecoder
import com.shangkele.core.ai.audio.AudioQuality
import com.shangkele.core.ai.engine.SenseVoiceEngine
import com.shangkele.core.ai.llm.LlmConfigStore
import com.shangkele.core.ai.llm.LlmCredentialStore
import com.shangkele.core.ai.llm.LlmSummaryEngine
import com.shangkele.core.ai.model.SenseVoiceModelManager
import com.shangkele.core.database.dao.NoteDao
import com.shangkele.core.database.entity.SummaryEntity
import com.shangkele.core.database.entity.TranscriptSegmentEntity
import com.shangkele.core.model.NoteStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 把「一条录音笔记」变成「带时间戳的转写 + 结构化笔记」。
 *
 * 流水线：
 * ```
 *   读笔记 → REFINING → 解码音频
 *          → 录音体检（没人声就直接停，别出幻觉）
 *          → 分段转写（用完立刻释放模型，500MB 不能常驻）
 *          → 切句 + 机械抽取摘要 → 落库（转写片段 + 摘要）
 *          → DONE
 * ```
 *
 * 两个刻意的设计：
 *
 * 1. **体检放在转写之前**。SenseVoice 在近乎无声的输入上会**产生幻觉**——
 *    吐出一段流畅但完全无关的文字，而不是报错或返回空。用户只会觉得
 *    「识别太差」，但真正的问题是麦克风没收到声音。所以先判断有没有人声。
 *
 * 2. **模型用完立刻 close()**：引擎常驻内存约 500MB，荣耀 200 的 MagicOS
 *    在内存压力下会把持有大内存的进程直接清掉，留着不放只会让 App 后台被杀。
 */
@Singleton
class NoteTranscriber @Inject constructor(
    private val modelManager: SenseVoiceModelManager,
    private val noteDao: NoteDao,
    private val llmConfigStore: LlmConfigStore,
    private val credentialStore: LlmCredentialStore,
    private val llmSummaryEngine: LlmSummaryEngine,
) {

    sealed interface Outcome {
        data class Success(
            val text: String,
            val segmentCount: Int,
            val quality: AudioQuality.Report,
            /** 摘要来源：模型名（LLM 档）或模板标识。用于界面提示与排查。 */
            val summarySource: String = "",
        ) : Outcome

        data class Failed(val message: String) : Outcome
    }

    fun isModelReady(): Boolean = modelManager.isAsrModelReady()

    fun downloadedBytes(): Long = modelManager.downloadedBytes()

    suspend fun downloadModel(onProgress: (Long, Long) -> Unit): Result<Unit> =
        modelManager.ensureAsrModel(onProgress).map { }

    suspend fun transcribe(
        noteId: Long,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
        /** 摘要阶段的文案提示（分段处理时用） */
        onStage: (String) -> Unit = {},
    ): Outcome = withContext(Dispatchers.IO) {
        val note = noteDao.getById(noteId)
            ?: return@withContext Outcome.Failed("找不到这条笔记")

        val path = note.audioPath
        if (path.isNullOrBlank()) return@withContext Outcome.Failed("这条笔记没有录音文件")

        val audioFile = File(path)
        if (!audioFile.isFile || audioFile.length() == 0L) {
            return@withContext Outcome.Failed("录音文件已丢失或为空")
        }
        if (!modelManager.isAsrModelReady()) {
            return@withContext Outcome.Failed("语音模型还没下载完，请先下载")
        }

        noteDao.updateStatus(noteId, NoteStatus.REFINING.name)

        val samples = runCatching { AacAudioDecoder.decodeToMonoFloat(audioFile) }
            .getOrElse { error ->
                noteDao.updateStatus(noteId, NoteStatus.FAILED.name)
                return@withContext Outcome.Failed("解码录音失败：${error.message ?: "未知原因"}")
            }
        if (samples.isEmpty()) {
            noteDao.updateStatus(noteId, NoteStatus.FAILED.name)
            return@withContext Outcome.Failed("录音解出来是空的")
        }

        // ---- 体检：没人声就不要送模型 ----
        val quality = AudioQuality.inspect(samples)
        if (!quality.hasUsableSpeech) {
            noteDao.updateStatus(noteId, NoteStatus.FAILED.name)
            return@withContext Outcome.Failed(quality.advice)
        }

        val cmvn = modelManager.loadCmvn()
        val vocabulary = modelManager.loadVocabulary()

        val chunks = runCatching {
            SenseVoiceEngine(modelManager.modelFile(), cmvn, vocabulary).use { engine ->
                engine.transcribeChunks(samples, onProgress = onProgress)
            }
        }.getOrElse { error ->
            noteDao.updateStatus(noteId, NoteStatus.FAILED.name)
            return@withContext Outcome.Failed("转写失败：${error.message ?: "模型推理异常"}")
        }

        if (chunks.isEmpty()) {
            noteDao.updateStatus(noteId, NoteStatus.FAILED.name)
            return@withContext Outcome.Failed("没有识别出任何内容")
        }

        // 28 秒粒度的分段再切到句子级，时间按字数占比分摊
        val sentences = chunks
            .flatMap { chunk -> SentenceSplitter.split(chunk.text, chunk.startMs, chunk.endMs) }
            .map { ExtractiveSummarizer.Sentence(it.text, it.startMs, it.endMs) }

        noteDao.deleteSegments(noteId)
        noteDao.insertSegments(
            sentences.mapIndexed { index, sentence ->
                TranscriptSegmentEntity(
                    noteId = noteId,
                    index = index,
                    startMs = sentence.startMs,
                    endMs = sentence.endMs,
                    text = sentence.text,
                    correctedText = null,
                    isCorrected = false,
                    // 强调度留给后续的教师话语分析，这里先给中性值
                    emphasisScore = 0.5f,
                )
            },
        )

        val fullText = sentences.joinToString("") { it.text }

        // ---- 摘要：配了云端模型就用它，否则退回机械抽取 ----
        val (entity, source) = buildSummary(note, noteId, fullText, sentences, quality, onStage)
        noteDao.upsertSummary(entity)

        noteDao.update(note.copy(transcriptText = fullText, status = NoteStatus.DONE.name))

        Outcome.Success(fullText, sentences.size, quality, source)
    }

    /**
     * 生成摘要并决定用哪一档。
     *
     * 顺序是 `LLM → 机械抽取`，和设计里的三档降级一致
     * （第三档「外部」在配置意义上等价于 LLM 档）。
     *
     * **LLM 失败不能连带整条转写失败**：用户至少应该拿到文字。
     * 所以这里吞掉异常，退回机械抽取，并把失败原因写进 overview ——
     * 静默降级会让用户以为模型质量差，说清楚才知道是 Key 过期了还是网络断了。
     */
    private suspend fun buildSummary(
        note: com.shangkele.core.database.entity.NoteEntity,
        noteId: Long,
        fullText: String,
        sentences: List<ExtractiveSummarizer.Sentence>,
        quality: AudioQuality.Report,
        onStage: (String) -> Unit,
    ): Pair<SummaryEntity, String> {
        val config = llmConfigStore.load()
        val apiKey = credentialStore.apiKey()
        val now = System.currentTimeMillis()

        if (config.isUsable && !apiKey.isNullOrBlank()) {
            val outcome = llmSummaryEngine.summarize(
                transcript = fullText,
                // 标题形如「高等数学 · 第 4 周」，取课程名部分给模型当上下文
                courseName = note.title?.substringBefore(" ·"),
                config = config,
                apiKey = apiKey,
                onStage = onStage,
            )
            outcome.getOrNull()?.let { out ->
                return SummaryEntity(
                    noteId = noteId,
                    modelName = config.model,
                    overview = buildString {
                        append(out.overview)
                        append("\n录音质量：")
                        append(quality.advice)
                    },
                    keyPointsJson = Json.encodeToString(out.keyPoints),
                    termsJson = Json.encodeToString(out.terms),
                    confusionsJson = Json.encodeToString(out.confusions),
                    todosJson = Json.encodeToString(out.todos),
                    quizJson = Json.encodeToString(out.quiz),
                    tier = TIER_LLM,
                    createdAt = now,
                ) to config.model
            }

            val reason = outcome.exceptionOrNull()?.message ?: "未知原因"
            return templateSummary(noteId, sentences, quality, now, llmFailure = reason) to TIER_TEMPLATE
        }

        val reason = when {
            !config.enabled -> "未开启 AI 摘要（设置 → AI 摘要）"
            config.model.isBlank() -> "还没有选择模型（设置 → AI 摘要）"
            apiKey.isNullOrBlank() -> "还没有填 API Key（设置 → AI 摘要）"
            else -> "云端模型不可用"
        }
        return templateSummary(noteId, sentences, quality, now, llmFailure = reason) to TIER_TEMPLATE
    }

    private fun templateSummary(
        noteId: Long,
        sentences: List<ExtractiveSummarizer.Sentence>,
        quality: AudioQuality.Report,
        now: Long,
        llmFailure: String?,
    ): SummaryEntity {
        val summary = ExtractiveSummarizer.summarize(sentences, 0L)
        return SummaryEntity(
            noteId = noteId,
            modelName = TEMPLATE_MODEL_NAME,
            overview = buildString {
                append(summary.overview)
                append("\n录音质量：")
                append(quality.advice)
                if (!llmFailure.isNullOrBlank()) {
                    append("\n未使用 AI 摘要：")
                    append(llmFailure)
                }
            },
            keyPointsJson = Json.encodeToString(summary.keyPoints),
            termsJson = Json.encodeToString(summary.terms),
            confusionsJson = "[]",
            todosJson = "[]",
            quizJson = "[]",
            tier = TIER_TEMPLATE,
            createdAt = now,
        )
    }

    companion object {
        /** 机械抽取档。云端模型不可用时的兜底。 */
        const val TIER_TEMPLATE = "TEMPLATE"

        /** 云端大模型档。与 `SummaryTier.LLM` / `SummaryTier.EXTERNAL` 对应。 */
        const val TIER_LLM = "LLM"

        /**
         * 不能写成「未接模型」—— 那会被读成「模型没下载」。
         * 实际情况是这块功能还没开发，用词必须指向"代码"而不是"文件"。
         */
        const val TEMPLATE_MODEL_NAME = "规则抽取（未使用 AI 模型）"
    }
}
