package com.shangkele.core.jwgl

/**
 * 注入到 WebView 页面里的 JS。
 *
 * 为什么不用 OkHttp 直接打课表接口：正方 V9 在不同学校的部署路径不统一，
 * 猜端点的成本很高（实测 `xskbcx_cxXsKb.html` 就返回非 JSON）。
 * 换成在**页面内**发起请求有三个好处：
 *
 *  1. 同源请求，Cookie 由浏览器自己带，不用在 Kotlin 侧搬运会话
 *  2. 可以先把页面里出现过的真实课表地址刮出来当候选，命中率远高于硬编码
 *  3. 一旦命中，把真实地址记下来，之后的静默刷新就能走原生 OkHttp
 *
 * 通过 `SKL.onScheduleJson(url, json)` / `SKL.onFetchFailed(detail)` 回传 Kotlin。
 */
object JwglPageScript {

    /** JS 桥接对象名，与 WebView.addJavascriptInterface 的第二个参数一致。 */
    const val BRIDGE_NAME = "SKL"

    /** 课表接口里的判别特征，避免把别的接口响应误当成课表。 */
    private const val KB_MARKER = "kbList"

    /**
     * 安装 XHR 钩子。
     *
     * 用户自己在页面里点「个人课表查询」时，页面会自己发一次课表请求，
     * 我们顺手把那次的 URL 和响应截下来 —— 这是最可靠的一次机会，
     * 因为它一定是官方前端认可的地址和参数。
     *
     * 必须在 `onPageStarted` 注入，晚了页面脚本已经跑完。
     */
    val HOOK_XHR: String = """
        (function () {
          try {
            if (window.__sklHooked) { return 'skip'; }
            window.__sklHooked = true;
            var marker = '$KB_MARKER';
            var origOpen = XMLHttpRequest.prototype.open;
            var origSend = XMLHttpRequest.prototype.send;
            XMLHttpRequest.prototype.open = function (method, url) {
              try { this.__sklUrl = url || ''; } catch (e) {}
              return origOpen.apply(this, arguments);
            };
            XMLHttpRequest.prototype.send = function () {
              var self = this;
              try {
                self.addEventListener('load', function () {
                  try {
                    var body = self.responseText || '';
                    if (body.charAt(0) === '{' && body.indexOf(marker) >= 0) {
                      window.$BRIDGE_NAME.onScheduleJson(self.__sklUrl || '', body);
                    }
                  } catch (e) {}
                });
              } catch (e) {}
              return origSend.apply(this, arguments);
            };
            return 'installed';
          } catch (e) { return 'error:' + e; }
        })();
    """.trimIndent()

    /**
     * 读取当前页面上的「时间线索」：当前第几周、各节几点上课。
     *
     * **刻意只做一件事：把页面可见文本原样带回 Kotlin。**
     * 解析（正则、配对、校验）全部放在 [PageHintsParser] 里用 Kotlin 写，
     * 因为那是整条链路里最容易出错的一环 —— 写在 JS 字符串里没法单测，
     * 出错了只能靠猜。
     *
     * `innerText` 只拿可见文本（不会把 `<script>` 里的代码卷进来），
     * 但页面还在加载或内容被折叠时可能为空，所以短于 200 字时退到 `textContent`。
     */
    val READ_PAGE_HINTS: String = """
        (function () {
          try {
            var body = (document.body && document.body.innerText) || '';
            if (body.length < 200) {
              body = (document.body && document.body.textContent) || body;
            }
            if (body.length > 40000) { body = body.substring(0, 40000); }
            var payload = JSON.stringify({ url: location.href || '', text: body });
            window.$BRIDGE_NAME.onPageHints(payload);
            return 'ok:' + body.length;
          } catch (e) { return 'error:' + e; }
        })();
    """.trimIndent()

    /**
     * 主动抓取课表。
     *
     * 候选顺序：页面里刮出来的真实地址 → 内置候选 → 逐个试，
     * 谁先返回带 `kbList` 的 JSON 就用谁，失败信息一并带回 Kotlin 便于排查。
     */
    fun buildFetchScript(xnm: String, xqm: String): String {
        val candidates = buildList {
            // 页面里出现的 xskbcx 地址优先（正则从 DOM 里刮）
            add("__discover__")
            addAll(JwglEndpoints.SCHEDULE_ENDPOINTS.map { it.removePrefix(JwglEndpoints.BASE_URL) })
        }
        val urlsLiteral = candidates.joinToString(prefix = "[", postfix = "]") { jsString(it) }
        // 注意：这里要的是「裸值」，因为下面已经包在 JS 单引号里了。
        // 早先误用了 jsString() 加双引号，拼出来是 ?gnmkdm="N2151"，请求直接打歪。
        val gnmkdm = JwglEndpoints.GNMKDM_STUDENT_SCHEDULE.replace("'", "")

        return """
            (function () {
              try {
                // 必须在教务系统自己的域名下发起，否则是跨域请求会被浏览器拦掉
                if (location.host.indexOf('jwgl') < 0) {
                  window.$BRIDGE_NAME.onFetchFailed(
                    '当前页面是 ' + location.host + '，请先返回教务系统首页再点导入');
                  return;
                }
                var urls = $urlsLiteral;
                var xnm = ${jsString(xnm)};
                var xqm = ${jsString(xqm)};
                var marker = '$KB_MARKER';

                // 从当前页面里刮出真实课表接口地址，放在候选最前面
                try {
                  var html = (document.documentElement && document.documentElement.innerHTML) || '';
                  var re = /[^"'()\s<>]*(xskbcx|cxXsKb)[^"'()\s<>]*\.html/g;
                  var seen = {}, extra = [], m;
                  while ((m = re.exec(html)) !== null) {
                    var u = m[0];
                    if (!seen[u]) { seen[u] = 1; extra.push(u); }
                  }
                  if (extra.length) { urls = extra.concat(urls); }
                } catch (e) {}

                var tried = [];
                function attempt(i) {
                  if (i >= urls.length) {
                    window.$BRIDGE_NAME.onFetchFailed(tried.join(' | '));
                    return;
                  }
                  if (urls[i] === '__discover__') { attempt(i + 1); return; }
                  var url = urls[i];
                  // 页面里刮出来的地址本来就可能带 gnmkdm（而且是本校正确的那个码），
                  // 已经带了就不能再追加，否则拼出 ?gnmkdm=<本校的码>&gnmkdm=N2151
                  var full = url.indexOf('gnmkdm=') >= 0
                    ? url
                    : url + (url.indexOf('?') >= 0 ? '&' : '?') + 'gnmkdm=$gnmkdm';
                  try {
                    var xhr = new XMLHttpRequest();
                    xhr.open('POST', full, true);
                    xhr.setRequestHeader('Content-Type', 'application/x-www-form-urlencoded;charset=UTF-8');
                    xhr.setRequestHeader('X-Requested-With', 'XMLHttpRequest');
                    xhr.onreadystatechange = function () {
                      if (xhr.readyState !== 4) { return; }
                      var body = (xhr.responseText || '').replace(/^\s+/, '');
                      if (body.charAt(0) === '{' && body.indexOf(marker) >= 0) {
                        window.$BRIDGE_NAME.onScheduleJson(full, body);
                      } else {
                        tried.push(url + ' => HTTP ' + xhr.status);
                        attempt(i + 1);
                      }
                    };
                    xhr.send('xnm=' + xnm + '&xqm=' + xqm + '&kzlx=ck&xsdm=');
                  } catch (e) {
                    tried.push(url + ' => ' + e);
                    attempt(i + 1);
                  }
                }
                attempt(0);
              } catch (e) {
                try { window.$BRIDGE_NAME.onFetchFailed('script error: ' + e); } catch (x) {}
              }
            })();
        """.trimIndent()
    }

    private fun jsString(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
