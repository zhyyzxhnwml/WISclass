package com.shangkele.core.jwgl

import android.webkit.CookieManager

/**
 * 从 WebView 的 CookieManager 里取出教务系统的会话。
 *
 * 必须在主线程调用（CookieManager 的要求），Compose 的点击回调天然在主线程。
 */
object JwglWebCookies {

    /** @return `JSESSIONID=...; route=...`；未登录时返回 null */
    fun read(): String? =
        CookieManager.getInstance()
            .getCookie(JwglEndpoints.BASE_URL)
            ?.takeIf { it.isNotBlank() }

    /** 退出登录时清掉 WebView 侧的 Cookie。 */
    fun clear() {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
    }
}
