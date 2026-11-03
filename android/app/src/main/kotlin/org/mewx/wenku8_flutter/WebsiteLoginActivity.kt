package org.mewx.wenku8_flutter

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * The official site sits behind a Cloudflare browser check, so a plain
 * HttpURLConnection is answered with the interstitial instead of a page.
 * The original client completes that check in a real WebView and then keeps
 * the resulting cookies; this activity does the same and hands the jar back.
 */
class WebsiteLoginActivity : Activity() {
    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        val host = intent.getStringExtra("host") ?: "net"
        val origin = if (host == WenkuWebsite.CC.suffix) WenkuWebsite.CC.origin
        else WenkuWebsite.NET.origin
        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        web = WebView(this).apply {
            setBackgroundColor(Color.WHITE)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            // The site is a fixed-width 2000s page without a viewport meta
            // tag, so it has to be scaled down to fit the phone screen.
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.userAgentString = settings.userAgentString
                .replace("; wv", "")
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest,
                ): Boolean {
                    val next = request.url
                    // Sub-frames carry the browser check and site widgets; only
                    // top-level navigation decides where the session may go.
                    if (!request.isForMainFrame) return false
                    // Keep the session on the two official mirrors only; a
                    // redirect elsewhere must not receive the account cookies.
                    if (next.host?.lowercase() !in setOf(
                            "wenku8.net", "www.wenku8.net",
                            "wenku8.cc", "www.wenku8.cc",
                        )
                    ) return true
                    // The legacy markup posts to plain http, which Android
                    // blocks; the same page is served over https.
                    if (next.scheme == "http") {
                        view.loadUrl(next.toString().replaceFirst("http://", "https://"))
                        return true
                    }
                    if (next.path?.contains("login.php") == true &&
                        next.getQueryParameter("do") == null &&
                        authenticated()
                    ) {
                        finishWithCookies()
                        return true
                    }
                    return false
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    super.onPageFinished(view, url)
                    // The site sets its session while rendering a normal page.
                    if (authenticated() && url?.contains("login.php") != true) {
                        finishWithCookies()
                    }
                }
            }
        }
        // The check is served from a sub-resource, so third-party cookies must
        // be allowed on the view itself once it exists.
        cookies.setAcceptThirdPartyCookies(web, true)
        setContentView(web)
        // A deep link is only used by the diagnostic build; the normal path is
        // the sign-in form.
        web.loadUrl(intent.getStringExtra("url") ?: (origin.toString() + "login.php"))
    }

    private fun authenticated(): Boolean {
        val cookies = CookieManager.getInstance()
        val cookie = (cookies.getCookie("https://www.wenku8.net/") ?: "") + ";" +
            (cookies.getCookie("https://www.wenku8.cc/") ?: "")
        return cookie.contains("jieqiUserInfo") || cookie.contains("jieqiUserName")
    }

    private fun finishWithCookies() {
        val cookies = CookieManager.getInstance()
        val net = cookies.getCookie("https://www.wenku8.net/") ?: ""
        val cc = cookies.getCookie("https://www.wenku8.cc/") ?: ""
        val merged = LinkedHashMap<String, String>()
        for (field in "$net; $cc".split(';')) {
            val pair = field.trim().split('=', limit = 2)
            if (pair.size == 2 && pair[0].isNotEmpty()) merged[pair[0]] = pair[1]
        }
        val stored = if (merged.isEmpty()) "" else
            "cookies-v2:" + merged.entries.joinToString("; ") { "${it.key}=${it.value}" }
        setResult(
            if (stored.isEmpty()) RESULT_CANCELED else RESULT_OK,
            Intent().putExtra("cookies", stored),
        )
        web.destroy()
        finish()
    }

    override fun onBackPressed() {
        setResult(RESULT_CANCELED)
        web.destroy()
        finish()
    }
}
