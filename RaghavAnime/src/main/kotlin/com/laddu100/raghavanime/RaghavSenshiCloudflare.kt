package com.laddu100.raghavanime

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.lagradost.api.Log
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.app
import com.lagradost.nicehttp.NiceResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.coroutines.resume

private val cfBlockerPhrases = listOf(
    "just a moment", "checking your browser", "ddos-guard",
    "attention required", "verify you are human", "cloudflare",
    "challenge-platform", "enable javascript", "turnstile"
)

private const val COOKIE_TTL_MS = 45 * 60 * 1000L

// cookies are stored per host, senshi.to, the vidcloud api and the stream cdn
// each run their own challenge
private object SenshiCookieStore {
    private const val PREFS_NAME = "SenshiCFBypass"

    internal class Saved(val host: String, val cookies: String, val ua: String, val ts: Long)

    private var prefs: android.content.SharedPreferences? = null
    private val saved = mutableMapOf<String, Saved>()

    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        prefs?.all?.forEach { (key, value) ->
            if (value is String && value.contains("||")) {
                val parts = value.split("||", limit = 3)
                if (parts.size == 3 && now - parts[2].toLong() < COOKIE_TTL_MS) {
                    saved[key] = Saved(key, parts[0], parts[1], parts[2].toLong())
                }
            }
        }
    }

    @Synchronized
    fun get(host: String): Saved? {
        val entry = saved[host] ?: return null
        if (System.currentTimeMillis() - entry.ts > COOKIE_TTL_MS) {
            saved.remove(host)
            prefs?.edit()?.remove(host)?.apply()
            return null
        }
        return entry
    }

    @Synchronized
    fun save(host: String, cookies: String, ua: String) {
        saved[host] = Saved(host, cookies, ua, System.currentTimeMillis())
        prefs?.edit()?.putString(host, "$cookies||$ua||${System.currentTimeMillis()}")?.apply()
    }

    @Synchronized
    fun clear(host: String) {
        saved.remove(host)
        prefs?.edit()?.remove(host)?.apply()
    }
}

private fun isSenshiCloudflareBlocked(response: NiceResponse): Boolean {
    if (response.code != 403 && response.code != 503) return false
    val body = response.text.lowercase()
    return cfBlockerPhrases.any { body.contains(it) }
}

private fun hostOf(url: String): String = try {
    Uri.parse(url).host ?: url
} catch (e: Exception) {
    url
}

private val cfBypassMutex = Mutex()

class SenshiCFDialog(
    private val targetUrl: String,
    private val onFinished: ((Boolean) -> Unit)? = null
) : BottomSheetDialogFragment() {

    companion object {
        private const val POLL_INTERVAL_MS = 2000L
        private const val POLL_TIMEOUT_MS = 120000L
    }

    private var webView: WebView? = null
    private var statusText: TextView? = null
    private var progressBar: ProgressBar? = null
    private val handler = Handler(Looper.getMainLooper())
    private var cookiesSaved = false
    private var pollElapsedMs = 0L

    private val targetHost: String by lazy {
        try {
            val uri = Uri.parse(targetUrl)
            "${uri.scheme}://${uri.host}"
        } catch (e: Exception) {
            targetUrl
        }
    }

    private val cookiePollRunnable = object : Runnable {
        override fun run() {
            if (cookiesSaved || !isAdded) return
            CookieManager.getInstance().flush()
            val cookieStr = CookieManager.getInstance().getCookie(targetHost) ?: ""

            when {
                cookieStr.contains("cf_clearance") -> saveCookiesAndDismiss(cookieStr)
                pollElapsedMs >= POLL_TIMEOUT_MS -> {
                    Log.w("Senshi", "cookie poll timed out after ${pollElapsedMs / 1000}s")
                    updateStatus("Timed out. Solve the CAPTCHA then tap retry.")
                }
                else -> scheduleNextPoll()
            }
        }
    }

    private fun scheduleNextPoll() {
        pollElapsedMs += POLL_INTERVAL_MS
        updateStatus("Waiting for cookies... (${pollElapsedMs / 1000}s)")
        handler.postDelayed(cookiePollRunnable, POLL_INTERVAL_MS)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return super.onCreateDialog(savedInstanceState).apply {
            window?.setDimAmount(0.0f)
            window?.addFlags(32)
            (this as? BottomSheetDialog)?.behavior?.apply {
                state = BottomSheetBehavior.STATE_HIDDEN
                skipCollapsed = false
                peekHeight = 0
            }
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(-1, -1)
        dialog?.findViewById<View?>(com.google.android.material.R.id.design_bottom_sheet)?.let { bs ->
            bs.layoutParams?.height = -1
            bs.requestLayout()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val dp = resources.displayMetrics.density
        val screenH = resources.displayMetrics.heightPixels
        val webViewHeight = (screenH * 0.7).toInt()

        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * dp).toInt(), (20 * dp).toInt(), (24 * dp).toInt(), (16 * dp).toInt())
            setBackgroundColor(Color.parseColor("#1A1A2E"))
            layoutParams = ViewGroup.LayoutParams(-1, -2)
        }

        root.addView(TextView(requireContext()).apply {
            text = "Senshi - Cloudflare Bypass"
            textSize = 18f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, (8 * dp).toInt())
        })

        TextView(requireContext()).apply {
            text = "Loading challenge page..."
            textSize = 13f
            setTextColor(Color.parseColor("#A0A0B0"))
            setPadding(0, 0, 0, (4 * dp).toInt())
        }.also { statusText = it; root.addView(it) }

        root.addView(TextView(requireContext()).apply {
            text = "Solve any CAPTCHA shown below. This closes automatically when done."
            textSize = 11f
            setTextColor(Color.parseColor("#707080"))
            setPadding(0, 0, 0, (12 * dp).toInt())
        })

        ProgressBar(requireContext(), null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = (12 * dp).toInt() }
        }.also { progressBar = it; root.addView(it) }

        FrameLayout(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(-1, webViewHeight)
            webView = buildWebView()
            addView(webView, FrameLayout.LayoutParams(-1, -1))
            root.addView(this)
        }

        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
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
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildWebView(): WebView {
        return WebView(requireContext()).apply {
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
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    if (!cookiesSaved) updateStatus("Loading... $newProgress%")
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = false

                override fun onPageFinished(view: WebView?, url: String?) {
                    if (cookiesSaved) return
                    val title = view?.title ?: ""

                    if (cfBlockerPhrases.any { title.lowercase().contains(it) }) {
                        updateStatus("Challenge active - solve the CAPTCHA above")
                        return
                    }

                    CookieManager.getInstance().flush()
                    val cookies = CookieManager.getInstance().getCookie(targetHost) ?: ""
                    if (cookies.contains("cf_clearance")) {
                        handler.removeCallbacks(cookiePollRunnable)
                        saveCookiesAndDismiss(cookies)
                    }
                }
            }
        }
    }

    private fun saveCookiesAndDismiss(cookieStr: String) {
        if (cookiesSaved) return
        cookiesSaved = true
        handler.removeCallbacks(cookiePollRunnable)

        val ua = webView?.settings?.userAgentString ?: ""
        SenshiCookieStore.save(hostOf(targetUrl), cookieStr, ua)
        updateStatus("Done! Cookies saved.")

        webView?.postDelayed({
            if (isAdded) {
                onFinished?.invoke(true)
                dismissAllowingStateLoss()
            }
        }, 1500)
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        if (!cookiesSaved) {
            Log.w("Senshi", "bypass dialog dismissed without cookies")
            handler.removeCallbacks(cookiePollRunnable)
            onFinished?.invoke(false)
        }
    }

    private fun updateStatus(msg: String) {
        activity?.runOnUiThread {
            statusText?.apply {
                text = msg
                if (msg.startsWith("Done")) {
                    setTextColor(Color.parseColor("#4CAF50"))
                    progressBar?.visibility = View.GONE
                } else {
                    setTextColor(Color.parseColor("#A0A0B0"))
                    progressBar?.visibility = View.VISIBLE
                }
            }
        }
    }

    override fun onDestroyView() {
        handler.removeCallbacks(cookiePollRunnable)
        webView?.apply { stopLoading(); destroy() }
        webView = null
        super.onDestroyView()
    }
}

private suspend fun showBypassDialogAndWait(url: String): Boolean = withContext(Dispatchers.Main) {
    val activity = CommonActivity.activity as? AppCompatActivity
    if (activity == null || activity.isFinishing || activity.isDestroyed) {
        return@withContext false
    }
    suspendCancellableCoroutine { cont ->
        val dialog = SenshiCFDialog(url) { success ->
            if (cont.isActive) cont.resume(success)
        }
        try {
            dialog.show(activity.supportFragmentManager, "SenshiCFDialog")
        } catch (e: Exception) {
            Log.e("Senshi", "failed to show bypass dialog: ${e.message}")
            if (cont.isActive) cont.resume(false)
        }
        cont.invokeOnCancellation { dialog.dismissAllowingStateLoss() }
    }
}

internal fun senshiHeaders(base: Map<String, String>, url: String): Map<String, String> {
    val h = base.toMutableMap()
    val entry = SenshiCookieStore.get(hostOf(url))
    if (entry != null) {
        h["Cookie"] = entry.cookies
        if (entry.ua.isNotBlank()) {
            h["User-Agent"] = entry.ua
        }
    }
    return h
}

internal fun initSenshiCFBypass(context: Context) {
    try {
        SenshiCookieStore.init(context)
    } catch (e: Exception) {
        Log.e("Senshi", "bypass init failed: ${e.message}")
    }
}

internal suspend fun cfGet(
    url: String,
    headers: Map<String, String> = emptyMap(),
    timeout: Long = 20_000L
): NiceResponse {
    val host = hostOf(url)

    var response = app.get(url, headers = senshiHeaders(headers, url), timeout = timeout)

    if (!isSenshiCloudflareBlocked(response)) return response

    cfBypassMutex.withLock {
        if (SenshiCookieStore.get(host) != null) {
            response = app.get(url, headers = senshiHeaders(headers, url), timeout = timeout)
            if (!isSenshiCloudflareBlocked(response)) return response
            SenshiCookieStore.clear(host)
        }

        if (!showBypassDialogAndWait(url)) {
            return@withLock
        }

        repeat(2) {
            response = app.get(url, headers = senshiHeaders(headers, url), timeout = timeout)
            if (!isSenshiCloudflareBlocked(response)) return response
        }
        Log.e("Senshi", "still blocked on $host after bypass")
    }

    return response
}

internal suspend fun cfPost(
    url: String,
    body: String,
    headers: Map<String, String> = emptyMap(),
    timeout: Long = 20_000L
): NiceResponse {
    val host = hostOf(url)

    fun requestBody() = body.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())

    var response = app.post(url, requestBody = requestBody(), headers = senshiHeaders(headers, url), timeout = timeout)

    if (!isSenshiCloudflareBlocked(response)) return response

    cfBypassMutex.withLock {
        if (SenshiCookieStore.get(host) != null) {
            response = app.post(url, requestBody = requestBody(), headers = senshiHeaders(headers, url), timeout = timeout)
            if (!isSenshiCloudflareBlocked(response)) return response
            SenshiCookieStore.clear(host)
        }

        if (!showBypassDialogAndWait(url)) {
            return@withLock
        }

        repeat(2) {
            response = app.post(url, requestBody = requestBody(), headers = senshiHeaders(headers, url), timeout = timeout)
            if (!isSenshiCloudflareBlocked(response)) return response
        }
        Log.e("Senshi", "still blocked on $host after bypass")
    }

    return response
}
