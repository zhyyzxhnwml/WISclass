package com.shangkele.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 更新清单解析测试。
 *
 * 关键的一条：**解析失败必须抛出来**，不能悄悄返回一个空清单 ——
 * 那样界面只会说「已是最新版」，而真实原因是地址填错了。
 */
class UpdateManifestParserTest {

    @Test
    fun `解析 GitHub Releases 响应`() {
        val json = """
            {
              "tag_name": "0.10.1-w6",
              "name": "0.10.1-w6",
              "body": "修了照片时间轴对齐",
              "published_at": "2026-10-06T13:00:00Z",
              "assets": [
                { "name": "app-debug.apk", "size": 999, "browser_download_url": "https://example.com/debug.apk" },
                { "name": "ShangKeLe-0.10.1-w6-release.apk", "size": 43670000,
                  "browser_download_url": "https://example.com/release.apk" }
              ]
            }
        """.trimIndent()

        val m = UpdateManifestParser.parse(json)
        assertEquals("0.10.1-w6", m.version)
        assertEquals("修了照片时间轴对齐", m.notes)
        assertEquals(43_670_000L, m.sizeBytes)
        assertEquals("https://example.com/release.apk", m.apkUrl)
        assertTrue(m.sizeLabel().endsWith("MB"))
    }

    @Test
    fun `附件后缀大写也认`() {
        val json = """{"tag_name":"1.0","assets":[{"name":"app.APK","size":1,"browser_download_url":"u"}]}"""
        assertEquals("1.0", UpdateManifestParser.parse(json).version)
    }

    @Test
    fun `同时有 debug 与 release 包时挑 release`() {
        val json = """
            {"tag_name":"1.0","assets":[
              {"name":"ShangKeLe-1.0-debug.apk","size":1,"browser_download_url":"debug"},
              {"name":"ShangKeLe-1.0-release.apk","size":2,"browser_download_url":"release"}
            ]}
        """.trimIndent()
        assertEquals("release", UpdateManifestParser.parse(json).apkUrl)
    }

    @Test
    fun `只有 debug 包时也要能用而不是报错`() {
        val json = """{"tag_name":"1.0","assets":[{"name":"app-debug.apk","size":1,"browser_download_url":"debug"}]}"""
        assertEquals("debug", UpdateManifestParser.parse(json).apkUrl)
    }

    @Test
    fun `解析自建静态清单`() {
        val json = """{"version":"0.10.1-w6","notes":"本地测试","apkUrl":"http://192.168.0.3:8080/app.apk","sizeBytes":123}"""
        val m = UpdateManifestParser.parse(json)
        assertEquals("0.10.1-w6", m.version)
        assertEquals("http://192.168.0.3:8080/app.apk", m.apkUrl)
        assertEquals(123L, m.sizeBytes)
    }

    @Test
    fun `release 里没有 APK 时要报错而不是返回空`() {
        val json = """{"tag_name":"1.0","assets":[{"name":"notes.txt","size":1,"browser_download_url":"u"}]}"""
        val e = runCatching { UpdateManifestParser.parse(json) }.exceptionOrNull()
        assertTrue(e is UpdateManifestParser.ParseException)
    }

    @Test
    fun `地址填成网页时要给出能看懂的原因`() {
        val e = runCatching { UpdateManifestParser.parse("<!DOCTYPE html><html>404</html>") }.exceptionOrNull()
        assertTrue(e is UpdateManifestParser.ParseException)
        assertTrue(e!!.message!!.contains("网页"))
    }

    @Test
    fun `空响应与非法 JSON 都要抛错`() {
        assertTrue(runCatching { UpdateManifestParser.parse("") }.exceptionOrNull() is UpdateManifestParser.ParseException)
        assertTrue(runCatching { UpdateManifestParser.parse("{oops") }.exceptionOrNull() is UpdateManifestParser.ParseException)
    }

    @Test
    fun `changelog 会被裁短`() {
        val long = "a".repeat(3000)
        assertEquals(1201, UpdateManifestParser.trimNotes(long).length)
        assertEquals("短说明", UpdateManifestParser.trimNotes("  短说明  "))
    }
}
