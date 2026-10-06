package com.shangkele.core.ai.dsp

import kotlin.math.cos

/**
 * kaldi 风格 fbank 特征提取。
 *
 * 逐帧顺序**严格按 kaldi**（顺序错了结果就错，但不会报错）：
 * ```
 *   1. 取帧（frame * frameShift 起，长 frameLength）
 *   2. 去直流：减去该帧均值
 *   3. 预加重：x[i] -= 0.97 * x[i-1]，i 从后往前；首点用自身补 x[-1]
 *   4. 加窗：对称 hamming 0.54 - 0.46*cos(2πi/(N-1))，长度 frameLength
 *   5. 补零到 paddedLength
 *   6. FFT → 功率谱 |X[k]|²，k = 0..padded/2
 *   7. mel 滤波器组
 *   8. log(max(e, logFloor))
 * ```
 *
 * 这 8 步全部与 `kaldi_native_fbank` 实测对齐（最大误差 7.9e-05，float32 精度极限），
 * 见 `FbankGoldenTest`。
 */
class FbankExtractor(val options: FbankOptions = FbankOptions()) {

    private val window: FloatArray = FloatArray(options.frameLength).also { w ->
        val a = 2.0 * Math.PI / (options.frameLength - 1)
        for (i in w.indices) {
            w[i] = (0.54 - 0.46 * cos(a * i)).toFloat()
        }
    }

    private val fft = RealFft(options.paddedLength)
    private val melBanks = MelFilterBank.build(
        sampleRate = options.sampleRate,
        fftSize = options.paddedLength,
        numBins = options.numMelBins,
        lowFreq = options.lowFreq,
        highFreq = options.highFreq,
    )

    // 复用缓冲，避免每帧分配
    private val frame = FloatArray(options.paddedLength)
    private val power = FloatArray(options.numFftBins)
    private val melOut = FloatArray(options.numMelBins)

    val numMelBins: Int get() = options.numMelBins

    fun numFrames(numSamples: Int): Int = options.numFrames(numSamples)

    /** 取窗函数系数，便于与基准比对。 */
    fun windowCoefficients(): FloatArray = window.copyOf()

    fun melFilterBank(): MelFilterBank = melBanks

    /**
     * @param samples 浮点采样。传入前无需放大，本方法会按 [FbankOptions.inputScale] 处理。
     * @return `frames x numMelBins` 的 log-mel 特征
     */
    fun compute(samples: FloatArray): Array<FloatArray> {
        val scale = options.inputScale.toFloat()
        val frames = options.numFrames(samples.size)
        val result = Array(frames) { FloatArray(options.numMelBins) }

        val frameLength = options.frameLength
        val frameShift = options.frameShift
        val preemph = options.preemphCoeff.toFloat()
        val floor = options.logFloor

        for (f in 0 until frames) {
            val start = f * frameShift

            // 1) 取帧并放大到 int16 量纲
            var sum = 0f
            for (i in 0 until frameLength) {
                val v = samples[start + i] * scale
                frame[i] = v
                sum += v
            }
            for (i in frameLength until options.paddedLength) frame[i] = 0f

            // 2) 去直流
            if (options.removeDcOffset && frameLength > 0) {
                val mean = sum / frameLength
                for (i in 0 until frameLength) frame[i] -= mean
            }

            // 3) 预加重：倒序遍历保证用的是原始前一个采样；首点以自身作为前值
            if (preemph != 0f) {
                val first = frame[0]
                for (i in frameLength - 1 downTo 1) {
                    frame[i] -= preemph * frame[i - 1]
                }
                frame[0] -= preemph * first
            }

            // 4) 加窗
            for (i in 0 until frameLength) frame[i] *= window[i]

            // 5~7) FFT → 功率谱 → mel
            fft.powerSpectrum(frame, power)
            melBanks.compute(power, melOut)

            // 8) log（带下限，防止 log(0) = -inf）
            val out = result[f]
            for (i in 0 until options.numMelBins) {
                val e = melOut[i]
                out[i] = Math.log(if (e > floor) e.toDouble() else floor.toDouble()).toFloat()
            }
        }
        return result
    }
}
