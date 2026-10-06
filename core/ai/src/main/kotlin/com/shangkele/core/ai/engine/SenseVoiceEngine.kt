package com.shangkele.core.ai.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.shangkele.core.ai.asr.AsrVocabulary
import com.shangkele.core.ai.asr.AudioChunker
import com.shangkele.core.ai.asr.CtcDecoder
import com.shangkele.core.ai.asr.SenseVoiceSpec
import com.shangkele.core.ai.dsp.AsrFrontend
import com.shangkele.core.ai.dsp.Cmvn
import java.io.Closeable
import java.io.File
import java.nio.FloatBuffer
import java.nio.IntBuffer

/**
 * SenseVoice 整段转写引擎。
 *
 * **内存是这里的主要矛盾**：CTC 输出是 `[T', 25055]` 的 float32，
 * 90 分钟课不分段的话是 9GB。所以：
 *  - 先用 [AudioChunker] 按静音切成 28 秒左右的段
 *  - 每段单独推理、单独解码、立刻丢弃 logits
 *  - 只有文本被留下来
 *
 * **线程数锁 4**：骁龙 7 Gen3 是 4 个大核 + 4 个小核，
 * 开满 8 线程反而会被小核拖慢并显著升温。
 *
 * 一次只允许一个推理在跑（`synchronized`）：模型常驻内存约 500MB，
 * 并发跑两份会直接把荣耀 200 的内存压力顶到被系统清理。
 */
class SenseVoiceEngine(
    private val modelFile: File,
    private val cmvn: Cmvn,
    private val vocabulary: AsrVocabulary,
    private val chunker: AudioChunker = AudioChunker(),
    intraOpThreads: Int = 4,
) : Closeable {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val frontend = AsrFrontend()
    private val decoder = CtcDecoder(vocabulary)

    init {
        require(modelFile.isFile) { "模型文件不存在：${modelFile.absolutePath}" }
        require(cmvn.dim == frontend.featureDim) {
            "CMVN 维度 ${cmvn.dim} 与模型要求的 ${frontend.featureDim} 不符"
        }
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(intraOpThreads)
            setInterOpNumThreads(1)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        session = env.createSession(modelFile.absolutePath, options)
    }

    /** 一段的转写结果，带时间范围，用于「点文本回跳原声」。 */
    data class TranscriptChunk(
        val text: String,
        val startMs: Long,
        val endMs: Long,
    )

    /**
     * 整段转写，返回带时间戳的分段结果。
     *
     * 分段边界来自 [AudioChunker] 找到的静音点，因此时间戳是「真实切点」
     * 而不是按字数均分的估算值。
     *
     * @param samples 16kHz 单声道浮点 PCM（[-1, 1]）
     * @param onProgress `(已处理段数, 总段数)`
     */
    fun transcribeChunks(
        samples: FloatArray,
        sampleRate: Int = 16_000,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): List<TranscriptChunk> {
        if (samples.isEmpty()) return emptyList()
        val segments = chunker.plan(samples, sampleRate)
        if (segments.isEmpty()) return emptyList()

        val result = ArrayList<TranscriptChunk>(segments.size)
        segments.forEachIndexed { index, segment ->
            val piece = samples.copyOfRange(segment.startSample, segment.endSampleExclusive)
            val text = runCatching { transcribeSegment(piece) }.getOrDefault("")
            if (text.isNotBlank()) {
                result += TranscriptChunk(
                    text = text,
                    startMs = segment.startSample * 1000L / sampleRate,
                    endMs = segment.endSampleExclusive * 1000L / sampleRate,
                )
            }
            onProgress(index + 1, segments.size)
        }
        return result
    }

    /** 纯文本版本，见 [transcribeChunks]。 */
    fun transcribe(
        samples: FloatArray,
        sampleRate: Int = 16_000,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): String = transcribeChunks(samples, sampleRate, onProgress)
        .joinToString(" ") { it.text }
        .trim()

    /** 单段推理，段长应控制在 30 秒内，否则 logits 会吃掉几百 MB。 */
    @Synchronized
    fun transcribeSegment(samples: FloatArray): String {
        val features = frontend.extract(samples, cmvn)
        if (features.isEmpty()) return ""

        val frames = features.size
        val dim = frontend.featureDim
        val flat = FloatArray(frames * dim)
        for (f in 0 until frames) {
            System.arraycopy(features[f], 0, flat, f * dim, dim)
        }

        val shape = longArrayOf(1, frames.toLong(), dim.toLong())
        OnnxTensor.createTensor(env, FloatBuffer.wrap(flat), shape).use { speech ->
            OnnxTensor.createTensor(
                env, IntBuffer.wrap(intArrayOf(frames)), longArrayOf(1),
            ).use { lengths ->
                OnnxTensor.createTensor(
                    env, IntBuffer.wrap(intArrayOf(SenseVoiceSpec.Language.AUTO)), longArrayOf(1),
                ).use { language ->
                    OnnxTensor.createTensor(
                        env, IntBuffer.wrap(intArrayOf(SenseVoiceSpec.TextNorm.WITH_ITN)), longArrayOf(1),
                    ).use { textnorm ->
                        val inputs = mapOf(
                            SenseVoiceSpec.INPUT_SPEECH to speech,
                            SenseVoiceSpec.INPUT_SPEECH_LENGTHS to lengths,
                            SenseVoiceSpec.INPUT_LANGUAGE to language,
                            SenseVoiceSpec.INPUT_TEXTNORM to textnorm,
                        )
                        session.run(inputs).use { result ->
                            // 按名字取输出而不是按下标：ONNX 不保证输出顺序，
                            // 按下标取一旦顺序变了就会静默拿到错误的张量
                            var logitsTensor: OnnxTensor? = null
                            var encoderLens: OnnxTensor? = null
                            for (entry in result) {
                                when (entry.key) {
                                    SenseVoiceSpec.OUTPUT_CTC_LOGITS ->
                                        logitsTensor = entry.value as? OnnxTensor
                                    SenseVoiceSpec.OUTPUT_ENCODER_LENS ->
                                        encoderLens = entry.value as? OnnxTensor
                                }
                            }
                            val logitsValue = logitsTensor ?: return ""
                            val buffer = logitsValue.floatBuffer
                            val logits = FloatArray(buffer.remaining())
                            buffer.get(logits)

                            val frames = logits.size / vocabulary.size
                            val validFrames = readEncoderLength(encoderLens, frames)
                            return decoder.decode(logits, frames = frames, frameCount = validFrames)
                        }
                    }
                }
            }
        }
    }

    /**
     * `encoder_out_lens` 有时会略大于我们送进去的帧数（模型内部有补帧），
     * 所以必须夹到实际 logits 的帧数内，否则解码会越界。
     */
    private fun readEncoderLength(tensor: OnnxTensor?, fallback: Int): Int {
        val value = runCatching { (tensor?.value as? Array<*>)?.firstOrNull() }.getOrNull()
        val length = when (value) {
            is IntArray -> value.firstOrNull()
            is LongArray -> value.firstOrNull()?.toInt()
            is Array<*> -> (value.firstOrNull() as? Number)?.toInt()
            else -> null
        }
        return (length ?: fallback).coerceIn(0, fallback)
    }

    override fun close() {
        runCatching { session.close() }
    }
}
