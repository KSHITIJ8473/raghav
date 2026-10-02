package com.laddu100.rareanimes

import android.annotation.SuppressLint
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
import android.webkit.WebResourceResponse
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
import com.lagradost.cloudstream3.ui.settings.Globals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

internal class RAIResolvedLink(
    val url: String,
    val kind: String,
    val pageUrl: String? = null
)

private val ALLOWED_HOSTS = listOf(
    "rareanimes.mov",
    "animetoonhindi.com",
    "codedew.com",
    "argon.razorshell.space",
    "groovy.monster",
    "hubcloud.ist",
    "hubcloud.cx",
    "gamerxyt.com",
    "pixeldrain.net",
    "pixeldrain.dev",
    "pixeldra.in",
    "cloudflarestorage.com",
    "googleusercontent.com",
    "hbplay.pages.dev",
    "workers.dev",
    "gofile.io",
    "api.gofile.io",
    "mediafire.com",
    "mega.nz",
    "jwpcdn.com",
    "cloudflare.com",
    "cloudflareinsights.com",
    "cdnjs.cloudflare.com",
    "googleapis.com",
    "gstatic.com",
    "google.com",
    "fontawesome.com",
    "pages.dev"
)

private val AD_URL_HINTS = listOf(
    "bonuscaf.com", "rm358.com", "/sftouch", "/cuid/", "adsterra", "propellerads",
    "popads", "popunder", "/sw.js", "onesignal", "pushnami", "/ads/", "doubleclick"
)

private fun isAllowedHost(url: String): Boolean {
    return try {
        val host = Uri.parse(url).host ?: return false
        if (AD_URL_HINTS.any { url.contains(it, ignoreCase = true) }) return false
        ALLOWED_HOSTS.any { host == it || host.endsWith(".$it") }
    } catch (_: Exception) {
        false
    }
}

internal fun classifyVideoUrl(url: String): String? {
    val u = url.substringBefore("#")
    return when {
        u.contains("groovy.monster") && u.contains(".m3u8") -> "hls"
        u.endsWith(".m3u8") || u.contains(".m3u8?") -> "hls"
        u.contains("mediafire.com") && u.contains("/download") -> "mediafire"
        u.contains(".workers.dev/") -> "worker"
        u.contains("cloudflarestorage.com/") && u.contains("X-Amz-") -> "r2"
        u.contains("pixeldrain.net/api/file/") || u.contains("pixeldrain.dev/api/file/") -> "pixeldrain"
        u.contains("pixeldra.in/api/file/") -> "pixeldrain"
        u.contains("pixeldrain.net/u/") || u.contains("pixeldrain.dev/u/") -> "pixeldrain_page"
        u.contains("pixeldra.in/u/") -> "pixeldrain_page"
        u.contains("googleusercontent.com/") && !u.contains("lh3.") -> "gvideo"
        else -> null
    }
}

private class CursorPos { var x: Float = 0f; var y: Float = 0f }

@SuppressLint("InflateParams")
private class RAIResolverDialog(
    private val startUrl: String,
    private val onFinished: ((RAIResolvedLink?) -> Unit)? = null
) {
    private var dialog: AlertDialog? = null
    private var webView: WebView? = null
    private var statusText: TextView? = null
    private var urlText: TextView? = null
    private val handler = Handler(Looper.getMainLooper())
    private val resolved = java.util.concurrent.atomic.AtomicBoolean(false)
    private var captured: RAIResolvedLink? = null
    private var lastPageUrl: String? = null

    private fun tryCapture(url: String): Boolean {
        val kind = classifyVideoUrl(url) ?: return false
        if (captured == null) {
            captured = RAIResolvedLink(url.substringBefore("#"), kind, lastPageUrl)
            statusText?.text = "Link captured - finishing..."
            handler.postDelayed({ finishWithCapture() }, 600)
            return true
        }
        return false
    }

    private fun finishWithCapture() {
        if (!resolved.compareAndSet(false, true)) return
        handler.removeCallbacksAndMessages(null)
        try { webView?.stopLoading() } catch (_: Exception) {}
        try { webView?.destroy() } catch (_: Exception) {}
        try { dialog?.dismiss() } catch (_: Exception) {}
        try { onFinished?.invoke(captured) } catch (_: Exception) {}
    }

    private fun finishManual() {
        if (captured != null) {
            finishWithCapture()
        } else {
            if (!resolved.compareAndSet(false, true)) return
            handler.removeCallbacksAndMessages(null)
            try { webView?.stopLoading() } catch (_: Exception) {}
            try { webView?.destroy() } catch (_: Exception) {}
            try { dialog?.dismiss() } catch (_: Exception) {}
            try { onFinished?.invoke(null) } catch (_: Exception) {}
        }
    }

    private fun finishCancel() {
        captured = null
        if (!resolved.compareAndSet(false, true)) return
        handler.removeCallbacksAndMessages(null)
        try { webView?.stopLoading() } catch (_: Exception) {}
        try { webView?.destroy() } catch (_: Exception) {}
        try { dialog?.dismiss() } catch (_: Exception) {}
        try { onFinished?.invoke(null) } catch (_: Exception) {}
    }

    private fun smartBack() {
        val wv = webView ?: return
        try {
            if (wv.canGoBack()) {
                statusText?.text = "Going back..."
                wv.goBack()
            } else {
                statusText?.text = "Already at the first page"
            }
        } catch (_: Exception) {}
    }

    private fun reloadPage() {
        statusText?.text = "Reloading..."
        try { webView?.reload() } catch (_: Exception) {}
    }

    private fun navButton(
        activity: AppCompatActivity,
        label: String,
        color: Int,
        onClick: () -> Unit
    ): Button {
        return Button(activity).apply {
            text = label
            textSize = 13f
            background = GradientDrawable().apply {
                cornerRadius = 14f
                setColor(color)
            }
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, (42 * activity.resources.displayMetrics.density).toInt(), 1f)
            setOnClickListener { onClick() }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun show(activity: AppCompatActivity) {
        val dp = activity.resources.displayMetrics.density
        val screenH = activity.resources.displayMetrics.heightPixels
        val screenW = activity.resources.displayMetrics.widthPixels
        val portraitW = (screenH * 0.60f).toInt()
        val dialogW = minOf((screenW * 0.95f).toInt(), portraitW)
        val dialogH = (screenH * 0.92f).toInt()
        val chromeH = (54 * dp).toInt() + (30 * dp).toInt() + (46 * dp).toInt() + (34 * dp).toInt()
        val webViewHeight = (dialogH - chromeH).coerceAtLeast((screenH * 0.45f).toInt())

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((10 * dp).toInt(), (8 * dp).toInt(), (10 * dp).toInt(), (6 * dp).toInt()
            )
        }

        val topBar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, (4 * dp).toInt())
        }
        topBar.addView(TextView(activity).apply {
            text = "Resolving Link"
            textSize = 14f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })
        val closeTop = Button(activity).apply {
            text = "X"
            textSize = 15f
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFFE5484D.toInt())
            }
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams((38 * dp).toInt(), (38 * dp).toInt())
            setOnClickListener { finishCancel() }
        }
        topBar.addView(closeTop)
        container.addView(topBar)

        val statusView = TextView(activity).apply {
            text = "Loading the link page..."
            textSize = 11f
            setTextColor(Color.parseColor("#A0A0B0"))
            setPadding(0, 0, 0, (2 * dp).toInt())
        }
        statusText = statusView
        container.addView(statusView)

        val urlView = TextView(activity).apply {
            text = startUrl.substringBefore("?").takeLast(52)
            textSize = 9f
            setTextColor(Color.parseColor("#707080"))
            setPadding(0, 0, 0, (4 * dp).toInt())
            maxLines = 1
        }
        urlText = urlView
        container.addView(urlView)

        container.addView(ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = (4 * dp).toInt() }
        })

        val isTv = try { Globals.isLayout(Globals.TV) } catch (_: Throwable) { false }

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
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.argb(160, 255, 50, 50))
                    setStroke((2 * dp).toInt(), Color.WHITE)
                }
                elevation = 999f
            }
            webContainer.addView(cursor)

            val pos = CursorPos()
            pos.x = webViewHeight / 2f
            pos.y = webContainer.width / 2f
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

            val step = 10f * dp
            webContainer.setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> { moveCursor(pos, cursor, cursorSize, webContainer, 0f, -step); true }
                    KeyEvent.KEYCODE_DPAD_DOWN -> { moveCursor(pos, cursor, cursorSize, webContainer, 0f, step); true }
                    KeyEvent.KEYCODE_DPAD_LEFT -> { moveCursor(pos, cursor, cursorSize, webContainer, -step, 0f); true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { moveCursor(pos, cursor, cursorSize, webContainer, step, 0f); true }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { clickAtCursor(pos, webView); true }
                    KeyEvent.KEYCODE_BACK -> { smartBack(); true }
                    else -> false
                }
            }
            webContainer.requestFocus()
        }
        container.addView(webContainer)

        val navRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.topMargin = (6 * dp).toInt() }
        }
        navRow.addView(navButton(activity, "Back", 0xFF2E7D32.toInt()) { smartBack() })
        navRow.addView(navButton(activity, "Reload", 0xFF6D5ACF.toInt()) { reloadPage() }.apply {
            layoutParams = (layoutParams as LinearLayout.LayoutParams).also { it.marginStart = (5 * dp).toInt() }
        })
        navRow.addView(navButton(activity, "Done", 0xFF0B57D0.toInt()) { finishManual() }.apply {
            layoutParams = (layoutParams as LinearLayout.LayoutParams).also { it.marginStart = (5 * dp).toInt() }
        })
        container.addView(navRow)

        container.addView(TextView(activity).apply {
            text = "Popup ads are blocked automatically. Back closes an ad page, X closes this window."
            textSize = 10f
            setTextColor(Color.parseColor("#707080"))
            setPadding(0, (5 * dp).toInt(), 0, 0)
        })

        dialog = AlertDialog.Builder(activity).setView(container).setCancelable(false).create()
        webView?.setTag(dialog)
        dialog?.setOnKeyListener { _, keyCode, _ ->
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                if (webView?.canGoBack() == true) {
                    smartBack()
                } else {
                    finishCancel()
                }
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
                try { onFinished?.invoke(captured) } catch (_: Exception) {}
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
            flush()
        }
        webView?.loadUrl(startUrl)
        handler.postDelayed({ finishManual() }, 120_000L)
    }

    private fun moveCursor(pos: CursorPos, cursorView: View, cursorSize: Int, container: View, dx: Float, dy: Float) {
        pos.x = (pos.x + dx).coerceIn(0f, container.width.toFloat())
        pos.y = (pos.y + dy).coerceIn(0f, container.height.toFloat())
        cursorView.translationX = pos.x - cursorSize / 2f
        cursorView.translationY = pos.y - cursorSize / 2f
    }

    private fun clickAtCursor(pos: CursorPos, webView: WebView?) {
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
            isFocusable = true
            isFocusableInTouchMode = true
            requestFocus()
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                allowContentAccess = true
                allowFileAccess = true
                userAgentString = RAI_UA
                mediaPlaybackRequiresUserGesture = false
                setSupportMultipleWindows(false)
                javaScriptCanOpenWindowsAutomatically = false
                useWideViewPort = true
                loadWithOverviewMode = true
                blockNetworkImage = true
                loadsImagesAutomatically = false
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    if (!resolved.get()) statusText?.text = "Loading... $newProgress%"
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val url = request?.url?.toString() ?: return false
                    if (tryCapture(url)) return true
                    if (!isAllowedHost(url)) {
                        statusText?.text = "Blocked a popup ad"
                        handler.post {
                            if (webView?.canGoBack() == true) smartBack()
                        }
                        return true
                    }
                    lastPageUrl = url
                    urlText?.text = url.substringBefore("?").takeLast(52)
                    return false
                }

                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    val url = request?.url?.toString() ?: return null
                    if (tryCapture(url)) {
                        handler.post { finishWithCapture() }
                    }
                    return null
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    if (resolved.get()) return
                    if (!url.isNullOrBlank()) lastPageUrl = url
                    statusText?.text = "Page loaded - waiting for the video link..."
                    url?.let { tryCapture(it) }
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

internal suspend fun showRAIResolverPopupAndWait(url: String): RAIResolvedLink? =
    withContext(Dispatchers.Main) {
        val activity = CommonActivity.activity as? AppCompatActivity
        if (activity == null || activity.isFinishing || activity.isDestroyed) {
            return@withContext null
        }
        suspendCancellableCoroutine { cont ->
            val resolverDialog = RAIResolverDialog(url) { link ->
                if (cont.isActive) cont.resume(link)
            }
            try { resolverDialog.show(activity) } catch (_: Exception) {
                if (cont.isActive) cont.resume(null)
            }
            cont.invokeOnCancellation { resolverDialog.dismiss() }
        }
    }
