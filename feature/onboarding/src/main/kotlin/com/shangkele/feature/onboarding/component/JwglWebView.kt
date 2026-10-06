package com.shangkele.feature.onboarding.component

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.shangkele.core.jwgl.JwglApiClient
import com.shangkele.core.jwgl.JwglEndpoints
import com.shangkele.core.jwgl.JwglPageScript

/**
 * 持有 WebView 引用，供外部（比如「开始导入」按钮）执行注入脚本。
 */
class JwglWebController internal constructor() {

    internal var webView: WebView? = null

    fun evaluate(script: String) {
        webView?.evaluateJavascript(script, null)
    }

    fun canGoBack(): Boolean = webView?.canGoBack() == true

    fun goBack() {
        webView?.goBack()
    }

    fun currentUrl(): String? = webView?.url
}

/**
 * 教务系统登录用的 WebView，同时承担**课表抓取**。
 *
 * 这是认证主通道（docs/03 §二 的 L1）：用户在官方页面里自己输账号密码 / 扫码 /
 * 走统一身份认证，验证码、SSO、前端改版都影响不到这条通道。
 *
 * 抓取也放在页面内做，理由见 [JwglPageScript]：同源请求自带 Cookie，
 * 接口地址还能从页面里刮出来 —— 比在 Kotlin 侧猜端点可靠得多。
 */
@SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
@Composable
fun JwglWebView(
    controller: JwglWebController,
    modifier: Modifier = Modifier,
    onProgressChanged: (Int) -> Unit = {},
    onPageFinishedWith: (String) -> Unit = {},
    onScheduleJson: (url: String, json: String) -> Unit = { _, _ -> },
    onFetchFailed: (detail: String) -> Unit = {},
    /** 页面上的时间线索（当前周次 / 作息时间）原始载荷，解析在 Kotlin 侧做 */
    onPageHints: (payload: String) -> Unit = {},
) {
    val progressCallback by rememberUpdatedState(onProgressChanged)
    val pageFinishedCallback by rememberUpdatedState(onPageFinishedWith)
    val scheduleCallback by rememberUpdatedState(onScheduleJson)
    val failedCallback by rememberUpdatedState(onFetchFailed)
    val hintsCallback by rememberUpdatedState(onPageHints)
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).also { view ->
                controller.webView = view
                view.layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                view.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    loadWithOverviewMode = true
                    useWideViewPort = true
                    // 正方首页的菜单是 window.open 开的，不开这个点不动
                    setSupportMultipleWindows(true)
                    javaScriptCanOpenWindowsAutomatically = true
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    userAgentString = JwglApiClient.USER_AGENT
                    cacheMode = WebSettings.LOAD_DEFAULT
                }

                CookieManager.getInstance().apply {
                    setAcceptCookie(true)
                    setAcceptThirdPartyCookies(view, true)
                }

                // JS 桥的回调在 WebView 的线程上，统一 post 回主线程再碰 UI
                view.addJavascriptInterface(
                    JsBridge(
                        onJson = { url, json -> mainHandler.post { scheduleCallback(url, json) } },
                        onFailed = { detail -> mainHandler.post { failedCallback(detail) } },
                        onHints = { payload -> mainHandler.post { hintsCallback(payload) } },
                    ),
                    JwglPageScript.BRIDGE_NAME,
                )

                view.webViewClient = object : WebViewClient() {

                    override fun onPageStarted(webView: WebView, url: String, favicon: android.graphics.Bitmap?) {
                        // 必须比页面自己的脚本更早，否则钩不到它的课表请求
                        webView.evaluateJavascript(JwglPageScript.HOOK_XHR, null)
                    }

                    override fun shouldOverrideUrlLoading(
                        webView: WebView,
                        request: WebResourceRequest,
                    ): Boolean {
                        val url = request.url.toString()

                        // 扫码 / 支付类深链交给对应的 App
                        if (url.startsWith("weixin://") ||
                            url.startsWith("alipays://") ||
                            url.startsWith("alipay://")
                        ) {
                            openExternal(webView, url)
                            return true
                        }

                        // 只在校园域名内导航。带着教务会话的 WebView 不该到处跑，
                        // 站外链接一律丢给系统浏览器。
                        // 本校域名来自本机构建配置，代码里没有学校信息（见 JwglEndpoints.isSchoolHost）。
                        val host = request.url.host.orEmpty()
                        if (host.isNotEmpty() && !JwglEndpoints.isSchoolHost(host)) {
                            openExternal(webView, url)
                            return true
                        }
                        return false
                    }

                    override fun onPageFinished(webView: WebView, url: String) {
                        // 不 flush 的话 Cookie 可能还没落盘，读出来会是空
                        CookieManager.getInstance().flush()
                        pageFinishedCallback(url)
                    }
                }

                view.webChromeClient = object : WebChromeClient() {
                    override fun onProgressChanged(webView: WebView, newProgress: Int) {
                        progressCallback(newProgress)
                    }

                    /**
                     * 弹窗处理。
                     *
                     * ⚠️ 这里曾经写成 `transport.webView = view`（把主 WebView 交给弹窗），
                     * 结果点「个人课表查询」时主 WebView 被弹窗机制接管、当前页面被顶掉，
                     * 表现为「点课表就退出去了」。正确做法是用一个临时 WebView 承接，
                     * 拿到真实地址后再让主 WebView 加载。
                     */
                    override fun onCreateWindow(
                        webView: WebView,
                        isDialog: Boolean,
                        isUserGesture: Boolean,
                        resultMsg: Message,
                    ): Boolean {
                        val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false

                        val temp = WebView(webView.context)
                        temp.webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                tempView: WebView,
                                request: WebResourceRequest,
                            ): Boolean {
                                webView.loadUrl(request.url.toString())
                                tempView.destroy()
                                return true
                            }
                        }
                        transport.webView = temp
                        resultMsg.sendToTarget()
                        return true
                    }
                }

                view.loadUrl(JwglEndpoints.LOGIN_PAGE)
            }
        },
    )

    DisposableEffect(Unit) {
        onDispose {
            controller.webView?.let { view ->
                (view.parent as? ViewGroup)?.removeView(view)
                view.stopLoading()
                view.destroy()
            }
            controller.webView = null
        }
    }
}

private fun openExternal(webView: WebView, url: String) {
    runCatching {
        webView.context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
}

/** 暴露给页面 JS 的桥。方法名与 [JwglPageScript] 里调用的保持一致。 */
private class JsBridge(
    private val onJson: (String, String) -> Unit,
    private val onFailed: (String) -> Unit,
    private val onHints: (String) -> Unit,
) {

    @JavascriptInterface
    fun onScheduleJson(url: String, json: String) {
        onJson(url, json)
    }

    @JavascriptInterface
    fun onFetchFailed(detail: String) {
        onFailed(detail)
    }

    @JavascriptInterface
    fun onPageHints(payload: String) {
        onHints(payload)
    }
}
