package com.csksy.nepu

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.api.Log
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.ui.settings.Globals
import com.lagradost.nicehttp.NiceResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

private const val TAG = "Nepu_CF"

private const val SOLVER_TIMEOUT_MS = 120_000L
private const val POLL_INTERVAL_MS = 1000L

// a cancelled or timed out check blocks the auto solver for a minute so the
// home page retry loop cannot throw the dialog at the user over and over
private const val SOLVER_BACKOFF_MS = 60_000L

@Volatile private var lastSolverFailureAt = 0L

private const val ACCENT = 0xFF3DD598.toInt()
private const val SHEET_BG = 0xFF17171B.toInt()
private const val SURFACE = 0xFF101014.toInt()
private const val TEXT_SUBTLE = 0xFF9E9EA8.toInt()

private const val MAIN_URL = "https://nepu.io"
private const val MAIN_HOST = "nepu.io"

private val CHALLENGE_TITLES = listOf(
    "just a moment", "just a moment...", "checking your browser", "attention required",
    "one more step", "verify you are human", "ddos-guard"
)

internal const val DEFAULT_UA =
    "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/155.0.0.0 Mobile Safari/537.36"

internal fun parseCookieMap(cookieStr: String): LinkedHashMap<String, String> {
    val map = LinkedHashMap<String, String>()
    cookieStr.split(";").forEach { part ->
        val trimmed = part.trim()
        val eq = trimmed.indexOf('=')
        if (eq > 0) {
            val name = trimmed.substring(0, eq).trim()
            val value = trimmed.substring(eq + 1).trim()
            if (name.isNotEmpty() && value.isNotEmpty()) map[name] = value
        }
    }
    return map
}

internal fun mergeCookieStrings(existing: String, fresh: String): String {
    val map = parseCookieMap(existing)
    map.putAll(parseCookieMap(fresh))
    return map.entries.joinToString("; ") { "${it.key}=${it.value}" }
}

internal fun extractClearanceToken(cookieStr: String): String? {
    val token = parseCookieMap(cookieStr)["cf_clearance"] ?: return null
    return token.trim().takeIf { it.length >= 10 }
}

internal fun hasClearance(cookieStr: String): Boolean = extractClearanceToken(cookieStr) != null

internal object NepuSession {
    private const val KEY_COOKIES = "NEPU_CF_COOKIES"
    private const val KEY_UA = "NEPU_CF_USER_AGENT"
    private const val KEY_SAVED_AT = "NEPU_CF_SAVED_AT"

    @Volatile private var cookies: String? = null
    @Volatile private var userAgent: String? = null
    @Volatile private var savedAt: Long = 0L
    @Volatile private var loaded = false

    fun init() {
        if (loaded) return
        loaded = true
        try {
            cookies = CloudStreamApp.getKey<String>(KEY_COOKIES)?.takeIf { it.isNotBlank() }
            userAgent = CloudStreamApp.getKey<String>(KEY_UA)?.takeIf { it.isNotBlank() }
            savedAt = CloudStreamApp.getKey<String>(KEY_SAVED_AT)?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            Log.e(TAG, "init: ${e.message}")
        }
    }

    // kept until the site rejects it, cloudflare decides the lifetime
    fun cookies(): String? = cookies

    fun userAgent(): String = userAgent ?: DEFAULT_UA

    fun savedAtMillis(): Long = savedAt

    fun active(): Boolean = cookies != null

    fun save(cookieString: String, ua: String) {
        cookies = cookieString
        userAgent = ua
        savedAt = System.currentTimeMillis()
        try {
            CloudStreamApp.setKey(KEY_COOKIES, cookieString)
            CloudStreamApp.setKey(KEY_UA, ua)
            CloudStreamApp.setKey(KEY_SAVED_AT, savedAt.toString())
        } catch (e: Exception) {
            Log.e(TAG, "save session: ${e.message}")
        }
    }

    // the server rotates __cf_bm and reissues cf_clearance on the fly, folding
    // those into the stored string is what keeps a verified session alive
    fun harvest(fresh: String) {
        val current = cookies ?: return
        val merged = mergeCookieStrings(current, fresh)
        if (merged != current) {
            try {
                CloudStreamApp.setKey(KEY_COOKIES, merged)
                CloudStreamApp.setKey(KEY_SAVED_AT, System.currentTimeMillis().toString())
            } catch (e: Exception) {
                Log.e(TAG, "harvest: ${e.message}")
            }
            cookies = merged
            savedAt = System.currentTimeMillis()
        }
    }

    fun clear() {
        cookies = null
        userAgent = null
        savedAt = 0L
        try {
            CloudStreamApp.setKey(KEY_COOKIES, "")
            CloudStreamApp.setKey(KEY_UA, "")
            CloudStreamApp.setKey(KEY_SAVED_AT, "")
        } catch (e: Exception) {
            Log.e(TAG, "clear session: ${e.message}")
        }
    }
}

// the webview flavor of the user agent leaks through "; wv" and the Version/
// token, cloudflare treats those as bot traffic so they are stripped before
// the challenge page runs
internal fun cleanWebViewUa(original: String): String {
    var clean = original.replace("; wv", "").replace(Regex("Version/[0-9.]+\\s*"), "")
    clean = clean.replace("Android TV", "Android")
    if (clean.contains("Android") && !clean.contains("Mobile")) {
        clean = clean.replace("Safari/", "Mobile Safari/")
    }
    return clean.ifBlank { DEFAULT_UA }
}

// expired setCookie tricks stopped removing cf_clearance on current
// webview builds, removeAllCookies does
internal fun wipeNepuBrowserState() {
    try {
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.removeAllCookies(null)
        cm.removeSessionCookies(null)
        cm.flush()
    } catch (e: Exception) {
        Log.e(TAG, "cookie wipe: ${e.message}")
    }
    try {
        WebStorage.getInstance().deleteAllData()
    } catch (e: Exception) {
        Log.e(TAG, "storage wipe: ${e.message}")
    }
    try {
        app.baseClient.connectionPool.evictAll()
    } catch (e: Exception) {
        Log.e(TAG, "pool evict: ${e.message}")
    }
    purgeNepuJar()
    NepuSession.clear()
}

private fun purgeNepuJar() {
    try {
        val url = "$MAIN_URL/".toHttpUrlOrNull() ?: return
        val expired = listOf("cf_clearance", "__cf_bm", "__ddg2_", "cf_chl_prog", "cf_chl_seq")
            .map { name ->
                Cookie.Builder()
                    .name(name)
                    .value("expired")
                    .domain(MAIN_HOST)
                    .path("/")
                    .expiresAt(0L)
                    .build()
            }
        app.baseClient.cookieJar.saveFromResponse(url, expired)
    } catch (e: Exception) {
        Log.d(TAG, "jar purge skipped: ${e.message}")
    }
}

internal fun isNepuCloudflareBlocked(response: NiceResponse): Boolean {
    val mitigated = response.headers["cf-mitigated"]?.equals("challenge", ignoreCase = true)
    if (mitigated == true) return true
    if (response.code != 403 && response.code != 503) return false
    if (mitigated == false) return false
    val body = try {
        response.text.lowercase().take(4000)
    } catch (e: Exception) {
        return true
    }
    if (body.isBlank()) return true
    return body.contains("just a moment") ||
        body.contains("challenge-platform") ||
        body.contains("checking your browser") ||
        body.contains("verify you are human")
}

private fun isChallengeTitle(title: String): Boolean {
    val lower = title.lowercase()
    return CHALLENGE_TITLES.any { lower.contains(it) }
}

private val solverMutex = Mutex()

private class CursorPos {
    var x = 0f
    var y = 0f
}

private class NepuSolverDialog(
    private val targetUrl: String,
    private val onFinished: (Boolean) -> Unit
) {
    private var dialog: AlertDialog? = null
    private var webView: WebView? = null
    private var statusText: TextView? = null
    private val handler = Handler(Looper.getMainLooper())
    private val resolved = AtomicBoolean(false)
    private var elapsedMs = 0L
    private var challengePage = false

    private val targetHost: String = try {
        val uri = Uri.parse(targetUrl)
        "${uri.scheme}://${uri.host}"
    } catch (e: Exception) {
        targetUrl
    }

    private fun extractCookies(): String {
        val cm = try {
            CookieManager.getInstance().apply { flush() }
        } catch (e: Exception) {
            return ""
        }
        val map = LinkedHashMap<String, String>()
        val urls = linkedSetOf(
            webView?.url?.takeIf { it.isNotBlank() },
            targetUrl.takeIf { it.isNotBlank() },
            targetHost.takeIf { it.isNotBlank() },
            targetHost.takeIf { it.isNotBlank() }?.let { "$it/" },
            "$MAIN_URL/",
            "https://challenges.cloudflare.com/"
        ).filterNotNull()
        urls.forEach { u ->
            try {
                cm.getCookie(u)?.takeIf { it.isNotBlank() }?.let { raw ->
                    parseCookieMap(raw).forEach { (name, value) ->
                        if (value.isNotEmpty()) map[name] = value
                    }
                }
            } catch (_: Exception) {}
        }
        return map.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    private fun checkForClearance() {
        if (resolved.get()) return
        val cookieString = try {
            extractCookies()
        } catch (e: Exception) {
            Log.d(TAG, "clearance poll: ${e.message}")
            return
        }
        if (!hasClearance(cookieString)) return
        // the clearance lands while the check is still running and can be
        // reissued a few times, only the page after it proves it is usable
        if (challengePage) return
        finishWith(cookieString)
    }

    private fun finishWith(cookieString: String) {
        if (!resolved.compareAndSet(false, true)) return
        lastSolverFailureAt = 0L
        handler.removeCallbacksAndMessages(null)
        val ua = webView?.settings?.userAgentString ?: DEFAULT_UA
        NepuSession.save(cookieString, ua)
        try { webView?.destroy() } catch (_: Exception) {}
        try { dialog?.dismiss() } catch (_: Exception) {}
        try { onFinished(true) } catch (_: Exception) {}
    }

    private fun finishCancelled() {
        if (!resolved.compareAndSet(false, true)) return
        lastSolverFailureAt = System.currentTimeMillis()
        handler.removeCallbacksAndMessages(null)
        try { webView?.destroy() } catch (_: Exception) {}
        try { dialog?.dismiss() } catch (_: Exception) {}
        try { onFinished(false) } catch (_: Exception) {}
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (resolved.get()) return
            elapsedMs += POLL_INTERVAL_MS
            checkForClearance()
            if (resolved.get()) return
            if (elapsedMs >= SOLVER_TIMEOUT_MS) {
                statusText?.text = "Timed out after ${elapsedMs / 1000}s"
                finishCancelled()
                return
            }
            statusText?.text = "Verifying access · ${elapsedMs / 1000}s"
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun show(activity: AppCompatActivity) {
        val dp = activity.resources.displayMetrics.density
        val screenH = activity.resources.displayMetrics.heightPixels
        val dialogW = (activity.resources.displayMetrics.widthPixels * 0.94f).toInt()
        val dialogH = (screenH * 0.88f).toInt()
        val webH = (screenH * 0.60f).toInt()

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(SHEET_BG)
                cornerRadius = 22 * dp
            }
            setPadding((20 * dp).toInt(), (18 * dp).toInt(), (20 * dp).toInt(), (14 * dp).toInt())
        }

        root.addView(TextView(activity).apply {
            text = "Cloudflare verification"
            textSize = 17f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, (3 * dp).toInt())
        })
        root.addView(TextView(activity).apply {
            text = "Nepu runs a browser check on first access. It finishes on its own in most cases; tap the checkbox if it appears."
            textSize = 12.5f
            setTextColor(TEXT_SUBTLE)
            setPadding(0, 0, 0, (12 * dp).toInt())
        })

        val progress = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            indeterminateTintList = android.content.res.ColorStateList.valueOf(ACCENT)
            progressTintList = android.content.res.ColorStateList.valueOf(ACCENT)
            layoutParams = LinearLayout.LayoutParams(-1, (3 * dp).toInt()).also {
                it.bottomMargin = (12 * dp).toInt()
            }
        }
        root.addView(progress)

        val webFrame = FrameLayout(activity).apply {
            background = GradientDrawable().apply {
                setColor(SURFACE)
                cornerRadius = 16 * dp
            }
            isFocusable = true
            isFocusableInTouchMode = true
        }
        webView = buildWebView(activity)
        webFrame.addView(webView, FrameLayout.LayoutParams(-1, -1))

        val isTv = try { Globals.isLayout(Globals.TV) } catch (e: Throwable) { false }
        if (isTv) {
            attachTvCursor(activity, webFrame)
        }
        root.addView(webFrame, LinearLayout.LayoutParams(-1, webH))

        root.addView(TextView(activity).apply {
            statusText = this
            text = "Loading check page"
            textSize = 12f
            setTextColor(TEXT_SUBTLE)
            setPadding((2 * dp).toInt(), (10 * dp).toInt(), 0, (4 * dp).toInt())
        })

        root.addView(Button(activity).apply {
            text = "Cancel"
            transformationMethod = null
            textSize = 14f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(0xFF232329.toInt())
                cornerRadius = 24 * dp
            }
            layoutParams = LinearLayout.LayoutParams(-1, (46 * dp).toInt()).also {
                it.topMargin = (8 * dp).toInt()
            }
            setOnClickListener { finishCancelled() }
        })

        dialog = AlertDialog.Builder(activity).setView(root).setCancelable(false).create()
        dialog?.show()
        dialog?.window?.apply {
            setLayout(dialogW, dialogH)
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            try { setAcceptThirdPartyCookies(webView, true) } catch (_: Exception) {}
            flush()
        }
        // the wipe runs right before the dialog opens, the small delay lets
        // the async removeAllCookies land so the challenge is guaranteed
        webView?.postDelayed({ webView?.loadUrl(targetUrl) }, 350L)
        handler.postDelayed(pollRunnable, POLL_INTERVAL_MS)
    }

    private fun attachTvCursor(activity: AppCompatActivity, frame: FrameLayout) {
        val dp = activity.resources.displayMetrics.density
        val size = (22 * dp).toInt()
        val cursor = View(activity).apply {
            layoutParams = FrameLayout.LayoutParams(size, size)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(android.graphics.Color.argb(170, 61, 213, 152))
                setStroke((2 * dp).toInt(), Color.WHITE)
            }
            elevation = 999f
        }
        frame.addView(cursor)

        val pos = CursorPos()
        frame.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                frame.viewTreeObserver.removeOnGlobalLayoutListener(this)
                pos.x = frame.width / 2f
                pos.y = frame.height / 2f
                cursor.translationX = pos.x - size / 2f
                cursor.translationY = pos.y - size / 2f
            }
        })

        val step = 14f * dp
        frame.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> { moveCursor(pos, cursor, size, frame, 0f, -step); true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { moveCursor(pos, cursor, size, frame, 0f, step); true }
                KeyEvent.KEYCODE_DPAD_LEFT -> { moveCursor(pos, cursor, size, frame, -step, 0f); true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { moveCursor(pos, cursor, size, frame, step, 0f); true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { tapAt(pos); true }
                else -> false
            }
        }
        frame.requestFocus()
    }

    private fun moveCursor(pos: CursorPos, cursor: View, size: Int, frame: View, dx: Float, dy: Float) {
        pos.x = (pos.x + dx).coerceIn(0f, frame.width.toFloat())
        pos.y = (pos.y + dy).coerceIn(0f, frame.height.toFloat())
        cursor.translationX = pos.x - size / 2f
        cursor.translationY = pos.y - size / 2f
    }

    private fun tapAt(pos: CursorPos) {
        val wv = webView ?: return
        val t = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, pos.x, pos.y, 0)
        val up = MotionEvent.obtain(t, t + 100, MotionEvent.ACTION_UP, pos.x, pos.y, 0)
        try {
            wv.dispatchTouchEvent(down)
            wv.dispatchTouchEvent(up)
        } catch (e: Exception) {
            Log.d(TAG, "tv tap: ${e.message}")
        } finally {
            down.recycle()
            up.recycle()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildWebView(context: Context): WebView {
        return WebView(context).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            requestFocus()
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                allowContentAccess = true
                allowFileAccess = true
                loadsImagesAutomatically = true
                userAgentString = cleanWebViewUa(settings.userAgentString ?: DEFAULT_UA)
                mediaPlaybackRequiresUserGesture = false
            }
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            webChromeClient = object : WebChromeClient() {}
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = false

                override fun onPageFinished(view: WebView?, url: String?) {
                    if (resolved.get()) return
                    val title = view?.title ?: ""
                    challengePage = isChallengeTitle(title)
                    statusText?.text = if (challengePage) {
                        "Waiting for the browser check"
                    } else {
                        "Reading session"
                    }
                    checkForClearance()
                }
            }
        }
    }

    fun dismiss() {
        handler.removeCallbacksAndMessages(null)
        try { webView?.apply { stopLoading(); destroy() } } catch (_: Exception) {}
        webView = null
        try { dialog?.dismiss() } catch (_: Exception) {}
        dialog = null
    }
}

internal suspend fun solveNepuCloudflare(url: String): Boolean = withContext(Dispatchers.Main) {
    val activity = CommonActivity.activity as? AppCompatActivity
    if (activity == null || activity.isFinishing || activity.isDestroyed) {
        return@withContext false
    }
    suspendCancellableCoroutine { cont ->
        val solver = NepuSolverDialog(url) { success ->
            if (cont.isActive) cont.resume(success)
        }
        try {
            solver.show(activity)
        } catch (e: Exception) {
            Log.e(TAG, "solver show: ${e.message}")
            if (cont.isActive) cont.resume(false)
        }
        cont.invokeOnCancellation { solver.dismiss() }
    }
}

private fun solverOnBackoff(): Boolean =
    System.currentTimeMillis() - lastSolverFailureAt < SOLVER_BACKOFF_MS

private fun sessionHeaders(extra: Map<String, String>, accept: String): Map<String, String> {
    val headers = extra.toMutableMap()
    if (!headers.containsKey("Accept")) headers["Accept"] = accept
    if (!headers.containsKey("User-Agent")) headers["User-Agent"] = NepuSession.userAgent()
    NepuSession.cookies()?.let { headers["Cookie"] = it }
    return headers
}

private fun harvestSetCookies(response: NiceResponse) {
    if (response.code !in 200..299) return
    try {
        val setCookies = response.headers.values("Set-Cookie")
        if (setCookies.isEmpty()) return
        val fresh = setCookies.mapNotNull { sc ->
            val part = sc.substringBefore(";").trim()
            val eq = part.indexOf('=')
            if (eq > 0 && part.substring(0, eq).trim().isNotEmpty() && part.substring(eq + 1).trim().isNotEmpty()) {
                part
            } else {
                null
            }
        }.joinToString("; ")
        if (fresh.isNotBlank()) NepuSession.harvest(fresh)
    } catch (e: Exception) {
        Log.d(TAG, "set-cookie harvest: ${e.message}")
    }
}

private suspend fun throughCloudflare(
    first: NiceResponse,
    retry: suspend () -> NiceResponse,
    solve: suspend () -> Boolean
): NiceResponse {
    var response = first
    harvestSetCookies(response)
    if (!isNepuCloudflareBlocked(response)) return response

    solverMutex.withLock {
        if (NepuSession.cookies() != null) {
            response = retry()
            harvestSetCookies(response)
            if (!isNepuCloudflareBlocked(response)) return response
        }
        if (solverOnBackoff()) return response
        NepuSession.clear()
        if (!solve()) return response
        repeat(2) {
            response = retry()
            harvestSetCookies(response)
            if (!isNepuCloudflareBlocked(response)) return response
        }
    }
    return response
}

internal suspend fun nepuGet(
    url: String,
    headers: Map<String, String> = emptyMap()
): NiceResponse {
    val accept = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
    val first = app.get(url, headers = sessionHeaders(headers, accept), timeout = 30_000L)
    return throughCloudflare(
        first,
        retry = { app.get(url, headers = sessionHeaders(headers, accept), timeout = 30_000L) },
        solve = { solveNepuCloudflare("$MAIN_URL/") }
    )
}

internal suspend fun nepuPostForm(
    url: String,
    form: Map<String, String>,
    headers: Map<String, String> = emptyMap()
): NiceResponse {
    val first = app.post(url, data = form, headers = sessionHeaders(headers, "*/*"), timeout = 30_000L)
    return throughCloudflare(
        first,
        retry = { app.post(url, data = form, headers = sessionHeaders(headers, "*/*"), timeout = 30_000L) },
        solve = { solveNepuCloudflare("$MAIN_URL/") }
    )
}
