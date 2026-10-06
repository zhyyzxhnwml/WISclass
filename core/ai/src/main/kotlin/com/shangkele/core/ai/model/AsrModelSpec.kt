package com.shangkele.core.ai.model

/**
 * ASR 模型的来源与校验信息。
 *
 * **为什么要下载而不是全部打进 APK**：模型本体 230MB，
 * assets 里的大文件会被 aapt 压缩，解压后还要再占一份磁盘；
 * 而配置类小文件（`am.mvn` 11KB、`tokens.json` 344KB）放在 assets 里几乎没有成本。
 * 所以策略是：**小的内置，大的下载**。
 *
 * 下载源用 ModelScope（魔搭）而不是 HuggingFace：本机实测 GitHub / HuggingFace 均不可达，
 * ModelScope 稳定可达，且这是官方（iic = 通义实验室）发布的 ONNX 版本。
 */
object AsrModelSpec {

    /** 模型目录名，位于 `filesDir/models/` 下 */
    const val DIR = "sensevoice"

    /** assets 里的配置类小文件 */
    const val ASSET_AM_MVN = "sensevoice/am.mvn"
    const val ASSET_TOKENS = "sensevoice/tokens.json"

    /** 词表大小，与 tokens.json 行数一致 */
    const val VOCAB_SIZE = 25055

    const val ONNX_FILE_NAME = "model_quant.onnx"

    /** 主模型。体积 230MB，SHA256 实测自下载产物。 */
    val ASR_MODEL = ModelFile(
        fileName = ONNX_FILE_NAME,
        sizeBytes = 241_216_270L,
        sha256 = "21DC965F689A78D1604717BF561E40D5A236087C85A95584567835750549E822",
        remotePath = "model_quant.onnx",
    )

    /** assets 里的文件（下载失败时也算「就绪」，因为它们本来就在包里） */
    val ASSET_HASHES = mapOf(
        ASSET_AM_MVN to "29B3C740A2C0CFC6B308126D31D7F265FA2BE74F3BB095CD2F143EA970896AE5",
        ASSET_TOKENS to "A2594FC1474E78973149CBA8CD1F603EBED8C39C7DECB470631F66E70CE58E97",
    )

    private const val MODELSCOPE_BASE =
        "https://modelscope.cn/api/v1/models/iic/SenseVoiceSmall-onnx/repo"

    /** 直链。`FilePath` 必须做 URL 编码，否则带 `/` 的路径会被截断。 */
    fun downloadUrl(file: ModelFile): String =
        "$MODELSCOPE_BASE?Revision=master&FilePath=${encodePath(file.remotePath)}"

    private fun encodePath(path: String): String =
        java.net.URLEncoder.encode(path, "UTF-8").replace("+", "%20")

    data class ModelFile(
        val fileName: String,
        val sizeBytes: Long,
        val sha256: String,
        val remotePath: String,
    ) {
        /** 人类可读体积，用于展示下载提示 */
        val sizeLabel: String get() = "%.0f MB".format(sizeBytes / 1024.0 / 1024.0)
    }
}
