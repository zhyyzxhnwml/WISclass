package com.shangkele.core.ai.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteOrder

/**
 * 把录音产出的 `.m4a`(AAC) 解成 16kHz 单声道浮点 PCM。
 *
 * 录音时就是按 16kHz 单声道录的，所以正常情况下不需要重采样；
 * 但 `MediaCodec` 的解码输出采样率由**解码器**决定，不保证与录制参数一致，
 * 因此这里仍然做一次判断与线性重采样兜底 ——
 * 采样率不对的话 fbank 出来的特征完全是错的，而且不会报错。
 */
object AacAudioDecoder {

    private const val TIMEOUT_US = 10_000L

    fun decodeToMonoFloat(file: File, targetSampleRate: Int = 16_000): FloatArray {
        require(file.isFile) { "录音文件不存在：${file.absolutePath}" }

        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).stringOrNull(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("录音文件里没有音频轨道")
            extractor.selectTrack(trackIndex)

            val inputFormat = extractor.getTrackFormat(trackIndex)
            val mime = inputFormat.stringOrNull(MediaFormat.KEY_MIME) ?: error("读不到音频 MIME 类型")

            var sampleRate = inputFormat.intOr(MediaFormat.KEY_SAMPLE_RATE, targetSampleRate)
            var channels = inputFormat.intOr(MediaFormat.KEY_CHANNEL_COUNT, 1)

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(inputFormat, null, null, 0)
            codec.start()

            val pcm = ShortAccumulator()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val buffer = codec.getInputBuffer(inIndex) ?: continue
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(
                                inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                when (val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val out = codec.outputFormat
                        sampleRate = out.intOr(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
                        channels = out.intOr(MediaFormat.KEY_CHANNEL_COUNT, channels)
                    }

                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit

                    else -> if (outIndex >= 0) {
                        val buffer = codec.getOutputBuffer(outIndex)
                        if (buffer != null && info.size > 0) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            val shorts = ShortArray(info.size / 2)
                            buffer.order(ByteOrder.nativeOrder()).asShortBuffer().get(shorts)
                            pcm.append(shorts)
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                    }
                }
            }

            val interleaved = pcm.toFloatArray()
            val mono = toMono(interleaved, channels)
            return resample(mono, sampleRate, targetSampleRate)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    /** 多声道取平均。立体声只取一路会丢信息，平均更稳。 */
    internal fun toMono(interleaved: FloatArray, channels: Int): FloatArray {
        if (channels <= 1) return interleaved
        val frames = interleaved.size / channels
        val out = FloatArray(frames)
        for (i in 0 until frames) {
            var sum = 0f
            for (c in 0 until channels) sum += interleaved[i * channels + c]
            out[i] = sum / channels
        }
        return out
    }

    /** 线性插值重采样。只在解码器输出采样率与目标不一致时才会走到。 */
    internal fun resample(input: FloatArray, fromRate: Int, toRate: Int): FloatArray {
        if (fromRate == toRate || input.isEmpty()) return input
        val outLength = (input.size.toLong() * toRate / fromRate).toInt().coerceAtLeast(1)
        val out = FloatArray(outLength)
        val ratio = fromRate.toDouble() / toRate
        for (i in 0 until outLength) {
            val src = i * ratio
            val i0 = src.toInt().coerceIn(0, input.size - 1)
            val i1 = (i0 + 1).coerceAtMost(input.size - 1)
            val frac = (src - i0).toFloat()
            out[i] = input[i0] * (1f - frac) + input[i1] * frac
        }
        return out
    }

    private fun MediaFormat.intOr(key: String, fallback: Int): Int =
        if (containsKey(key)) runCatching { getInteger(key) }.getOrDefault(fallback) else fallback

    private fun MediaFormat.stringOrNull(key: String): String? =
        if (containsKey(key)) runCatching { getString(key) }.getOrNull() else null

    /** 可增长的 short 缓冲。录音动辄几十万采样点，用 List 装箱会很慢。 */
    private class ShortAccumulator {
        private var data = ShortArray(1 shl 16)
        private var size = 0

        fun append(values: ShortArray) {
            if (size + values.size > data.size) {
                var capacity = data.size
                while (capacity < size + values.size) capacity = capacity shl 1
                data = data.copyOf(capacity)
            }
            System.arraycopy(values, 0, data, size, values.size)
            size += values.size
        }

        fun toFloatArray(): FloatArray {
            val out = FloatArray(size)
            for (i in 0 until size) {
                out[i] = data[i] / 32768f
            }
            return out
        }
    }
}
