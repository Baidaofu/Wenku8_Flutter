package org.mewx.wenku8_flutter

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Base64
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import org.mewx.wenku8.api.TokenManager
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.zip.GZIPInputStream

/** Bounded I/O workers; guest content requests cannot mutate an account session. */
internal class Wenku8Bridge(context: Context, private val postToMain: (Runnable) -> Unit) : MethodChannel.MethodCallHandler {
    private val io = Executors.newFixedThreadPool(4)
    private val accounts = Executors.newSingleThreadExecutor()
    private val imageIo = Executors.newSingleThreadExecutor()
    private val publicGateway = WenkuPublicGateway(context)
    private val store = AccountSessionStore(context)
    private val webStore = AccountSessionStore(context, "web")
    private val websitePrefs = context.getSharedPreferences("wenku8-website", Context.MODE_PRIVATE)
    private val browser = WebsiteBrowser(context) { activityForBrowser }
    private val webCatalog = WenkuWebCatalogClient(render = { url -> browser.fetch(url) })
    private val publication = WenkuPublicationSession(webCatalog)
    private var webCookie: ApiSessionCookies? = null
    /** Null until the first session restore; keeps a cached account usable. */
    private var webSignedIn: Boolean? = null
    @Volatile private var websiteSite = WenkuWebsite.parse(websitePrefs.getString("host", null))
    /** Set while WebsiteLoginActivity is open; it hands the jar back here. */
    @Volatile private var browserLogin: ((String?) -> Unit)? = null
    @Volatile var activityForBrowser: android.app.Activity? = null

    fun deliverBrowserSession(jar: String?) {
        browserLogin?.invoke(jar)
    }
    @Volatile private var sessionCookie: ApiSessionCookies? = null
    @Volatile private var sessionLoaded = false
    @Volatile private var sessionRevision = 0
    private val images = File(context.filesDir, "wenku8-illustrations-v1")
    private val imageLock = Any()
    private data class Response(val bytes: ByteArray, val cookies: List<String>)

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        if (call.method == "updatePriority") {
            publicGateway.updatePriority = call.argument<Boolean>("enabled") == true
            result.success(null)
            return
        }
        if (call.method !in setOf("request", "cover", "requestBytes", "accountRequest", "accountAvatar", "login", "hasSession", "clearSession", "illustration", "clearIllustrations", "encodeReview", "webLogin", "publicationPage", "websiteStatus", "websiteSelect", "websiteProbe", "websiteKeepAlive", "websiteRanking", "websiteBrowserLogin")) {
            result.notImplemented()
            return
        }
        val account = call.method in setOf("accountRequest", "login", "hasSession", "clearSession", "webLogin", "publicationPage", "websiteStatus", "websiteSelect", "websiteProbe", "websiteKeepAlive", "websiteRanking", "websiteBrowserLogin")
        val queuedRevision = sessionRevision
        (if (account) accounts else if (call.method in setOf("illustration", "clearIllustrations", "accountAvatar")) imageIo else io).execute {
            try {
                if (account && !sessionLoaded) {
                    sessionCookie = store.read()?.let { ApiSessionCookies.fromStored(it) }
                    webCatalog.use(websiteSite, null)
                    webCookie = webStore.read()?.let { ApiSessionCookies.fromStored(it, requireSession = false) }
                    webSignedIn = webCookie != null
                    sessionLoaded = true
                }
                val response: Any? = when (call.method) {
                    "hasSession" -> sessionCookie?.hasSession == true
                    "websiteStatus" -> websiteStatus()
                    "websiteProbe" -> {
                        val started = System.currentTimeMillis()
                        val html = browser.fetch(websiteSite.origin.toString())
                        require(html.isNotBlank()) { "Empty page" }
                        val cookie = browser.cookies()?.let {
                            ApiSessionCookies.fromStored(it, requireSession = false)
                        }
                        if (cookie?.hasWebLogin == true) {
                            webStore.write(cookie.stored())
                            webCookie = cookie
                            webSignedIn = true
                        }
                        mapOf(
                            "host" to websiteSite.suffix,
                            "reachable" to true,
                            "millis" to (System.currentTimeMillis() - started),
                            "signedIn" to (cookie?.hasWebLogin == true || webSignedIn == true),
                        )
                    }
                    "websiteRanking" -> {
                        check(queuedRevision == sessionRevision)
                        val sort = call.argument<String>("sort") ?: ""
                        val page = call.argument<Int>("page") ?: 0
                        val complete = call.argument<Boolean>("complete") == true
                        val reply = webCatalog.ranking(sort, page, complete, webCookie)
                        val next = reply.cookies
                        if (next != webCookie && next.hasWebLogin) {
                            webStore.write(next.stored())
                            webCookie = next
                            webSignedIn = true
                        }
                        reply.html
                    }
                    "websiteBrowserLogin" -> {
                        check(queuedRevision == sessionRevision)
                        val host = websiteSite.suffix
                        val latch = java.util.concurrent.CountDownLatch(1)
                        val stored = java.util.concurrent.atomic.AtomicReference<String?>(null)
                        postToMain(Runnable {
                            activityForBrowser?.let { activity ->
                                browserLogin = { value ->
                                    stored.set(value)
                                    latch.countDown()
                                }
                                activity.startActivityForResult(
                                    android.content.Intent(activity, WebsiteLoginActivity::class.java)
                                        .putExtra("host", host),
                                    WEBSITE_LOGIN_REQUEST,
                                )
                            } ?: latch.countDown()
                        })
                        latch.await(180, java.util.concurrent.TimeUnit.SECONDS)
                        browserLogin = null
                        val jar = stored.get()
                        if (jar == null) {
                            websiteStatus()
                        } else {
                            val cookie = ApiSessionCookies.fromStored(jar, requireSession = false)
                            webStore.write(cookie.stored())
                            webCookie = cookie
                            webSignedIn = cookie.hasWebLogin
                            publication.reset()
                            websiteStatus()
                        }
                    }
                    "websiteKeepAlive" -> {
                        check(queuedRevision == sessionRevision)
                        val enabled = call.argument<Boolean>("enabled") == true
                        websitePrefs.edit().putBoolean("keepAlive", enabled).commit()
                        // A session that must not outlive the app is dropped
                        // from disk; the live jar keeps working until restart.
                        if (!enabled) webStore.write(null)
                        else webCookie?.let { webStore.write(it.stored()) }
                        websiteStatus()
                    }
                    "websiteSelect" -> {
                        val site = WenkuWebsite.parse(call.argument<String>("host"))
                        if (site == websiteSite) websiteStatus() else {
                            check(queuedRevision == sessionRevision)
                            websiteSite = site
                            websitePrefs.edit().putString("host", site.suffix).commit()
                            webCatalog.use(site, null)
                            // A session belongs to one line; the other line
                            // simply asked again. Keep the API login intact.
                            webStore.write(null)
                            webCookie = null
                            webSignedIn = null
                            publication.reset()
                            websiteStatus()
                        }
                    }
                    "accountAvatar" -> {
                        check(queuedRevision == sessionRevision && sessionCookie?.hasSession == true)
                        val reply = request("action=avatar", sessionCookie)
                        check(queuedRevision == sessionRevision)
                        require(reply.bytes.size <= 2 * 1024 * 1024)
                        validateImage(reply.bytes)
                        reply.bytes
                    }
                    "clearSession" -> {
                        store.write(null)
                        webStore.write(null)
                        sessionCookie = null
                        webCookie = null
                        webSignedIn = null
                        publication.reset()
                        sessionRevision++
                        null
                    }
                    "webLogin" -> {
                        check(sessionCookie?.hasSession == true && queuedRevision == sessionRevision)
                        val username = call.argument<String>("username") ?: ""
                        val password = call.argument<String>("password") ?: ""
                        // A completed browser check already carries a session;
                        // re-submitting the form would only duplicate it.
                        val fromBrowser = browser.cookies()?.let {
                            ApiSessionCookies.fromStored(it, requireSession = false)
                        }?.takeIf { it.hasWebLogin }
                        val cookie = fromBrowser
                            ?: webCatalog.login(username, password, sessionCookie!!)
                        webStore.write(cookie.stored())
                        webCookie = cookie
                        webSignedIn = true
                        null
                    }
                    "publicationPage" -> {
                        check(queuedRevision == sessionRevision)
                        val reply = publication.read(call.argument<Int>("library") ?: 0, call.argument<Int>("page") ?: 0, sessionCookie, webCookie) { action, cookie ->
                            val response = request(action, cookie)
                            WenkuPublicationSession.ApiReply(response.bytes.toString(Charsets.UTF_8), cookie.merge(response.cookies))
                        }
                        reply.apiCookies?.let { next ->
                            if (next != sessionCookie) {
                                store.write(if (next.hasSession) next.stored() else null)
                                sessionCookie = next.takeIf { it.hasSession }
                            }
                        }
                        reply.webCookies?.let { next ->
                            if (next != webCookie) {
                                webStore.write(if (next.hasWebLogin) next.stored() else null)
                                webCookie = next.takeIf { it.hasWebLogin }
                                webSignedIn = webCatalog.signedIn(next)
                            }
                        }
                        reply.body
                    }
                    "encodeReview" -> {
                        val text = call.argument<String>("text") ?: throw IllegalArgumentException("Missing text")
                        require(text.length <= 10000)
                        if (!charset("GBK").newEncoder().canEncode(text)) {
                            postToMain(Runnable { result.error("REVIEW_ENCODING", "Characters outside the review encoding", null) })
                            return@execute
                        }
                        Base64.encodeToString(URLEncoder.encode(text, "GBK").toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                    }
                    "illustration" -> illustration(call.argument<String>("url") ?: "")
                    "clearIllustrations" -> synchronized(imageLock) {
                        check(!images.exists() || images.deleteRecursively())
                        null
                    }
                    else -> {
                        val action = if (call.method == "cover") {
                            val id = call.argument<Int>("aid") ?: 0
                            require(id > 0)
                            "action=book&do=cover&aid=$id"
                        } else call.argument<String>("action") ?: ""
                        require(action.isNotBlank() && action.length <= 60000)
                        if (call.method == "login") {
                            require(action.startsWith("action=login&") || action.startsWith("action=loginemail&"))
                            val reply = request(action, null)
                            val text = reply.bytes.toString(Charsets.UTF_8)
                            if (text.trim() == "1") {
                                val cookie = ApiSessionCookies.empty().merge(reply.cookies)
                                check(cookie.hasSession) { "Missing session" }
                                store.write(cookie.stored())
                                // Never retain another account's website session.
                                webStore.write(null)
                                webCookie = null
                                webSignedIn = false
                                publication.reset()
                                sessionCookie = cookie
                                sessionRevision++
                            }
                            text
                        } else if (call.method == "accountRequest") {
                            check(queuedRevision == sessionRevision) { "Session changed" }
                            val cookie = sessionCookie
                            if (cookie == null) "4" else {
                                val reply = request(action, cookie)
                                val text = reply.bytes.toString(Charsets.UTF_8)
                                val next = cookie.merge(reply.cookies)
                                if (next != cookie && text.trim() != "4") {
                                    store.write(if (next.hasSession) next.stored() else null)
                                    sessionCookie = next.takeIf { it.hasSession }
                                }
                                text
                            }
                        } else {
                            val reply = request(action, null)
                            if (call.method == "request") reply.bytes.toString(Charsets.UTF_8) else reply.bytes
                        }
                    }
                }
                postToMain(Runnable { result.success(response) })
            } catch (failure: WebCatalogFailure) {
                postToMain(Runnable { result.error(failure.code, failure.publicMessage, null) })
            } catch (_: Exception) {
                // Never expose actions, credentials or cookie-bearing exceptions.
                postToMain(Runnable { result.error("LEGACY_API_ERROR", "Request or local storage failed", null) })
            } catch (_: LinkageError) {
                postToMain(Runnable { result.error("LEGACY_API_RUNTIME", "Legacy runtime unavailable", null) })
            }
        }
    }

    private fun websiteStatus(): Map<String, Any?> = mapOf(
        "host" to websiteSite.suffix,
        "signedIn" to (webSignedIn ?: webCookie != null),
        "keepAlive" to websitePrefs.getBoolean("keepAlive", true),
    )

    private fun request(action: String, cookie: ApiSessionCookies?): Response {
        val version = TokenManager.getMagicVersionCpp("1.30")
        val encoded = Base64.encodeToString(action.toByteArray(Charsets.UTF_8), Base64.DEFAULT).trim()
        val body = "&appver=$version&request=$encoded&timetoken=${System.currentTimeMillis()}"
        val connection = (URL("https://wenku8-relay.mewx.org/").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Accept-Encoding", "gzip")
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            cookie?.let { setRequestProperty("Cookie", it.header()) }
            connectTimeout = 5000
            readTimeout = 10000
            doOutput = true
            instanceFollowRedirects = false
        }
        try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            check(connection.responseCode in 200..299)
            val cookies = connection.headerFields.entries
                .filter { it.key?.equals("Set-Cookie", ignoreCase = true) == true }
                .flatMap { it.value }
            val stream = if (connection.contentEncoding?.contains("gzip", ignoreCase = true) == true) {
                GZIPInputStream(BufferedInputStream(connection.inputStream))
            } else connection.inputStream
            return Response(stream.use { readBounded(it, 8 * 1024 * 1024) }, cookies)
        } catch (failure: Exception) {
            // The existing catalogue relay already uses Cloudflare. Only
            // public image bytes use the small spare gateway allowance.
            if (cookie == null) publicGateway.cover(action)?.let { bytes ->
                validateImage(bytes)
                return Response(bytes, emptyList())
            }
            throw failure
        } finally {
            connection.disconnect()
        }
    }

    private fun illustration(raw: String): ByteArray = synchronized(imageLock) {
        val url = IllustrationUrlPolicy.parse(raw)
        val name = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }
        val file = File(images, name)
        if (file.isFile) {
            try {
                require(file.length() in 1..(8 * 1024 * 1024))
                val cached = file.readBytes()
                validateImage(cached)
                file.setLastModified(System.currentTimeMillis())
                return@synchronized cached
            } catch (_: Exception) { file.delete() }
        }
        val bytes = downloadIllustration(url)
            try {
                images.mkdirs()
                val temporary = File(images, "$name.part")
                temporary.writeBytes(bytes)
                check(temporary.renameTo(file))
                val cached = images.listFiles()?.filter { it.isFile } ?: emptyList()
                var size = cached.sumOf { it.length() }
                for (old in cached.sortedBy { it.lastModified() }) {
                    if (size <= 256L * 1024 * 1024) break
                    if (old != file) { val length = old.length(); if (old.delete()) size -= length }
                }
            } catch (_: Exception) { /* An online illustration survives a cache write error. */ }
        bytes
    }

    private fun downloadIllustration(source: URL): ByteArray {
        try {
            return downloadOriginalIllustration(source)
        } catch (failure: Exception) {
            publicGateway.image(source)?.let { bytes ->
                try { validateImage(bytes); return bytes } catch (_: Exception) { /* Preserve original failure. */ }
            }
            throw failure
        }
    }

    private fun downloadOriginalIllustration(source: URL): ByteArray {
        var url = source
        for (hop in 0..3) {
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 12000
                instanceFollowRedirects = false
            }
            try {
                val status = connection.responseCode
                if (status in setOf(301, 302, 303, 307, 308)) {
                    check(hop < 3) { "Too many image redirects" }
                    val location = connection.getHeaderField("Location")
                    require(!location.isNullOrBlank())
                    url = IllustrationUrlPolicy.redirect(url, location)
                } else {
                    check(status in 200..299)
                    val bytes = connection.inputStream.use { readBounded(it, 8 * 1024 * 1024) }
                    validateImage(bytes)
                    return bytes
                }
            } finally {
                connection.disconnect()
            }
        }
        error("Image redirect limit")
    }

    private fun validateImage(bytes: ByteArray) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0 &&
            bounds.outWidth.toLong() * bounds.outHeight <= 50_000_000L)
    }

    private fun readBounded(input: java.io.InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= limit)
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
    fun close() {
        browser.close()
        io.shutdown()
        accounts.shutdown()
        imageIo.shutdown()
    }
}
