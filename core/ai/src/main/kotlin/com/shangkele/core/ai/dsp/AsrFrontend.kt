package com.shangkele.core.ai.dsp

/**
 * ASR 前端流水线：fbank → LFR → CMVN。
 *
 * 这三步必须在模型外做完，因为 SenseVoice 的 ONNX 输入直接是 `[T, 560]`。
 * 三者任意一步错了都不会报错，只会让识别结果悄悄变差，所以每一步都有独立单测。
 *
 * @param featureDim 模型期望的特征维度，应为 `numMelBins * lfrM` = 560
 */
class AsrFrontend(
    private val fbankOptions: FbankOptions = FbankOptions(),
    private val lfrM: Int = 7,
    private val lfrN: Int = 6,
) {
    private val extractor = FbankExtractor(fbankOptions)
    private val lfr = Lfr(lfrM, lfrN)

    val featureDim: Int = fbankOptions.numMelBins * lfrM

    /**
     * @param samples 浮点采样（[-1, 1]，即 Android 解出来的原始 PCM 浮点）
     * @param cmvn 与 [featureDim] 同维的 CMVN 参数
     * @return `T' x featureDim`，可直接喂给 ONNX
     */
    fun extract(samples: FloatArray, cmvn: Cmvn): Array<FloatArray> {
        require(cmvn.dim == featureDim) {
            "CMVN 维度 ${cmvn.dim} 与期望特征维度 $featureDim 不匹配"
        }
        val mel = extractor.compute(samples)
        if (mel.isEmpty()) return emptyArray()
        val stacked = lfr.apply(mel)
        cmvn.apply(stacked)
        return stacked
    }

    /** 只跑 fbank，便于与基准逐帧比对。 */
    fun fbankOnly(samples: FloatArray): Array<FloatArray> = extractor.compute(samples)

    fun lfrOnly(frames: Array<FloatArray>): Array<FloatArray> = lfr.apply(frames)

    fun numFramesFor(numSamples: Int): Int = lfr.outputFrames(extractor.numFrames(numSamples))

    internal fun windowCoefficients(): FloatArray = extractor.windowCoefficients()

    internal fun melFilterBank(): MelFilterBank = extractor.melFilterBank()
}
