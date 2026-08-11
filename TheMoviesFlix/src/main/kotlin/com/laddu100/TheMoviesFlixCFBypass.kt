package com.laddu100

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.api.Log
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.ui.settings.Globals
import com.lagradost.nicehttp.NiceResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

private const val TAG = "TMF_CF"

private val CF_CHALLENGE_TITLES = listOf(
    "just a moment", "just a moment...", "checking your browser",
    "attention required", "ddos-guard", "one more step"
)

private const val COOKIE_TTL_MS = 15L * 60 * 60 * 1000
private const val SOLVER_TIMEOUT_MS = 120_000L
private const val POLL_INTERVAL_MS = 1000L
private const val CURSOR_STEP_DP = 10f

internal object TMFCFStore {
    private const val PREFS_NAME = "TMFCFBypass"
    private const val KEY_COOKIES = "cf_cookies"
    private const val KEY_UA = "cf_user_agent"
    private const val KEY_HOST = "cf_cookie_host"
    private const val KEY_TIMESTAMP = "cf_timestamp"

    private var prefs: android.content.SharedPreferences? = null
    @Volatile private var cachedCookies: String? = null
    @Volatile private var cachedUA: String? = null
    @Volatile private var cachedHost: String? = null
    @Volatile private var cachedTimestamp: Long = 0L

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            cachedCookies = prefs?.getString(KEY_COOKIES, null)
            cachedUA = prefs?.getString(KEY_UA, null)
            cachedHost = prefs?.getString(KEY_HOST, null)
            cachedTimestamp = prefs?.getLong(KEY_TIMESTAMP, 0L) ?: 0L
            if (cachedCookies.isNullOrBlank()) {
                val persisted = TheMoviesFlixPlugin.cfCookies
                val persistedUa = TheMoviesFlixPlugin.cfUserAgent
                val persistedHost = TheMoviesFlixPlugin.cfCookieHost
                if (persisted.isNotBlank()) {
                    cachedCookies = persisted
                    cachedUA = persistedUa
                    cachedHost = persistedHost
                    cachedTimestamp = System.currentTimeMillis()
                    prefs?.edit()?.apply {
                        putString(KEY_COOKIES, persisted)
                        putString(KEY_UA, persistedUa)
                        putString(KEY_HOST, persistedHost)
                        putLong(KEY_TIMESTAMP, cachedTimestamp)
                    }?.apply()
                }
            }
        }
    }

    fun getCookies(): String? {
        val cookies = cachedCookies
        if (cookies.isNullOrBlank()) return null
        if (System.currentTimeMillis() - cachedTimestamp > COOKIE_TTL_MS) {
            clear()
            return null
        }
        return cookies
    }

    fun getUserAgent(): String? = cachedUA?.takeIf { it.isNotBlank() }
    fun getHost(): String? = cachedHost?.takeIf { it.isNotBlank() }

    fun save(cookies: String, userAgent: String, host: String) {
        cachedCookies = cookies
        cachedUA = userAgent
        cachedHost = host
        cachedTimestamp = System.currentTimeMillis()
        prefs?.edit()?.apply {
            putString(KEY_COOKIES, cookies)
            putString(KEY_UA, userAgent)
            putString(KEY_HOST, host)
            putLong(KEY_TIMESTAMP, cachedTimestamp)
        }?.apply()
        TheMoviesFlixPlugin.cfCookies = cookies
        TheMoviesFlixPlugin.cfUserAgent = userAgent
        TheMoviesFlixPlugin.cfCookieHost = host
    }

    fun clear() {
        cachedCookies = null
        cachedUA = null
        cachedHost = null
        cachedTimestamp = 0L
        prefs?.edit()?.clear()?.apply()
        TheMoviesFlixPlugin.cfCookies = ""
        TheMoviesFlixPlugin.cfUserAgent = ""
        TheMoviesFlixPlugin.cfCookieHost = ""
    }
}

internal fun isCloudflareBlocked(response: NiceResponse): Boolean {
    val code = response.code
    if (code == 503) return true
    val body = try { response.text.lowercase() } catch (e: Exception) { "" }
    if (code == 403) {
        if (body.contains("just a moment") && body.contains("challenge-platform")) return true
        if (body.contains("checking your browser") && body.contains("cloudflare")) return true
        if (body.contains("cf-browser-verification")) return true
        if (body.contains("checking if the site connection is secure")) return true
        if (body.contains("just a moment")) return true
        return false
    }
    if (body.contains("just a moment") && body.contains("challenge-platform")) return true
    if (body.contains("checking your browser") && body.contains("cloudflare")) return true
    if (body.contains("cf-browser-verification")) return true
    if (body.contains("checking if the site connection is secure")) return true
    return false
}

private fun isChallengeTitle(title: String): Boolean {
    val lower = title.lowercase()
    return CF_CHALLENGE_TITLES.any { lower.contains(it) }
}

private val cfBypassMutex = Mutex()

private class CursorPosHolder { var x: Float = 0f; var y: Float = 0f }

@SuppressLint("InflateParams")
private class TMFCFDialog(
    private val targetUrl: String,
    private val onFinished: ((Boolean) -> Unit)? = null
) {
    private var dialog: AlertDialog? = null
    private var webView: WebView? = null
    private var statusText: TextView? = null
    private var progressBar: ProgressBar? = null
    private var cursorView: View? = null
    private val handler = Handler(Looper.getMainLooper())
    private val resolved = java.util.concurrent.atomic.AtomicBoolean(false)
    private var pollElapsedMs = 0L

    private val targetHost: String by lazy {
        try {
            val uri = Uri.parse(targetUrl)
            "${uri.scheme}://${uri.host}"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse target host: ${e.message}")
            targetUrl
        }
    }

    private fun extractAndFinish() {
        if (resolved.get()) return
        try {
            CookieManager.getInstance().flush()
            val cookieStr = CookieManager.getInstance().getCookie(targetHost) ?: ""
            val hasCfClearance = cookieStr.contains("cf_clearance")
            if (hasCfClearance) {
                finishSuccess(cookieStr)
            }
        } catch (e: Exception) {
            Log.e(TAG, "extractAndFinish error: ${e.message}")
        }
    }

    private fun finishSuccess(cookieStr: String) {
        if (!resolved.compareAndSet(false, true)) return
        handler.removeCallbacksAndMessages(null)
        val ua = webView?.settings?.userAgentString ?: ""
        TMFCFStore.save(cookieStr, ua, targetHost)
        Log.d(TAG, "Cookies saved (len=${cookieStr.length}) host=$targetHost")
        try { webView?.destroy() } catch (e: Exception) { Log.e(TAG, "destroy: ${e.message}") }
        try { (webView?.getTag() as? Dialog)?.dismiss() } catch (e: Exception) {}
        try { onFinished?.invoke(true) } catch (e: Exception) { Log.e(TAG, "onFinished: ${e.message}") }
    }

    private fun finishFailure() {
        if (!resolved.compareAndSet(false, true)) return
        handler.removeCallbacksAndMessages(null)
        try { webView?.destroy() } catch (e: Exception) {}
        try { dialog?.dismiss() } catch (e: Exception) {}
        try { onFinished?.invoke(false) } catch (e: Exception) {}
    }

    private val cookiePollRunnable = object : Runnable {
        override fun run() {
            if (resolved.get() || dialog == null || dialog?.isShowing != true) return
            pollElapsedMs += POLL_INTERVAL_MS
            extractAndFinish()
            if (!resolved.get()) {
                if (pollElapsedMs >= SOLVER_TIMEOUT_MS) {
                    statusText?.text = "Timed out. Tap Cancel to close."
                    finishFailure()
                } else {
                    statusText?.text = "Waiting for cookies... (${pollElapsedMs / 1000}s)"
                    handler.postDelayed(this, POLL_INTERVAL_MS)
                }
            }
        }
    }

    fun show(activity: AppCompatActivity) {
        val dp = activity.resources.displayMetrics.density
        val screenW = activity.resources.displayMetrics.widthPixels
        val screenH = activity.resources.displayMetrics.heightPixels
        val dialogW = (screenW * 0.95f).toInt()
        val dialogH = (screenH * 0.9f).toInt()

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * dp).toInt(), (12 * dp).toInt(), (16 * dp).toInt(), (8 * dp).toInt())
        }

        val titleView = TextView(activity).apply {
            text = "Cloudflare Bypass"
            textSize = 16f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, (8 * dp).toInt())
        }
        container.addView(titleView)

        val statusView = TextView(activity).apply {
            text = "Loading challenge page..."
            textSize = 12f
            setTextColor(Color.parseColor("#A0A0B0"))
            setPadding(0, 0, 0, (4 * dp).toInt())
        }
        statusText = statusView
        container.addView(statusView)

        val isTv = try { Globals.isLayout(Globals.TV) } catch (e: Throwable) { false }
        val hintView = TextView(activity).apply {
            text = if (isTv) {
                "Use D-pad to move the cursor, OK/Enter to click the captcha checkbox."
            } else {
                "Solve the CAPTCHA below, then tap Done."
            }
            textSize = 11f
            setTextColor(Color.parseColor("#707080"))
            setPadding(0, 0, 0, (8 * dp).toInt())
        }
        container.addView(hintView)

        val progress = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = (8 * dp).toInt() }
        }
        progressBar = progress
        container.addView(progress)

        val webViewHeight = (screenH * 0.65f).toInt()
        val webContainer = FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(-1, webViewHeight)
            isFocusable = true
            isFocusableInTouchMode = true
        }
        webView = buildWebView(activity)
        webContainer.addView(webView, FrameLayout.LayoutParams(-1, -1))

        if (isTv) {
            val cursorSize = (22 * dp).toInt()
            val cursor = View(activity).apply {
                layoutParams = FrameLayout.LayoutParams(cursorSize, cursorSize)
                val shape = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.argb(160, 255, 50, 50))
                    setStroke((2 * dp).toInt(), Color.WHITE)
                }
                background = shape
                elevation = 999f
            }
            cursorView = cursor
            webContainer.addView(cursor)

            val pos = CursorPosHolder()
            pos.x = webViewHeight / 2f
            pos.y = webViewHeight / 2f
            cursor.translationX = pos.x - cursorSize / 2f
            cursor.translationY = pos.y - cursorSize / 2f

            webContainer.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    webContainer.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    pos.x = webContainer.width / 2f
                    pos.y = webContainer.height / 2f
                    cursor.translationX = pos.x - cursorSize / 2f
                    cursor.translationY = pos.y - cursorSize / 2f
                }
            })

            val step = CURSOR_STEP_DP * dp
            webContainer.setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        moveCursor(pos, cursor, cursorSize, webContainer, 0f, -step)
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        moveCursor(pos, cursor, cursorSize, webContainer, 0f, step)
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        moveCursor(pos, cursor, cursorSize, webContainer, -step, 0f)
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        moveCursor(pos, cursor, cursorSize, webContainer, step, 0f)
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        clickAtCursor(pos, webView)
                        true
                    }
                    else -> false
                }
            }
            webContainer.requestFocus()
        }
        container.addView(webContainer)

        val btnContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.topMargin = (8 * dp).toInt() }
        }

        val doneButton = android.widget.Button(activity).apply {
            text = "Done"
            setOnClickListener {
                CookieManager.getInstance().flush()
                extractAndFinish()
                if (!resolved.get()) {
                    statusText?.text = "No cookies yet. Solve the CAPTCHA first."
                }
            }
        }
        btnContainer.addView(doneButton)

        val cancelButton = android.widget.Button(activity).apply {
            text = "Cancel"
            setOnClickListener { finishFailure() }
        }
        btnContainer.addView(cancelButton)
        container.addView(btnContainer)

        dialog = AlertDialog.Builder(activity)
            .setView(container)
            .setCancelable(false)
            .create()

        webView?.setTag(dialog)

        dialog?.setOnDismissListener {
            handler.removeCallbacksAndMessages(null)
            if (!resolved.get()) {
                resolved.set(true)
                try { webView?.destroy() } catch (e: Exception) {}
                try { onFinished?.invoke(false) } catch (e: Exception) {}
            }
        }

        dialog?.show()
        dialog?.window?.apply {
            setLayout(dialogW, dialogH)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
            listOf("cf_clearance", "cf_chl_rc_ni", "cf_chl_prog").forEach { name ->
                setCookie(targetHost, "$name=; Max-Age=0; expires=Thu, 01 Jan 1970 00:00:00 GMT")
            }
            flush()
        }
        webView?.loadUrl(targetUrl)
        handler.postDelayed(cookiePollRunnable, POLL_INTERVAL_MS)

        handler.postDelayed({ finishFailure() }, SOLVER_TIMEOUT_MS)
    }

    private fun moveCursor(
        pos: CursorPosHolder,
        cursorView: View,
        cursorSize: Int,
        container: View,
        dx: Float,
        dy: Float
    ) {
        pos.x = (pos.x + dx).coerceIn(0f, container.width.toFloat())
        pos.y = (pos.y + dy).coerceIn(0f, container.height.toFloat())
        cursorView.translationX = pos.x - cursorSize / 2f
        cursorView.translationY = pos.y - cursorSize / 2f
    }

    private fun clickAtCursor(
        pos: CursorPosHolder,
        webView: WebView?
    ) {
        val wv = webView ?: return
        val x = pos.x
        val y = pos.y
        val t = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(t, t + 120, MotionEvent.ACTION_UP, x, y, 0)
        try {
            wv.dispatchTouchEvent(down)
            wv.dispatchTouchEvent(up)
        } catch (e: Exception) {
            Log.e(TAG, "clickAtCursor: ${e.message}")
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
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                allowContentAccess = true
                allowFileAccess = true
                loadsImagesAutomatically = true
                userAgentString = "Mozilla/5.0 (Linux; Android 13; Pixel 5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                mediaPlaybackRequiresUserGesture = false
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    if (!resolved.get()) statusText?.text = "Loading... $newProgress%"
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = false

                override fun onPageFinished(view: WebView?, url: String?) {
                    if (resolved.get()) return
                    val title = view?.title ?: ""
                    Log.d(TAG, "onPageFinished title='$title' url=$url")

                    if (isChallengeTitle(title)) {
                        statusText?.text = "Challenge active - solve the CAPTCHA above"
                        extractAndFinish()
                        return
                    }

                    statusText?.text = "Page loaded - checking cookies..."
                    extractAndFinish()

                    url?.let {
                        try {
                            val uri = Uri.parse(it)
                            val altHost = "${uri.scheme}://${uri.host}"
                            if (altHost != targetHost) {
                                val altCookies = CookieManager.getInstance().getCookie(altHost) ?: ""
                                if (altCookies.contains("cf_clearance")) {
                                    if (!resolved.get()) {
                                        resolved.set(true)
                                        handler.removeCallbacksAndMessages(null)
                                        val ua = webView?.settings?.userAgentString ?: ""
                                        TMFCFStore.save(altCookies, ua, altHost)
                                        try { webView?.destroy() } catch (e: Exception) {}
                                        try { dialog?.dismiss() } catch (e: Exception) {}
                                        try { onFinished?.invoke(true) } catch (e: Exception) {}
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "alt host extract: ${e.message}")
                        }
                    }
                }
            }
        }
    }

    fun dismiss() {
        handler.removeCallbacksAndMessages(null)
        try { webView?.apply { stopLoading(); destroy() } } catch (e: Exception) {}
        webView = null
        try { dialog?.dismiss() } catch (e: Exception) {}
        dialog = null
    }
}

suspend fun showTMFCFBypassDialogAndWait(url: String): Boolean = withContext(Dispatchers.Main) {
    val activity = CommonActivity.activity as? AppCompatActivity
    if (activity == null || activity.isFinishing || activity.isDestroyed) {
        Log.e(TAG, "No activity available to show CF dialog")
        return@withContext false
    }
    suspendCancellableCoroutine { cont ->
        val cfDialog = TMFCFDialog(url) { success ->
            if (cont.isActive) cont.resume(success)
        }
        try {
            cfDialog.show(activity)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show CF dialog: ${e.message}")
            if (cont.isActive) cont.resume(false)
        }
        cont.invokeOnCancellation { cfDialog.dismiss() }
    }
}

suspend fun tmfGet(
    url: String,
    headers: Map<String, String> = emptyMap()
): NiceResponse {
    val targetHost = try {
        val uri = Uri.parse(url)
        "${uri.scheme}://${uri.host}"
    } catch (e: Exception) {
        Log.e(TAG, "URL parse failed: ${e.message}")
        url
    }

    fun buildCfHeaders(): Map<String, String> {
        val h = headers.toMutableMap()
        if (!h.containsKey("Accept")) {
            h["Accept"] = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
        }
        if (!h.containsKey("Accept-Language")) {
            h["Accept-Language"] = "en-US,en;q=0.5"
        }
        h["sec-ch-ua-mobile"] = "?1"
        h["sec-ch-ua-platform"] = "\"Android\""
        TMFCFStore.getCookies()?.let { cookies ->
            h["Cookie"] = cookies
        }
        TMFCFStore.getUserAgent()?.let { ua ->
            h["User-Agent"] = ua
        } ?: run {
            if (!h.containsKey("User-Agent")) {
                h["User-Agent"] = "Mozilla/5.0 (Linux; Android 13; Pixel 5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
            }
        }
        return h
    }

    var response = try {
        app.get(url, headers = buildCfHeaders(), timeout = 30_000L)
    } catch (e: Exception) {
        Log.e(TAG, "First request failed: ${e.message}")
        throw e
    }

    if (!isCloudflareBlocked(response)) return response

    Log.d(TAG, "Cloudflare blocked (HTTP ${response.code}) for $url - triggering bypass")

    cfBypassMutex.withLock {
        val cachedCookies = TMFCFStore.getCookies()
        if (cachedCookies != null) {
            response = try {
                app.get(url, headers = buildCfHeaders(), timeout = 30_000L)
            } catch (e: Exception) {
                Log.e(TAG, "Retry with cached cookies failed: ${e.message}")
                throw e
            }
            if (!isCloudflareBlocked(response)) {
                Log.d(TAG, "Succeeded with cookies from another coroutine")
                return response
            }
        }

        TMFCFStore.clear()
        val bypassSuccess = showTMFCFBypassDialogAndWait(url)

        if (!bypassSuccess) {
            Log.e(TAG, "CF bypass dialog failed/cancelled")
            return@withLock
        }

        for (attempt in 1..2) {
            response = try {
                app.get(url, headers = buildCfHeaders(), timeout = 30_000L)
            } catch (e: Exception) {
                Log.e(TAG, "Retry $attempt failed: ${e.message}")
                throw e
            }
            if (!isCloudflareBlocked(response)) {
                Log.d(TAG, "Request succeeded after CF bypass (attempt $attempt)")
                return@withLock
            }
            Log.e(TAG, "Still CF-blocked after retry $attempt")
        }
    }

    return response
}

fun initTMFCFBypass(context: Context) {
    TMFCFStore.init(context)
    Log.d(TAG, "TMF CF bypass initialized (cursor-enabled, TTL=15h)")
}
