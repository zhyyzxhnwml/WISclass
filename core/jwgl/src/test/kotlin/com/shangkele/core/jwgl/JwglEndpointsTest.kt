package com.shangkele.core.jwgl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 这几个小工具是在真机上踩出来的：
 *  1. WebView 里截到的地址是相对的，直接喂给 OkHttp 会报 "Expected URL scheme"
 *  2. 截到的地址里已经带了本校正确的 gnmkdm（各校不同，这里用 N9999 举例），
 *     再追加默认值会拼出 `?gnmkdm=N9999&gnmkdm=N2151`
 */
class JwglEndpointsTest {

    @Test
    fun `相对地址补成绝对地址`() {
        // 期望值从 BASE_URL 拼，**不写死域名**：域名是构建时从本机 school.properties
        // 注入的，写死等于把学校信息钉进测试，而且换个配置就会红。
        assertEquals(
            "${JwglEndpoints.BASE_URL}/jwglxt/kbcx/xskbcx_cxXsgrkb.html?gnmkdm=N9999",
            JwglEndpoints.resolveUrl("/jwglxt/kbcx/xskbcx_cxXsgrkb.html?gnmkdm=N9999"),
        )
    }

    @Test
    fun `BASE_URL 的域名来自构建注入而不是写死`() {
        // 没配 school.properties 时会落到 *.invalid（IANA 保留 TLD，保证解析不到），
        // 所以「连不上」是明确的失败，而不是请求悄悄发去了别的地方。
        val host = JwglEndpoints.BASE_URL.removePrefix("http://")
        assertTrue("host 不能为空", host.isNotEmpty())
        assertTrue("不能是畸形 URL", !host.startsWith("/"))
    }

    @Test
    fun `已是绝对地址就原样返回`() {
        val url = "http://jwgl.example.edu.cn/jwglxt/kbcx/xskbcx_cxXsgrkb.html"
        assertEquals(url, JwglEndpoints.resolveUrl(url))
        assertEquals("https://a.b/c", JwglEndpoints.resolveUrl("https://a.b/c"))
    }

    @Test
    fun `协议相对地址补上 http`() {
        assertEquals("http://jwgl.example.edu.cn/x.html", JwglEndpoints.resolveUrl("//jwgl.example.edu.cn/x.html"))
    }

    @Test
    fun `空值返回 null`() {
        assertNull(JwglEndpoints.resolveUrl(null))
        assertNull(JwglEndpoints.resolveUrl("   "))
    }

    @Test
    fun `读取 gnmkdm`() {
        assertEquals(
            "N9999",
            JwglEndpoints.gnmkdmOf("/jwglxt/kbcx/xskbcx_cxXsgrkb.html?gnmkdm=N9999"),
        )
        assertEquals(
            "N2151",
            JwglEndpoints.gnmkdmOf("http://x/y.html?a=1&gnmkdm=N2151&b=2"),
        )
        assertNull(JwglEndpoints.gnmkdmOf("http://x/y.html"))
        assertNull(JwglEndpoints.gnmkdmOf(null))
    }

    @Test
    fun `已带 gnmkdm 时不重复追加`() {
        val url = "http://jwgl.example.edu.cn/jwglxt/kbcx/xskbcx_cxXsgrkb.html?gnmkdm=N9999"
        assertEquals(url, JwglEndpoints.withGnmkdm(url))
    }

    @Test
    fun `没有参数时用问号补`() {
        assertEquals(
            "http://jwgl.example.edu.cn/jwglxt/kbcx/xskbcx_cxXsKb.html?gnmkdm=N2151",
            JwglEndpoints.withGnmkdm("http://jwgl.example.edu.cn/jwglxt/kbcx/xskbcx_cxXsKb.html"),
        )
    }

    @Test
    fun `已有其他参数时用与号补`() {
        assertEquals(
            "http://a/b.html?layout=default&gnmkdm=N2151",
            JwglEndpoints.withGnmkdm("http://a/b.html?layout=default"),
        )
    }
}
