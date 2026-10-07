package com.shangkele.core.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** 一次「有新版本」的完整描述。 */
data class UpdateManifest(
    val version: String,
    /** 更新说明（release 里的正文），可为空 */
    val notes: String,
    /**
     * 单个安装包的地址。
     *
     * 与 [parts] **二选一**。GitHub 那边能匿名下大文件，一个地址就够；
     * Gitee 不行（见 [parts]）。
     */
    val apkUrl: String? = null,
    /**
     * 分片地址。**按顺序拼起来就是一个完整的 APK。**
     *
     * 为什么需要它：Gitee 实测**禁止匿名下载 8MB 以上的文件** ——
     * 16MB 就返回 `403 large file require login for access`，而安装包有 40 多 MB。
     * 于是只能切成小片分别下载，下完按顺序接起来。
     */
    val parts: List<String> = emptyList(),
    /** 完整安装包的字节数。分片时也必须对得上拼完的总和。 */
    val sizeBytes: Long,
    val publishedAt: String?,
) {
    fun sizeLabel(): String =
        if (sizeBytes > 0) "%.1f MB".format(sizeBytes / 1024.0 / 1024.0) else "体积未知"

    /** 实际要下载的地址序列：有分片就用分片，否则用整包。 */
    val sources: List<String>
        get() = if (parts.isNotEmpty()) parts else listOfNotNull(apkUrl)

    /** 是否分片下载。进度提示要说清「第几片」，否则用户看着百分比卡住会以为死机。 */
    val isChunked: Boolean get() = parts.isNotEmpty()
}

/**
 * 解析更新清单。
 *
 * 同时吃三种形状，因为它们各有各的用处：
 *
 *  1. **GitHub Releases API**：`tag_name` / `body` / `assets[]`。
 *     资源放在 release 里而不是塞进 git，仓库不会随版本一路变胖。
 *  2. **自建静态 json**：`{"version":..,"apkUrl":..}`，局域网或任意静态托管都能用。
 *  3. **分片清单**：`{"version":..,"parts":[...],"sizeBytes":..}`，给 Gitee 这种
 *     不允许匿名下大文件的托管用。
 *
 * 用 JSON 树模型而不是 dataserialization 插件 —— 只有几个字段，
 * 与 `core:jwgl` 的 KbListParser 保持同一套做法。
 */
object UpdateManifestParser {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        allowTrailingComma = true
    }

    class ParseException(message: String) : IllegalStateException(message)

    fun parse(raw: String): UpdateManifest {
        val text = raw.trim()
        if (text.isEmpty()) throw ParseException("更新源返回了空内容")
        if (text.startsWith("<")) throw ParseException("更新源返回的是网页而不是 JSON（地址填错了？）")

        val root = try {
            json.parseToJsonElement(text) as? JsonObject
        } catch (e: Exception) {
            throw ParseException("更新清单不是合法 JSON：${e.message}")
        } ?: throw ParseException("更新清单结构不符合预期")

        // GitHub Releases
        val tag = root.str("tag_name") ?: root.str("name")
        if (tag != null) {
            val assets = (root["assets"] as? JsonArray).orEmpty()
            val apk = assets.asSequence()
                .mapNotNull { it as? JsonObject }
                .filter { it.str("name")?.endsWith(".apk", ignoreCase = true) == true }
                // 一个 release 里可能同时传了 debug 和 release 两个包（我构建时就会产出两个）。
                // 优先挑名字带 release 的，其次避开 debug，最后才随便挑一个 ——
                // 拿错了会装上一个体积大、带调试工具、名字也不对的包，
                // 而它「能正常装能正常用」，所以没人会发现。
                .minByOrNull { candidate ->
                    val name = candidate.str("name").orEmpty().lowercase()
                    when {
                        "release" in name -> 0
                        "debug" in name -> 2
                        else -> 1
                    }
                }
                ?: throw ParseException("这个 release 里没有 APK 附件")
            return UpdateManifest(
                version = tag,
                notes = root.str("body").orEmpty(),
                apkUrl = apk.str("browser_download_url")
                    ?: throw ParseException("APK 附件缺少下载地址"),
                sizeBytes = apk.long("size") ?: 0L,
                publishedAt = root.str("published_at"),
            )
        }

        // 自建静态 json / 分片清单
        val version = root.str("version")
            ?: throw ParseException("更新清单里没有 version，也没有 GitHub release 的 tag_name")
        val apkUrl = root.str("apkUrl") ?: root.str("apk_url")
        val parts = (root["parts"] as? JsonArray).orEmpty()
            .mapNotNull { element ->
                (element as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            }

        if (apkUrl == null && parts.isEmpty()) {
            throw ParseException("更新清单里既没有 apkUrl 也没有 parts，不知道从哪儿下")
        }

        return UpdateManifest(
            version = version,
            notes = root.str("notes").orEmpty(),
            apkUrl = apkUrl,
            parts = parts,
            sizeBytes = root.long("sizeBytes") ?: 0L,
            publishedAt = root.str("publishedAt"),
        )
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private fun JsonObject.long(key: String): Long? =
        (this[key] as? JsonPrimitive)?.longOrNull

    /** 把 release 正文裁短，弹窗里放不下几千字的 changelog。 */
    fun trimNotes(notes: String, maxChars: Int = 1200): String {
        val cleaned = notes.trim()
        return if (cleaned.length <= maxChars) cleaned else cleaned.take(maxChars) + "…"
    }
}
