package com.shangkele.core.ai.model

import android.content.Context
import com.shangkele.core.ai.dsp.Cmvn
import com.shangkele.core.ai.asr.AsrVocabulary
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 模型文件的管理：就绪判定、断点续传下载、SHA256 校验。
 *
 * 三个刻意的设计：
 *  1. **下载到 `.part` 再改名**。直接写目标文件的话，中途被杀会留下一个
 *     看着像完整模型的坏文件，下次启动判定「已就绪」，然后在推理时崩得莫名其妙。
 *  2. **断点续传**。230MB 在校园网下不算小，重来一次很烦；用 Range 续传。
 *  3. **先校验再改名**。SHA256 不符就删掉重下，宁可多下一次也不要带着坏模型跑。
 */
@Singleton
class SenseVoiceModelManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** 模型目录：`filesDir/models/sensevoice`。放内部目录，避免被外部清理工具误删。 */
    fun modelDir(): File = File(File(context.filesDir, "models"), AsrModelSpec.DIR).apply { mkdirs() }

    fun modelFile(): File = File(modelDir(), AsrModelSpec.ONNX_FILE_NAME)

    private fun partFile(): File = File(modelDir(), "${AsrModelSpec.ONNX_FILE_NAME}.part")

    /** 模型是否已就绪：存在且体积对得上（体积比对便宜，SHA 只在下载完成后做）。 */
    fun isAsrModelReady(): Boolean {
        val f = modelFile()
        return f.isFile && f.length() == AsrModelSpec.ASR_MODEL.sizeBytes
    }

    /** 已下载的字节数，用于展示续传进度。 */
    fun downloadedBytes(): Long = partFile().takeIf { it.isFile }?.length() ?: 0L

    /** 读 assets 里的文本（`am.mvn` / `tokens.json`）。 */
    fun readAsset(path: String): String =
        context.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }

    fun loadCmvn(): Cmvn = Cmvn.parse(readAsset(AsrModelSpec.ASSET_AM_MVN))

    fun loadVocabulary(): AsrVocabulary =
        AsrVocabulary.parse(readAsset(AsrModelSpec.ASSET_TOKENS), AsrModelSpec.VOCAB_SIZE)

    /**
     * 确保模型就绪，必要时下载。
     *
     * @param onProgress `(已下载字节, 总字节)`
     * @return 模型文件
     */
    suspend fun ensureAsrModel(
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): Result<File> = withContext(Dispatchers.IO) {
        val target = modelFile()
        if (isAsrModelReady()) return@withContext Result.success(target)

        val spec = AsrModelSpec.ASR_MODEL
        var attempt = 0
        while (attempt < 2) {
            attempt++
            val result = runCatching { download(spec, onProgress) }
            if (result.isSuccess) {
                val file = result.getOrThrow()
                if (verifySha256(file, spec.sha256)) {
                    if (file != target) {
                        target.delete()
                        file.renameTo(target)
                    }
                    return@withContext Result.success(target)
                }
                // 校验失败：把 .part 删掉，下一轮从零开始
                file.delete()
            } else if (attempt >= 2) {
                return@withContext Result.failure(
                    result.exceptionOrNull() ?: IllegalStateException("模型下载失败"),
                )
            }
        }
        Result.failure(IllegalStateException("模型下载后校验不通过，已重试一次"))
    }

    private fun download(
        spec: AsrModelSpec.ModelFile,
        onProgress: (Long, Long) -> Unit,
    ): File {
        val part = partFile()
        var existing = if (part.isFile) part.length() else 0L
        if (existing >= spec.sizeBytes) {
            existing = 0L
            part.delete()
        }

        val request = Request.Builder()
            .url(AsrModelSpec.downloadUrl(spec))
            .apply { if (existing > 0) header("Range", "bytes=$existing-") }
            .build()

        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("模型下载失败：HTTP ${response.code}")
            }

            // 服务端不支持 Range 时会返回 200 而不是 206，此时必须从零写
            val append = existing > 0 && response.code == 206
            if (!append && existing > 0) {
                existing = 0L
                part.delete()
            }

            val body = response.body ?: error("模型下载响应为空")
            val contentLength = body.contentLength().takeIf { it > 0 } ?: (spec.sizeBytes - existing)
            val total = existing + contentLength
            onProgress(existing, total)

            var written = existing
            RandomAccessFile(part, "rw").use { raf ->
                raf.seek(existing)
                val buffer = ByteArray(BUFFER_SIZE)
                body.byteStream().use { input ->
                    var lastReport = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        raf.write(buffer, 0, read)
                        written += read
                        if (written - lastReport >= PROGRESS_STEP) {
                            lastReport = written
                            onProgress(written, total)
                        }
                    }
                }
            }
            onProgress(written, total)

            if (written != spec.sizeBytes) {
                error("模型下载不完整：$written / ${spec.sizeBytes}")
            }
        }
        return part
    }

    private fun verifySha256(file: File, expected: String): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        val actual = digest.digest().joinToString("") { "%02X".format(it) }
        return actual.equals(expected, ignoreCase = true)
    }

    /** 删除模型，释放 230MB。设置页的「清理」用。 */
    fun deleteAsrModel() {
        partFile().delete()
        modelFile().delete()
    }

    private companion object {
        const val BUFFER_SIZE = 128 * 1024
        const val PROGRESS_STEP = 2L * 1024 * 1024
    }
}
