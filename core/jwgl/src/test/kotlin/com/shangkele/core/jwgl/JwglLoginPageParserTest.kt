package com.shangkele.core.jwgl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JwglLoginPageParserTest {

    private fun page(vararg hiddenFields: String) = """
        <!DOCTYPE html>
        <html>
        <head><title>教学管理信息服务平台</title></head>
        <body>
        <form id="loginForm" action="/jwglxt/xtgl/login_slogin.html?time=1790000000000" method="post">
            ${hiddenFields.joinToString("\n            ")}
            <input type="text" id="yhm" name="yhm" placeholder="用户名" />
            <input type="password" id="mm" name="mm" />
            <button type="submit">登 录</button>
        </form>
        <a href="/jwglxt/xtgl/login_logoutAccount.html">忘记密码了？</a>
        </body>
        </html>
    """.trimIndent()

    @Test
    fun `解析标准登录页`() {
        val info = JwglLoginPageParser.parse(
            page(
                """<input type="hidden" id="csrftoken" name="csrftoken" value="a1b2c3d4-1111-2222,abcdef123456" />""",
                """<input type="hidden" id="mmsfjm" name="mmsfjm" value="1" />""",
                """<input type="hidden" id="yzcskz" name="yzcskz" value="3" />""",
                """<input type="hidden" id="xxdm" name="xxdm" value="12345" />""",
            ),
        )

        assertEquals("a1b2c3d4-1111-2222,abcdef123456", info.csrfToken)
        assertTrue(info.passwordEncrypted)
        assertEquals(3, info.captchaTriggerCount)
        assertEquals("12345", info.schoolCode)
        assertFalse(info.hasCaptcha)
        assertTrue(info.canAttemptNativeLogin)
    }

    @Test
    fun `属性顺序颠倒也能解析`() {
        val info = JwglLoginPageParser.parse(
            page("""<input value="token-reversed" name="csrftoken" id="csrftoken" type="hidden" />"""),
        )
        assertEquals("token-reversed", info.csrfToken)
    }

    @Test
    fun `单引号属性也能解析`() {
        val info = JwglLoginPageParser.parse(
            page("""<input type='hidden' id='csrftoken' value='single-quoted' />"""),
        )
        assertEquals("single-quoted", info.csrfToken)
    }

    @Test
    fun `出现验证码时必须回退 WebView`() {
        val info = JwglLoginPageParser.parse(
            page(
                """<input type="hidden" id="csrftoken" value="t" />""",
                """<img id="kaptcha" src="/jwglxt/kaptcha?time=1" />""",
            ),
        )
        assertTrue(info.hasCaptcha)
        assertFalse(info.canAttemptNativeLogin)
    }

    @Test
    fun `字段写在脚本里也能取到`() {
        val html = """
            <html><body>
            <script type="text/javascript">
                var csrftoken = "script-token";
                var xxdm = '12345';
            </script>
            </body></html>
        """.trimIndent()

        val info = JwglLoginPageParser.parse(html)
        assertEquals("script-token", info.csrfToken)
        assertEquals("12345", info.schoolCode)
    }

    @Test
    fun `缺字段时不抛异常且不能走原生登录`() {
        val info = JwglLoginPageParser.parse("<html><body>升级提示</body></html>")
        assertNull(info.csrfToken)
        assertFalse(info.passwordEncrypted)
        assertNull(info.captchaTriggerCount)
        assertFalse(info.canAttemptNativeLogin)
    }

    @Test
    fun `mmsfjm 为 0 表示不加密`() {
        val info = JwglLoginPageParser.parse(
            page(
                """<input type="hidden" id="csrftoken" value="t" />""",
                """<input type="hidden" id="mmsfjm" value="0" />""",
            ),
        )
        assertFalse(info.passwordEncrypted)
    }
}
