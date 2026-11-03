package org.mewx.wenku8_flutter

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The official site is behind a Cloudflare browser check, so plain HTTP is
 * answered with the interstitial. The original client reads those pages in a
 * WebView after the user completes the check once; this does the same and
 * shares the resulting cookie jar across requests.
 */
internal class WebsiteBrowser(
    private val context: Context,
    /**
     * A detached WebView never counts as visible, and Chromium then throttles
     * its timers so hard that the site's browser check never finishes. The view
     * is therefore parked as a one-pixel child of the running activity.
     */
    private val activity: () -> android.app.Activity? = { null },
) {
    private companion object {
        const val TAG = "WenkuBrowser"
        val CHALLENGE_MARKERS = listOf(
            "Just a moment",
            "cf-browser-verification",
            "Enable JavaScript and cookies to continue",
            "challenge-stage",
        )
    }

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var view: WebView? = null
    /** Pending teardown of the window-attached view. */
    private var idle: Runnable? = null

    /** Cookies the WebView holds, or null when nothing was stored. */
    fun cookies(): String? {
        val net = CookieManager.getInstance().getCookie("https://www.wenku8.net/")
        val cc = CookieManager.getInstance().getCookie("https://www.wenku8.cc/")
        val merged = LinkedHashMap<String, String>()
        for (field in "${net ?: ""}; ${cc ?: ""}".split(';')) {
            val pair = field.trim().split('=', limit = 2)
            if (pair.size == 2 && pair[0].isNotEmpty()) merged[pair[0]] = pair[1]
        }
        if (merged.isEmpty()) return null
        return "cookies-v2:" + merged.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    fun hasLogin(): Boolean {
        val stored = cookies() ?: return false
        return stored.contains("jieqiUserInfo") || stored.contains("jieqiUserName")
    }

    fun clear() {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
    }

    /**
     * Load [url] in the shared WebView and return the rendered HTML. Blocks the
     * calling worker thread; every caller is already off the main thread.
     */
    fun fetch(url: String, timeoutMillis: Long = 25_000): String {
        require(url.startsWith("https://www.wenku8.")) { "Unsupported website address" }
        Log.d(TAG, "fetch $url")
        // A view parked in the window is only needed while a page is loading;
        // keeping it around between requests would leave a full-screen view in
        // the hierarchy for the whole session.
        idle?.let { main.removeCallbacks(it) }
        try {
            val origin = originOf(url)
            if (!url.startsWith(origin) || url.length <= origin.length) {
                return navigate(url, timeoutMillis)
            }
            // Every navigation is challenged, but a request issued from inside a
            // page that already passed the check is not. Land on the root once,
            // then read the deep link with the page's own fetch().
            navigate(origin, timeoutMillis)
            return fetchInPage(url, timeoutMillis * 2)
        } finally {
            scheduleRelease()
        }
    }

    /** Tear the window-attached view down shortly after the last request. */
    private fun scheduleRelease() {
        val task = Runnable {
            main.post {
                view?.let { web ->
                    detach(web)
                    web.destroy()
                }
                view = null
                idle = null
            }
        }
        idle = task
        main.postDelayed(task, 3_000)
    }

    /** Read [url] from inside the loaded document, same origin as the site. */
    private fun fetchInPage(url: String, timeoutMillis: Long): String {
        val latch = CountDownLatch(1)
        val html = AtomicReference<String?>(null)
        val failure = AtomicReference<String?>(null)
        val deadline = System.currentTimeMillis() + timeoutMillis
        val script = "(function(){" +
            "var u=" + org.json.JSONObject.quote(url) + ";" +
            "fetch(u,{credentials:'include'}).then(function(r){" +
            "return r.arrayBuffer().then(function(b){" +
            "var t;" +
            "try{t=new TextDecoder('gbk').decode(b);}catch(e){t='';}" +
            // The site mixes GBK and UTF-8 pages; replacement characters mean
            // the guess was wrong, so the same bytes are decoded again.
            "if(!t||t.indexOf('\\uFFFD')>=0){try{t=new TextDecoder('utf-8').decode(b);}catch(e){}}" +
            "window.__wenkuRead=JSON.stringify({ok:r.ok,s:r.status,t:t});" +
            "});" +
            "}).catch(function(e){window.__wenkuRead=JSON.stringify({" +
            "ok:false,s:0,t:'',e:String(e)});});" +
            "return 1;})()"
        main.post {
            val web = view
            if (web == null) {
                failure.set("官网页面尚未就绪，请重试")
                latch.countDown()
                return@post
            }
            web.evaluateJavascript(script) {
                pollRead(web, deadline, html, failure, latch)
            }
        }
        if (!latch.await(timeoutMillis + 5_000, TimeUnit.MILLISECONDS)) {
            throw WebCatalogFailure("WEB_TIMEOUT", "官网页面加载超时，请重试")
        }
        failure.get()?.let { throw WebCatalogFailure("WEB_UNAVAILABLE", it) }
        val body = html.get()
        if (body.isNullOrBlank()) {
            throw WebCatalogFailure("WEB_UNAVAILABLE", "官网返回空页面，请稍后重试")
        }
        return body
    }

    /** Wait for the in-page fetch above to publish its result. */
    private fun pollRead(
        web: WebView,
        deadline: Long,
        html: AtomicReference<String?>,
        failure: AtomicReference<String?>,
        latch: CountDownLatch,
    ) {
        web.evaluateJavascript("window.__wenkuRead||''") { encoded ->
            val snapshot = decodeJsString(encoded)
            if (snapshot.isNotEmpty()) {
                web.evaluateJavascript("window.__wenkuRead=''") { }
                val ok = field(snapshot, "ok") == "true"
                val status = field(snapshot, "s").orEmpty()
                val text = field(snapshot, "t").orEmpty()
                Log.d(TAG, "read ok=$ok status=$status len=${text.length}")
                if (ok && text.length > 512) {
                    html.set(text)
                } else {
                    failure.set("官网暂时拒绝此页请求（$status），请稍后重试")
                }
                latch.countDown()
                return@evaluateJavascript
            }
            if (System.currentTimeMillis() >= deadline) {
                failure.set("官网页面加载超时，请重试")
                latch.countDown()
            } else {
                main.postDelayed(
                    { pollRead(web, deadline, html, failure, latch) },
                    400,
                )
            }
        }
    }

    /** Load [url] and return its rendered document once the check is done. */
    private fun navigate(url: String, timeoutMillis: Long): String {
        val latch = CountDownLatch(1)
        val html = AtomicReference<String?>(null)
        val failure = AtomicReference<String?>(null)
        val deadline = System.currentTimeMillis() + timeoutMillis
        main.post {
            try {
                val web = ensureView()
                web.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest,
                    ): Boolean {
                        // Only main-frame navigation is ours to police; the
                        // browser check lives in an iframe on another host and
                        // cancelling that frame leaves the page stuck.
                        if (!request.isForMainFrame) return false
                        if (!official(request.url.host)) return true
                        if (request.url.scheme == "http") {
                            view.loadUrl(
                                request.url.toString().replaceFirst("http://", "https://"),
                            )
                            return true
                        }
                        return false
                    }

                    override fun onPageFinished(view: WebView, finishedUrl: String?) {
                        poll(view, deadline, html, failure, latch)
                    }
                }
                // A request with no referer looks like a bot to the site's edge,
                // so every load claims the site itself as its origin.
                web.loadUrl(url, mapOf("Referer" to originOf(url)))
            } catch (error: Exception) {
                failure.set(error.message ?: "WebView unavailable")
                latch.countDown()
            }
        }
        if (!latch.await(timeoutMillis + 5_000, TimeUnit.MILLISECONDS)) {
            throw WebCatalogFailure("WEB_TIMEOUT", "官网页面加载超时，请重试")
        }
        Log.d(TAG, "done len=${html.get()?.length}")
        failure.get()?.let { throw WebCatalogFailure("WEB_UNAVAILABLE", it) }
        val body = html.get()
        if (body.isNullOrBlank()) {
            throw WebCatalogFailure("WEB_UNAVAILABLE", "官网返回空页面，请稍后重试")
        }
        return body
    }

    /**
     * `onPageFinished` also fires for the interstitial the site serves while its
     * JavaScript check runs, so keep re-reading the document until the real page
     * replaces it.
     */
    private fun poll(
        view: WebView,
        deadline: Long,
        html: AtomicReference<String?>,
        failure: AtomicReference<String?>,
        latch: CountDownLatch,
    ) {
        view.evaluateJavascript(
            "(function(){var d=document;return JSON.stringify({" +
                "t:(d.title||'')," +
                "c:!!d.querySelector('#content')," +
                "h:(d.documentElement.outerHTML||'')});})()",
        ) { encoded ->
            val snapshot = decodeJsString(encoded)
            val title = field(snapshot, "t").orEmpty()
            val body = field(snapshot, "h").orEmpty()
            val hasContent = field(snapshot, "c") == "true"
            Log.d(TAG, "poll title=$title len=${body.length} content=$hasContent")
            when {
                settled(body) -> {
                    html.set(body)
                    latch.countDown()
                }
                // Cloudflare keeps its orchestration script on the real page too,
                // so a late read of a challenge-free document is still usable.
                System.currentTimeMillis() >= deadline && usable(body) -> {
                    html.set(body)
                    latch.countDown()
                }
                System.currentTimeMillis() >= deadline -> {
                    failure.set("官网验证未完成，请在官网登录页完成验证后重试")
                    latch.countDown()
                }
                else -> main.postDelayed(
                    { poll(view, deadline, html, failure, latch) },
                    900,
                )
            }
        }
    }

    /** Read one top-level field out of the JSON snapshot above. */
    private fun field(snapshot: String, name: String): String? {
        if (snapshot.isEmpty()) return null
        return try {
            val json = org.json.JSONObject(snapshot)
            if (!json.has(name)) null else json.get(name).toString()
        } catch (_: Exception) {
            null
        }
    }

    private fun originOf(url: String): String =
        url.substring(0, url.indexOf('/', "https://".length)) + "/"

    private fun settled(body: String): Boolean =
        usable(body) && body.length > 512

    /** A document that is no longer the browser check, whatever its size. */
    private fun usable(body: String): Boolean =
        body.isNotEmpty() && CHALLENGE_MARKERS.none { body.contains(it) }

    private fun official(host: String?): Boolean =
        host?.lowercase() in setOf(
            "wenku8.net", "www.wenku8.net", "wenku8.cc", "www.wenku8.cc",
        )

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureView(): WebView = view ?: createView().also { web ->
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.databaseEnabled = true
        // A regular mobile UA keeps the site's own layout and its CF check.
        web.settings.userAgentString = web.settings.userAgentString
            .replace("; wv", "")
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        attach(web)
        view = web
    }

    private fun createView(): WebView {
        val host = activity()
        Log.d(TAG, "createView activity=${host?.javaClass?.simpleName}")
        return if (host == null) WebView(context) else WebView(host)
    }

    /** Park the view in the activity window so Chromium keeps it unthrottled. */
    private fun attach(web: WebView) {
        val host = activity() ?: run { Log.d(TAG, "attach: no activity"); return }
        val root = host.window?.decorView as? android.view.ViewGroup ?: return
        if (web.parent != null) return
        try {
            // The check renders a widget that needs a real viewport, and
            // Chromium throttles any view it believes is hidden or detached, so
            // the view has to live in the window. Everything about it is tuned
            // so it can never take input away from the app: it sits under the
            // Flutter surface, it is nearly transparent, and it refuses touches.
            val full = android.view.ViewGroup.LayoutParams.MATCH_PARENT
            val params = if (root is android.widget.FrameLayout) {
                android.widget.FrameLayout.LayoutParams(full, full)
            } else {
                android.view.ViewGroup.LayoutParams(full, full)
            }
            web.alpha = 0.01f
            web.isClickable = false
            web.isFocusable = false
            web.isFocusableInTouchMode = false
            web.setOnTouchListener { _, _ -> true }
            root.addView(web, 0, params)
            Log.d(TAG, "attached shown=${web.isShown()} winVis=${web.windowVisibility}")
        } catch (_: Exception) {
            // Detached rendering still works, just slower; the timeout covers it.
        }
    }

    private fun detach(web: WebView) {
        (web.parent as? android.view.ViewGroup)?.removeView(web)
    }

    /** `evaluateJavascript` hands back a JSON-encoded string literal. */
    private fun decodeJsString(encoded: String?): String {
        if (encoded == null || encoded == "null") return ""
        return try {
            org.json.JSONTokener(encoded).nextValue() as? String ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    fun close() {
        main.post {
            view?.let { web ->
                detach(web)
                web.destroy()
            }
            view = null
        }
    }
}
