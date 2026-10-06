package com.shangkele.core.ai.dsp

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/**
 * 黄金参考数据的读取与生成。
 *
 * 数据由 Python 侧的 `kaldi_native_fbank`（与 sherpa-onnx 同源）产出，
 * 详见项目根 `tools/` 下的生成脚本说明。测试信号用纯正弦叠加，
 * Kotlin 与 Python 用同一公式生成，因此不需要把音频文件塞进仓库。
 */
object Golden {

    const val SR = 16_000
    const val NUM_SAMPLES = 8320

    private val FREQS = listOf(440.0 to 0.5, 1150.0 to 0.3, 3100.0 to 0.15)

    /** 与 Python `test_signal()` 逐位一致（均用 float64 计算后再降到 float32）。 */
    fun testSignal(): FloatArray {
        val out = FloatArray(NUM_SAMPLES)
        for (i in 0 until NUM_SAMPLES) {
            var v = 0.0
            for ((freq, amp) in FREQS) {
                v += amp * sin(2.0 * PI * freq * i / SR)
            }
            out[i] = v.toFloat()
        }
        return out
    }

    private fun bytes(name: String): ByteArray {
        val stream = Golden::class.java.getResourceAsStream("/$name")
            ?: error("缺少测试资源 $name（应放在 core/ai/src/test/resources/）")
        return stream.use { it.readBytes() }
    }

    fun floats(name: String): FloatArray {
        val raw = bytes(name)
        require(raw.size % 4 == 0) { "$name 长度不是 4 的倍数" }
        val buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(raw.size / 4) { buf.getFloat() }
    }

    fun matrix(name: String, cols: Int): Array<FloatArray> {
        val flat = floats(name)
        require(flat.size % cols == 0) { "$name 长度 ${flat.size} 不能被 $cols 整除" }
        return Array(flat.size / cols) { row ->
            FloatArray(cols) { col -> flat[row * cols + col] }
        }
    }

    fun text(name: String): String = String(bytes(name), Charsets.UTF_8)
}
