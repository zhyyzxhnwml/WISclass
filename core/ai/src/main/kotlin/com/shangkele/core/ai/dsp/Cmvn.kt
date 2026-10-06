package com.shangkele.core.ai.dsp

/**
 * 倒谱均值方差归一化（CMVN），参数来自 `am.mvn`。
 *
 * 文件是 Kaldi 的文本格式，形如：
 * ```
 * <Nnet>
 * <Splice> 560 560
 * [ 0 ]
 * <AddShift> 560 560
 * <LearnRateCoef> 0 [ -8.311879 -8.600912 ... ]
 * <Rescale> 560 560
 * <LearnRateCoef> 0 [ 0.155775 0.154484 ... ]
 * </Nnet>
 * ```
 * 应用方式：`y = (x + means) * scales`。
 *
 * 解析时**刻意用正则抠数字**而不是按空格切：不同工具导出的文件里
 * `[` 有时紧跟第一个数字（`[-8.31`），按空格切会把符号粘进数字里，
 * 结果解析出一堆 NaN —— 而且要到推理时才发现。
 */
class Cmvn(
    val means: FloatArray,
    val scales: FloatArray,
) {
    val dim: Int get() = means.size

    /** 原地归一化。 */
    fun apply(features: Array<FloatArray>) {
        require(features.isEmpty() || features[0].size == dim) {
            "特征维度 ${features.firstOrNull()?.size} 与 CMVN 维度 $dim 不一致"
        }
        for (frame in features) {
            for (i in 0 until dim) {
                frame[i] = (frame[i] + means[i]) * scales[i]
            }
        }
    }

    companion object {
        private val NUMBER = Regex("[-+]?\\d*\\.?\\d+(?:[eE][-+]?\\d+)?")

        fun parse(text: String): Cmvn {
            val lines = text.lines()
            var means: FloatArray? = null
            var scales: FloatArray? = null

            for ((index, line) in lines.withIndex()) {
                val head = line.trim().split(Regex("\\s+")).firstOrNull() ?: continue
                if (index + 1 >= lines.size) continue
                when (head) {
                    "<AddShift>" -> means = numbersOf(lines[index + 1])
                    "<Rescale>" -> scales = numbersOf(lines[index + 1])
                }
            }

            val m = means ?: error("am.mvn 里没找到 <AddShift>")
            val s = scales ?: error("am.mvn 里没找到 <Rescale>")
            require(m.size == s.size) {
                "am.mvn 的 AddShift(${m.size}) 与 Rescale(${s.size}) 维度不一致"
            }
            require(m.isNotEmpty()) { "am.mvn 解析出的向量为空" }
            return Cmvn(m, s)
        }

        private fun numbersOf(line: String): FloatArray {
            // 去掉 <LearnRateCoef> 及其后的系数 0，剩下的括号内数字才是向量
            val body = line.substringAfter("<LearnRateCoef>", missingDelimiterValue = line)
            val matches = NUMBER.findAll(body).map { it.value }.toList()
            // body 以 "<LearnRateCoef>" 的值开头时，首个数字是那个 0，需要丢掉
            val values = if (line.contains("<LearnRateCoef>")) matches.drop(1) else matches
            return values.map { it.toFloat() }.toFloatArray()
        }
    }
}
