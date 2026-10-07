package com.shangkele.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 分片清单的解析。
 *
 * 背景：Gitee 实测禁止匿名下载 8MB 以上的文件（16MB 就 `403 large file require login`），
 * 而安装包 40 多 MB —— 只能切成小片分别下载再拼起来。所以清单里多了 `parts`。
 */
class UpdateManifestPartsTest {

    private val part1 = "https://gitee.com/wis314/wisclass/raw/main/release/A.apk.part01"
    private val part2 = "https://gitee.com/wis314/wisclass/raw/main/release/A.apk.part02"

    @Test
    fun `解析分片清单`() {
        val manifest = UpdateManifestParser.parse(
            """
            {
              "version": "0.10.8-w6",
              "notes": "修了点什么",
              "parts": ["$part1", "$part2"],
              "sizeBytes": 43792860
            }
            """.trimIndent(),
        )

        assertEquals("0.10.8-w6", manifest.version)
        assertEquals(listOf(part1, part2), manifest.parts)
        assertEquals(43792860L, manifest.sizeBytes)
        assertTrue(manifest.isChunked)
        assertEquals(listOf(part1, part2), manifest.sources)
        assertNull("分片清单里没有整包地址", manifest.apkUrl)
    }

    @Test
    fun `只有整包地址时行为不变`() {
        val manifest = UpdateManifestParser.parse(
            """{"version":"0.10.8-w6","apkUrl":"https://example.com/a.apk","sizeBytes":100}""",
        )

        assertFalse(manifest.isChunked)
        assertEquals(listOf("https://example.com/a.apk"), manifest.sources)
    }

    /**
     * 两者都有时**优先分片**：Gitee 上那份整包匿名根本下不动，
     * 先试它只会白白卡住一次。
     */
    @Test
    fun `两者都有时优先用分片`() {
        val manifest = UpdateManifestParser.parse(
            """{"version":"1","apkUrl":"https://example.com/a.apk","parts":["$part1"],"sizeBytes":1}""",
        )
        assertEquals(listOf(part1), manifest.sources)
    }

    @Test
    fun `分片里的空串被丢掉`() {
        val manifest = UpdateManifestParser.parse(
            """{"version":"1","parts":["$part1","","  ","$part2"],"sizeBytes":1}""",
        )
        assertEquals(listOf(part1, part2), manifest.parts)
    }

    /**
     * 两个都没有必须**报错**，不能返回一个空清单 ——
     * 那样界面只会说「已是最新版」，而真实原因是清单格式不对。
     */
    @Test
    fun `既没有 apkUrl 也没有 parts 要报错`() {
        try {
            UpdateManifestParser.parse("""{"version":"1","sizeBytes":1}""")
            fail("应该抛 ParseException")
        } catch (e: UpdateManifestParser.ParseException) {
            assertTrue("错误信息要说清缺什么：${e.message}", e.message!!.contains("apkUrl"))
            assertTrue("也要提到 parts：${e.message}", e.message!!.contains("parts"))
        }
    }

    @Test
    fun `分片清单缺 sizeBytes 时按 0 处理而不是崩`() {
        val manifest = UpdateManifestParser.parse("""{"version":"1","parts":["$part1"]}""")
        assertEquals(0L, manifest.sizeBytes)
    }
}
