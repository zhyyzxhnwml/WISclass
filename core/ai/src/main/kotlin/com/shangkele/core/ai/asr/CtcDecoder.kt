package com.shangkele.core.ai.asr

/**
 * CTC 贪心解码。
 *
 * 规则很小，但三个细节错一个结果就全乱：
 *  1. 每帧取 argmax；
 *  2. **先去重再删 blank**（kaldi 的顺序）。反过来会把 "a _ a" 这类
 *     中间夹 blank 的重复字丢掉；
 *  3. blank 是 id 0，但词表第 0 项是 `<unk>`，所以不能拿词表项判 blank。
 *
 * 不需要 beam search：SenseVoice 是 CTC 模型且训练充分，贪心已经够用，
 * 而且省掉了外部解码器的依赖。
 */
class CtcDecoder(
    private val vocab: AsrVocabulary,
    private val blankId: Int = SenseVoiceSpec.BLANK_ID,
) {

    /**
     * @param logits 每帧一个长度为 `vocab.size` 的向量（未做 softmax 也可以，
     *   argmax 与 softmax 单调等价，省掉一次指数运算）
     * @param frameCount 只解码前多少帧（由模型输出的 `encoder_out_lens` 给出）
     */
    fun decode(logits: Array<FloatArray>, frameCount: Int = logits.size): String {
        val limit = frameCount.coerceIn(0, logits.size)
        val sb = StringBuilder()
        var previous = -1

        for (f in 0 until limit) {
            val tokenId = argMax(logits[f])
            if (tokenId != previous && tokenId != blankId) {
                vocab[tokenId]?.let { sb.append(it) }
            }
            previous = tokenId
        }
        return SenseVoiceSpec.normalizeText(sb.toString())
    }

    /** 从扁平的 `[frames * vocabSize]` 缓冲解码，省掉一次二维数组构造。 */
    fun decode(flatLogits: FloatArray, frames: Int, frameCount: Int = frames): String {
        val limit = frameCount.coerceIn(0, frames)
        val sb = StringBuilder()
        var previous = -1

        for (f in 0 until limit) {
            val base = f * vocab.size
            var best = 0
            var bestValue = Float.NEGATIVE_INFINITY
            for (i in 0 until vocab.size) {
                val v = flatLogits[base + i]
                if (v > bestValue) {
                    bestValue = v
                    best = i
                }
            }
            if (best != previous && best != blankId) {
                vocab[best]?.let { sb.append(it) }
            }
            previous = best
        }
        return SenseVoiceSpec.normalizeText(sb.toString())
    }

    private fun argMax(row: FloatArray): Int {
        var best = 0
        var bestValue = Float.NEGATIVE_INFINITY
        for (i in row.indices) {
            if (row[i] > bestValue) {
                bestValue = row[i]
                best = i
            }
        }
        return best
    }
}
