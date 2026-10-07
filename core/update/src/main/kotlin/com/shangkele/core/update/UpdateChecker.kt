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

    /**
     * 检查有没有新版本。
     *
     * 主源失败时会再试一次[备用源][UpdateSource.FALLBACK_MANIFEST_URL]：
     * 「更新源连不上」在用户眼里就是「这 App 再也更新不了」，而两个不同域
     * 同时挂掉的概率很低。全都失败才报错，并且把两边的原因都列出来 ——
     * 只说「检查失败」的话，用户没法判断是自己网络的问题还是源的问题。
     */
    suspend fun check(currentVersion: String, url: String = source.manifestUrl): CheckResult {
        if (url.isBlank()) {
            return CheckResult.Failed("还没配置更新源，去「设置 → 检查更新」填一个地址")
        }

        val candidates = listOf(url, UpdateSource.FALLBACK_MANIFEST_URL)
            .filter { it.isNotBlank() }
            .distinct()

        val failures = mutableListOf<String>()
        for (candidate in candidates) {
            when (val result = checkOne(candidate, currentVersion)) {
                // 主源答得出结果就以它为准，不去拿备用源比版本
                is CheckResult.Failed -> failures += result.message
                else -> return result
            }
        }

        return CheckResult.Failed(
            if (failures.size <= 1) {
                failures.firstOrNull() ?: "检查更新失败"
            } else {
                "两个更新源都没连上：\n· ${failures[0]}\n· ${failures[1]}"
            },
        )
    }

    private suspend fun checkOne(url: String, currentVersion: String): CheckResult = try {
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
     * 给静态分发地址加一个时间戳，**尽量**绕开 CDN 缓存。
     *
     * 得说清楚：这条**并不可靠**。本机实测 raw.githubusercontent.com 带随机参数
     * 依然返回 `X-Cache: HIT`，也就是参数没进缓存键 —— 所以「刚发完版手机上读到旧清单」
     * 这件事仍会发生，真正管用的办法是发完版等几分钟再检查（见 tools/release/README.md）。
     * 留着它是「有总比没有强」，而且对别的静态托管可能是有效的。
     *
     * 不处理 API 域名：那类接口本身不缓存内容，加参数没意义。
     */
    private fun withCacheBuster(url: String): String {
        if (url.contains("api.github.com") || url.contains("gitee.com/api")) return url
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
