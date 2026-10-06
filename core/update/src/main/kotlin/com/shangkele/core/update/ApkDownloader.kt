package com.shangkele.core.update

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 把新版 APK 下到缓存目录。
 *
 * 两个必须守住的点：
 *  - **先下到 `.part` 再改名**。否则中途断网会在缓存里留下一个「看着完整」的
 *    半截 APK，安装时只报一句语焉不详的「解析包时出现问题」。
 *  - **校验体积**。release 里带了 size，对不上就丢弃重下；这能挡住
 *    「下载被运营商插入了一个 HTML 错误页」这类问题 —— 那种文件同样有大小。
 */
@Singleton
class ApkDownloader @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun targetFile(version: String): File {
        val dir = File(context.cacheDir, DIR).apply { mkdirs() }
        // 版本名里可能有 / 之类的字符，换掉再当文件名
        val safe = version.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(dir, "ShangKeLe-$safe.apk")
    }

    /**
     * 下载。已经下好且体积对得上就直接复用，不重复跑流量。
     *
     * @param onProgress `(已下载字节, 总字节)`；总字节未知时第二个参数为 -1
     */
    suspend fun download(
        manifest: UpdateManifest,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
    ): Result<File> = withContext(Dispatchers.IO) {
        val target = targetFile(manifest.version)
        if (target.isFile && manifest.sizeBytes > 0 && target.length() == manifest.sizeBytes) {
            return@withContext Result.success(target)
        }

        val part = File(target.parentFile, "${target.name}.part")
        try {
            val request = Request.Builder()
                .url(manifest.apkUrl)
                .header("User-Agent", "ShangKeLe-Updater")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("下载失败：HTTP ${response.code}")
                }
                val body = response.body ?: throw IOException("下载失败：响应为空")
                val total = body.contentLength().takeIf { it > 0 } ?: manifest.sizeBytes

                body.byteStream().use { input ->
                    part.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            done += read
                            onProgress(done, total)
                        }
                    }
                }
            }

            if (manifest.sizeBytes > 0 && part.length() != manifest.sizeBytes) {
                part.delete()
                throw IOException(
                    "下载的体积和发布信息对不上（${part.length()} ≠ ${manifest.sizeBytes}），已丢弃",
                )
            }

            if (target.exists()) target.delete()
            if (!part.renameTo(target)) {
                part.delete()
                throw IOException("下载完成但无法落盘")
            }
            Result.success(target)
        } catch (e: Exception) {
            runCatching { part.delete() }
            Result.failure(e)
        }
    }

    private companion object {
        const val DIR = "updates"
    }
}
