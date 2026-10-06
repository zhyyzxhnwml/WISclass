package com.shangkele.core.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 拉取更新清单并判断有没有新版本。
 *
 * 只在 [Dispatchers.IO] 上跑；失败一律转成 [CheckResult.Failed] 并把
 * **可操作的原因**带回来 —— 更新检查失败是最容易被写成「静默什么都不发生」的地方，
 * 用户会以为「已经是最新版」，其实只是没连上。
 */
@Singleton
class UpdateChecker @Inject constructor(
    private val source: UpdateSource,
) {

    sealed interface CheckResult {
        /** 已经是最新 */
        data class UpToDate(val current: String) : CheckResult

        /** 有新版本 */
        data class Available(val manifest: UpdateManifest) : CheckResult

        /** 检查没成功。message 是给用户看的，必须能指导下一步动作。 */
        data class Failed(val message: String) : CheckResult
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun check(currentVersion: String, url: String = source.manifestUrl): CheckResult {
        if (url.isBlank()) {
            return CheckResult.Failed("还没配置更新源，去「设置 → 检查更新」填一个地址")
        }
        return try {
            val raw = fetch(withCacheBuster(url))
            val manifest = UpdateManifestParser.parse(raw)
            if (VersionCompare.isNewer(manifest.version, currentVersion)) {
                CheckResult.Available(manifest)
            } else {
                CheckResult.UpToDate(currentVersion)
            }
        } catch (e: Exception) {
            CheckResult.Failed(describe(e))
        }
    }

    private suspend fun fetch(url: String): String = withContext(Dispatchers.IO) {
        val builder = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            // GitHub 强制要求带 User-Agent，缺了直接 403，
            // 而且报出来的原因和「配额用光」一模一样，非常难查
            .header("User-Agent", USER_AGENT)
        source.token.takeIf { it.isNotBlank() }?.let {
            builder.header("Authorization", "Bearer $it")
        }

        client.newCall(builder.build()).execute().use { response ->
            when {
                response.isSuccessful -> response.body?.string().orEmpty()
                response.code == 404 ->
                    throw IOException("更新源返回 404：仓库名或路径不对（当前源 $url）")
                response.code == 403 ->
                    throw IOException(
                        "更新源返回 403：匿名调用额度用完了。" +
                            "去「设置 → 检查更新」填一个 GitHub Token 可提到 5000 次/小时",
                    )
                else -> throw IOException("更新源返回 HTTP ${response.code}")
            }
        }
    }

    /**
     * 给 raw.githubusercontent.com 的地址加一个时间戳，绕开 CDN 缓存。
     *
     * raw 走 CDN，同一个文件大概会缓存几分钟。刚发完版就在手机上检查，
     * 会读到旧清单，表现是「作者说发了新版，但 App 说已是最新」——
     * 这种误会最后一定会被当成 bug 来找。
     *
     * 只对 raw 加：GitHub API 本身不缓存内容，加参数反而没意义。
     */
    private fun withCacheBuster(url: String): String {
        if (!url.contains("raw.githubusercontent.com")) return url
        val separator = if (url.contains('?')) '&' else '?'
        return "$url$separator" + "t=" + (System.currentTimeMillis() / 1000)
    }

    private fun describe(e: Exception): String = when (e) {
        is UpdateManifestParser.ParseException -> e.message ?: "更新清单解析失败"
        is IOException -> e.message ?: "网络请求失败"
        else -> "检查更新失败：${e.message ?: e.javaClass.simpleName}"
    }

    private companion object {
        const val USER_AGENT = "ShangKeLe-Updater"
    }
}
