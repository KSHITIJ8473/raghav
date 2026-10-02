package com.laddu100.rareanimes

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
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.ui.settings.Globals
import com.lagradost.nicehttp.NiceResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

private val CF_CHALLENGE_TITLES = listOf(
    "just a moment", "just a moment...", "checking your browser",
    "attention required", "ddos-guard", "one more step"
)

private const val COOKIE_TTL_MS = 15L * 60 * 60 * 1000
private const val SOLVER_TIMEOUT_MS = 120_000L
private const val POLL_INTERVAL_MS = 1000L
private const val CURSOR_STEP_DP = 10f

internal object RAICFStore {
    private const val KEY_PREFIX = "RAI_CF_"
    private const val KEY_COOKIES = "RAI_CF_COOKIES_MAP"
    private const val KEY_UA = "RAI_CF_UA_MAP"
    private const val KEY_TIME = "RAI_CF_TIME_MAP"

    @Volatile private var cookiesMap: MutableMap<String, String> = hashMapOf()
    @Volatile private var uaMap: MutableMap<String, String> = hashMapOf()
    @Volatile private var timeMap: MutableMap<String, Long> = hashMapOf()
    @Volatile private var initialized = false

    fun init() {
        if (initialized) return
        initialized = true
        try {
            val saved = CloudStreamApp.getKey<String>(KEY_COOKIES)
            if (!saved.isNullOrBlank()) {
                saved.split("|||").forEach { entry ->
                    val parts = entry.split("::=", limit = 2)
                    if (parts.size == 2 && parts[1].contains("cf_clearance")) {
                        cookiesMap[parts[0]] = parts[1]
                    }
                }
            }
            val savedUa = CloudStreamApp.getKey<String>(KEY_UA)
            if (!savedUa.isNullOrBlank()) {
                savedUa.split("|||").forEach { entry ->
                    val parts = entry.split("::=", limit = 2)
                    if (parts.size == 2) uaMap[parts[0]] = parts[1]
                }
            }
            val savedTime = CloudStreamApp.getKey<String>(KEY_TIME)
            if (!savedTime.isNullOrBlank()) {
                savedTime.split("|||").forEach { entry ->
                    val parts = entry.split("::=", limit = 2)
                    val t = parts.getOrNull(1)?.toLongOrNull()
                    if (parts.size == 2 && t != null) timeMap[parts[0]] = t
                }
            }
        } catch (_: Exception) {}
    }

    private fun persist() {
        try {
            CloudStreamApp.setKey(KEY_COOKIES, cookiesMap.entries.joinToString("|||") { "${it.key}::=${it.value}" })
            CloudStreamApp.setKey(KEY_UA, uaMap.entries.joinToString("|||") { "${it.key}::=${it.value}" })
            CloudStreamApp.setKey(KEY_TIME, timeMap.entries.joinToString("|||") { "${it.key}::=${it.value}" })
        } catch (_: Exception) {}
    }

    fun getCookies(host: String): String? {
        val cookies = cookiesMap[host] ?: return null
        val saved = timeMap[host] ?: 0L
        if (System.currentTimeMillis() - saved > COOKIE_TTL_MS) {
            cookiesMap.remove(host)
            uaMap.remove(host)
            timeMap.remove(host)
            persist()
            return null
        }
        return cookies
    }

    fun getUserAgent(host: String): String? = uaMap[host]?.takeIf { it.isNotBlank() }

    fun save(host: String, cookies: String, userAgent: String) {
        cookiesMap[host] = cookies
        uaMap[host] = userAgent
        timeMap[host] = System.currentTimeMillis()
        persist()
    }

    fun clear(host: String? = null) {
        if (host == null) {
            cookiesMap.clear()
            uaMap.clear()
            timeMap.clear()
        } else {
            cookiesMap.remove(host)
            uaMap.remove(host)
            timeMap.remove(host)
        }
        persist()
    }

    fun hasAnyCookies(): Boolean = cookiesMap.isNotEmpty()
}

internal fun isRAICloudflareBlocked(response: NiceResponse): Boolean {
    val code = response.code
    if (code == 503) return true
    val body = try { response.text.lowercase() } catch (_: Exception) { "" }
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

private class CursorPosHolder { var x: Float = 0f; var y: Float = 0f }

@SuppressLint("InflateParams")
private class RAICFDialog(
    private val targetUrl: String,
    private val onFinished: ((Boolean) -> Unit)? = null
) {
    private var dialog: AlertDialog? = null
    private var webView: WebView? = null
    private var statusText: TextView? = null
    private val handler = Handler(Looper.getMainLooper())
    private val resolved = java.util.concurrent.atomic.AtomicBoolean(false)
    private var pollElapsedMs = 0L

    private val targetHost: String by lazy {
        try {
            val uri = Uri.parse(targetUrl)
            "${uri.scheme}://${uri.host}"
        } catch (_: Exception) { targetUrl }
    }

    private fun extractAndFinish() {
        if (resolved.get()) return
        try {
            CookieManager.getInstance().flush()
            val cookieStr = CookieManager.getInstance().getCookie(targetHost) ?: ""
            if (cookieStr.contains("cf_clearance")) {
                finishSuccess(cookieStr)
            }
        } catch (_: Exception) {}
    }

    private fun finishSuccess(cookieStr: String) {
        if (!resolved.compareAndSet(false, true)) return
        handler.removeCallbacksAndMessages(null)
        val ua = webView?.settings?.userAgentString ?: ""
        RAICFStore.save(Uri.parse(targetHost).host ?: "", cookieStr, ua)
        try { webView?.destroy() } catch (_: Exception) {}
        try { (webView?.getTag() as? Dialog)?.dismiss() } catch (_: Exception) {}
        try { onFinished?.invoke(true) } catch (_: Exception) {}
    }

    private fun finishFailure() {
        if (!resolved.compareAndSet(false, true)) return
        handler.removeCallbacksAndMessages(null)
        try { webView?.destroy() } catch (_: Exception) {}
        try { dialog?.dismiss() } catch (_: Exception) {}
        try { onFinished?.invoke(false) } catch (_: Exception) {}
    }

    private val cookiePollRunnable = object : Runnable {
        override fun run() {
            if (resolved.get() || dialog == null || dialog?.isShowing != true) return
            pollElapsedMs += POLL_INTERVAL_MS
            extractAndFinish()
            if (!resolved.get()) {
                if (pollElapsedMs >= SOLVER_TIMEOUT_MS) {
                    finishFailure()
                } else {
                    statusText?.text = "Waiting... (${pollElapsedMs / 1000}s)"
                    handler.postDelayed(this, POLL_INTERVAL_MS)
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun show(activity: AppCompatActivity) {
        val dp = activity.resources.displayMetrics.density
        val screenH = activity.resources.displayMetrics.heightPixels
        val dialogW = (activity.resources.displayMetrics.widthPixels * 0.95f).toInt()
        val dialogH = (screenH * 0.9f).toInt()
        val webViewHeight = (screenH * 0.65f).toInt()

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * dp).toInt(), (12 * dp).toInt(), (16 * dp).toInt(), (8 * dp).toInt())
        }

        container.addView(TextView(activity).apply {
            text = "Cloudflare Bypass"
            textSize = 16f; setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, (8 * dp).toInt())
        })

        val statusView = TextView(activity).apply {
            text = "Loading..."
            textSize = 12f; setTextColor(Color.parseColor("#A0A0B0"))
            setPadding(0, 0, 0, (4 * dp).toInt())
        }
        statusText = statusView
        container.addView(statusView)

        val isTv = try { Globals.isLayout(Globals.TV) } catch (_: Throwable) { false }
        container.addView(TextView(activity).apply {
            text = if (isTv) "Use D-pad to move cursor, OK to click."
            else "Solve the CAPTCHA below, then tap Done."
            textSize = 11f; setTextColor(Color.parseColor("#707080"))
            setPadding(0, 0, 0, (8 * dp).toInt())
        })

        container.addView(ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = (8 * dp).toInt() }
        })

        val webContainer = FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(-1, webViewHeight)
            isFocusable = true; isFocusableInTouchMode = true
        }
        webView = buildWebView(activity)
        webContainer.addView(webView, FrameLayout.LayoutParams(-1, -1))

        if (isTv) {
            val cursorSize = (22 * dp).toInt()
            val cursor = View(activity).apply {
                layoutParams = FrameLayout.LayoutParams(cursorSize, cursorSize)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.argb(160, 255, 50, 50))
                    setStroke((2 * dp).toInt(), Color.WHITE)
                }
                elevation = 999f
            }
            webContainer.addView(cursor)

            val pos = CursorPosHolder()
            pos.x = webViewHeight / 2f; pos.y = webViewHeight / 2f
            cursor.translationX = pos.x - cursorSize / 2f
            cursor.translationY = pos.y - cursorSize / 2f

            webContainer.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    webContainer.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    pos.x = webContainer.width / 2f; pos.y = webContainer.height / 2f
                    cursor.translationX = pos.x - cursorSize / 2f
                    cursor.translationY = pos.y - cursorSize / 2f
                }
            })

            val step = CURSOR_STEP_DP * dp
            webContainer.setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> { moveCursor(pos, cursor, cursorSize, webContainer, 0f, -step); true }
                    KeyEvent.KEYCODE_DPAD_DOWN -> { moveCursor(pos, cursor, cursorSize, webContainer, 0f, step); true }
                    KeyEvent.KEYCODE_DPAD_LEFT -> { moveCursor(pos, cursor, cursorSize, webContainer, -step, 0f); true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { moveCursor(pos, cursor, cursorSize, webContainer, step, 0f); true }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { clickAtCursor(pos, webView); true }
                    else -> false
                }
            }
            webContainer.requestFocus()
        }
        container.addView(webContainer)

        val btnContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.topMargin = (8 * dp).toInt() }
        }
        btnContainer.addView(Button(activity).apply {
            text = "Back"
            setOnClickListener {
                try { webView?.goBack() } catch (_: Exception) {}
            }
        })
        btnContainer.addView(Button(activity).apply {
            text = "Reload"
            setOnClickListener {
                try { webView?.reload() } catch (_: Exception) {}
            }
        })
        btnContainer.addView(Button(activity).apply {
            text = "Done"
            setOnClickListener {
                CookieManager.getInstance().flush()
                extractAndFinish()
                if (!resolved.get()) statusText?.text = "No cf_clearance found."
            }
        })
        btnContainer.addView(Button(activity).apply {
            text = "Cancel"
            setOnClickListener { finishFailure() }
        })
        container.addView(btnContainer)

        dialog = AlertDialog.Builder(activity).setView(container).setCancelable(false).create()
        webView?.setTag(dialog)
        dialog?.setOnKeyListener { _, keyCode, _ ->
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                try { webView?.goBack() } catch (_: Exception) {}
                true
            } else {
                false
            }
        }
        dialog?.setOnDismissListener {
            handler.removeCallbacksAndMessages(null)
            if (!resolved.get()) {
                resolved.set(true)
                try { webView?.destroy() } catch (_: Exception) {}
                try { onFinished?.invoke(false) } catch (_: Exception) {}
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

    private fun moveCursor(pos: CursorPosHolder, cursorView: View, cursorSize: Int, container: View, dx: Float, dy: Float) {
        pos.x = (pos.x + dx).coerceIn(0f, container.width.toFloat())
        pos.y = (pos.y + dy).coerceIn(0f, container.height.toFloat())
        cursorView.translationX = pos.x - cursorSize / 2f
        cursorView.translationY = pos.y - cursorSize / 2f
    }

    private fun clickAtCursor(pos: CursorPosHolder, webView: WebView?) {
        val wv = webView ?: return
        val t = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, pos.x, pos.y, 0)
        val up = MotionEvent.obtain(t, t + 120, MotionEvent.ACTION_UP, pos.x, pos.y, 0)
        try { wv.dispatchTouchEvent(down); wv.dispatchTouchEvent(up) } catch (_: Exception) {}
        finally { down.recycle(); up.recycle() }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildWebView(context: Context): WebView {
        return WebView(context).apply {
            isFocusable = true; isFocusableInTouchMode = true; requestFocus()
            settings.apply {
                javaScriptEnabled = true; domStorageEnabled = true
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                allowContentAccess = true; allowFileAccess = true; loadsImagesAutomatically = true
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
                    if (isChallengeTitle(title)) {
                        statusText?.text = "Challenge active - solve the CAPTCHA"
                        extractAndFinish()
                        return
                    }
                    statusText?.text = "Page loaded - checking cookies..."
                    extractAndFinish()
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

internal suspend fun showRAICFBypassDialogAndWait(url: String): Boolean = withContext(Dispatchers.Main) {
    val activity = CommonActivity.activity as? AppCompatActivity
    if (activity == null || activity.isFinishing || activity.isDestroyed) {
        return@withContext false
    }
    suspendCancellableCoroutine { cont ->
        val cfDialog = RAICFDialog(url) { success ->
            if (cont.isActive) cont.resume(success)
        }
        try { cfDialog.show(activity) } catch (_: Exception) {
            if (cont.isActive) cont.resume(false)
        }
        cont.invokeOnCancellation { cfDialog.dismiss() }
    }
}
