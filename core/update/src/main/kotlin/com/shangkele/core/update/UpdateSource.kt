package com.shangkele.core.update

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 更新源配置。
 *
 * 地址做成可配置的，是因为「能不能连上 GitHub」这件事不由我决定：
 * 本机实测 `github.com` 通、但 `api.github.com` 的**匿名配额是按 IP 算的**
 * （每小时 60 次，共享出口很容易被用光）。填一个 Token 配额就变成 5000/小时；
 * 实在连不上还能改成一个自建的静态 json —— 见 `tools/release/serve.ps1`。
 */
@Singleton
class UpdateSource @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 清单地址。默认指向 GitHub Releases API。 */
    var manifestUrl: String
        get() = prefs.getString(KEY_URL, DEFAULT_MANIFEST_URL)?.takeIf { it.isNotBlank() }
            ?: DEFAULT_MANIFEST_URL
        set(value) = prefs.edit().putString(KEY_URL, value.trim()).apply()

    /** 可选的 GitHub Token，只用于提高 API 配额，不做别的。 */
    var token: String
        get() = prefs.getString(KEY_TOKEN, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_TOKEN, value.trim()).apply()

    /** 上次自动检查的时间，用来做频率限制。 */
    var lastCheckMs: Long
        get() = prefs.getLong(KEY_LAST_CHECK, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_CHECK, value).apply()

    /** 用户点了「以后再说」的版本号 —— 同一个版本不再反复弹。 */
    var skippedVersion: String
        get() = prefs.getString(KEY_SKIPPED, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_SKIPPED, value).apply()

    fun isConfigured(): Boolean = manifestUrl.isNotBlank()

    companion object {
        /**
         * 默认更新源：仓库里的 `release/latest.json`。
         *
         * **为什么是 Gitee 而不是 GitHub**：GitHub 在国内经常连不上，而更新源连不上
         * 就等于「这个 App 再也更新不了」—— 用户会以为是自己手机的问题。
         * Gitee 的 raw 是公开直读的，不要 token、不限额。
         *
         * 也不用任何平台的「Release API」：那类接口要么按 IP 算匿名配额
         * （本机实测撞到过 `0/60`，一律 403），要么需要 token。
         * 读一个公开仓库的小文件，raw 最省事。
         *
         * 清单里的 `apkUrl` 才决定 APK 从哪儿下。看 `tools/release/README.md`。
         */
        const val DEFAULT_MANIFEST_URL: String =
            "https://gitee.com/wis314/wisclass/raw/main/release/latest.json"

        /**
         * 备用更新源：GitHub 上同一份清单。
         *
         * 只在主源**失败**时才去试（不是用来比版本的）：两个不同域同时不可达的概率
         * 很低，而「主源抽风一次就完全更新不了」是用户直接能感知到的故障。
         */
        const val FALLBACK_MANIFEST_URL: String =
            "https://raw.githubusercontent.com/zhyyzxhnwml/WISclass/main/release/latest.json"

        /** 自动检查的最小间隔。开 App 就查一次太浪费配额，也没必要。 */
        const val AUTO_CHECK_INTERVAL_MS: Long = 6 * 60 * 60 * 1000L

        private const val PREFS_NAME = "update_source"
        private const val KEY_URL = "manifest_url"
        private const val KEY_TOKEN = "github_token"
        private const val KEY_LAST_CHECK = "last_check_ms"
        private const val KEY_SKIPPED = "skipped_version"
    }
}
