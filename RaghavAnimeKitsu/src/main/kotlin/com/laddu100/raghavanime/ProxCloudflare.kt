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
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.app
import com.lagradost.nicehttp.NiceResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

// Cloudflare bypass for prox.anikage.cc, the resolver the site's player uses for the
// token-only servers (kiwi/uwu/megg/dib/wave). A WebView solves the challenge once and
// the cf_clearance cookie + matching UA are attached to every emitted prox link.
private const val PROX_CF_TAG = "AniKage_ProxCF"

private val PROX_CF_BLOCKER_PHRASES = listOf(
    "just a moment", "checking your browser", "ddos-guard",
    "attention required", "verify you are human", "cloudflare",
    "challenge-platform", "cf-ray", "enable javascript"
)

private val PROX_CF_CHALLENGE_TITLES = listOf(
    "just a moment", "just a moment...", "checking your browser",
    "attention required", "ddos-guard", "one more step",
    "performing security verification"
)

object ProxCFStore {
    private const val PREFS_NAME = "AniKageProxCFBypass"
    private const val KEY_COOKIES = "cf_cookies"
    private const val KEY_UA = "cf_user_agent"
    private const val KEY_HOST = "cf_cookie_host"
    private const val KEY_TIMESTAMP = "cf_timestamp"
    private const val COOKIE_TTL_MS = 45 * 60 * 1000L

    private var prefs: android.content.SharedPreferences? = null
    private var cachedCookies: String? = null
    private var cachedUA: String? = null
    private var cachedHost: String? = null
    private var cachedTimestamp: Long = 0L

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            cachedCookies = prefs?.getString(KEY_COOKIES, null)
            cachedUA = prefs?.getString(KEY_UA, null)
            cachedHost = prefs?.getString(KEY_HOST, null)
            cachedTimestamp = prefs?.getLong(KEY_TIMESTAMP, 0L) ?: 0L
        }
    }

    fun getCookies(): String? {
        if (cachedCookies.isNullOrBlank()) return null
        if (System.currentTimeMillis() - cachedTimestamp > COOKIE_TTL_MS) {
            clear()
            return null
        }
        return cachedCookies
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
    }

    fun clear() {
        cachedCookies = null
        cachedUA = null
        cachedHost = null
        cachedTimestamp = 0L
        prefs?.edit()?.clear()?.apply()
    }

    fun hasValidCookies(): Boolean = getCookies() != null
}

fun isProxCloudflareBlocked(response: NiceResponse): Boolean {
    if (response.code != 403 && response.code != 503) return false
    val body = response.text.lowercase()
    return PROX_CF_BLOCKER_PHRASES.any { body.contains(it) }
}

private fun isProxChallengeTitle(title: String): Boolean {
    val lower = title.lowercase()
    return PROX_CF_CHALLENGE_TITLES.any { lower.contains(it) }
}

private val proxBypassMutex = Mutex()

class ProxCFDialog(
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
                    updateStatus("Timed out. Try again after solving the verification.")
                }
                else -> scheduleNextPoll()
            }
        }
    }

    private fun scheduleNextPoll() {
        pollElapsedMs += POLL_INTERVAL_MS
        updateStatus("Waiting for cookies… (${pollElapsedMs / 1000}s)")
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

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
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
            text = "AniKage – One-time verification"
            textSize = 18f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, (8 * dp).toInt())
        })

        TextView(requireContext()).apply {
            text = "Loading verification page…"
            textSize = 13f
            setTextColor(Color.parseColor("#A0A0B0"))
            setPadding(0, 0, 0, (4 * dp).toInt())
        }.also { statusText = it; root.addView(it) }

        root.addView(TextView(requireContext()).apply {
            text = "The Kiwi/Uwu/Megg/Dib/Wave servers need a one-time browser check. Wait for it to finish – the dialog closes automatically."
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
                userAgentString = settings.userAgentString
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    if (!cookiesSaved) updateStatus("Loading… $newProgress%")
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = false

                override fun onPageFinished(view: WebView?, url: String?) {
                    if (cookiesSaved) return
                    val title = view?.title ?: ""

                    if (isProxChallengeTitle(title)) {
                        updateStatus("Verification active – please wait for it to complete")
                        return
                    }

                    updateStatus("Page loaded – checking cookies…")
                    CookieManager.getInstance().flush()

                    val cookiesFromTarget = CookieManager.getInstance().getCookie(targetHost) ?: ""
                    val cookiesFromUrl = url?.let {
                        try {
                            val uri = Uri.parse(it)
                            CookieManager.getInstance().getCookie("${uri.scheme}://${uri.host}")
                        } catch (e: Exception) { null }
                    } ?: ""

                    val bestCookies = when {
                        cookiesFromTarget.contains("cf_clearance") -> cookiesFromTarget
                        cookiesFromUrl.contains("cf_clearance") -> cookiesFromUrl
                        else -> null
                    }

                    if (bestCookies != null) {
                        handler.removeCallbacks(cookiePollRunnable)
                        saveCookiesAndDismiss(bestCookies)
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
        ProxCFStore.save(cookieStr, ua, targetHost)

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

private suspend fun showProxCBypassDialogAndWait(url: String): Boolean = withContext(Dispatchers.Main) {
    val activity = CommonActivity.activity as? AppCompatActivity
    if (activity == null || activity.isFinishing || activity.isDestroyed) {
        return@withContext false
    }
    suspendCancellableCoroutine { cont ->
        val dialog = ProxCFDialog(url) { success ->
            if (cont.isActive) cont.resume(success)
        }
        try {
            dialog.show(activity.supportFragmentManager, "ProxCFDialog")
        } catch (e: Exception) {
            if (cont.isActive) cont.resume(false)
        }
        cont.invokeOnCancellation { dialog.dismissAllowingStateLoss() }
    }
}

// GET with auto Cloudflare-bypass; returns the response either way
// ("bad url"/"invalid payload" app responses are informational, not fatal)
suspend fun proxGet(
    url: String,
    headers: Map<String, String> = emptyMap()
): NiceResponse {
    val targetHost = try {
        val uri = Uri.parse(url)
        "${uri.scheme}://${uri.host}"
    } catch (e: Exception) { url }

    fun buildCfHeaders(): Map<String, String> {
        val h = headers.toMutableMap()

        if (!h.containsKey("Accept")) {
            h["Accept"] = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
        }
        if (!h.containsKey("Accept-Language")) {
            h["Accept-Language"] = "en-US,en;q=0.5"
        }
        if (!h.containsKey("Referer")) {
            h["Referer"] = "https://anikage.cc/"
        }
        if (!h.containsKey("Origin")) {
            h["Origin"] = "https://anikage.cc"
        }
        h["sec-ch-ua-mobile"] = "?1"
        h["sec-ch-ua-platform"] = "\"Android\""

        ProxCFStore.getCookies()?.let { cookies ->
            h["Cookie"] = cookies
        }
        ProxCFStore.getUserAgent()?.let { ua ->
            h["User-Agent"] = ua
        } ?: run {
            if (!h.containsKey("User-Agent")) {
                h["User-Agent"] = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
            }
        }
        return h
    }

    var response = try {
        app.get(url, headers = buildCfHeaders())
    } catch (e: Exception) {
        throw e
    }

    if (!isProxCloudflareBlocked(response)) return response

    proxBypassMutex.withLock {
        val cachedCookies = ProxCFStore.getCookies()
        if (cachedCookies != null && ProxCFStore.getHost() == targetHost) {
            response = try { app.get(url, headers = buildCfHeaders()) } catch (e: Exception) { throw e }
            if (!isProxCloudflareBlocked(response)) return response
        }

        ProxCFStore.clear()
        val bypassSuccess = showProxCBypassDialogAndWait("https://prox.anikage.cc/")

        if (!bypassSuccess) {
            return@withLock
        }

        for (attempt in 1..2) {
            response = try { app.get(url, headers = buildCfHeaders()) } catch (e: Exception) { throw e }
            if (!isProxCloudflareBlocked(response)) {
                return@withLock
            }
        }
    }

    return response
}

// Cookie/UA headers for emitted prox playback links (empty before the first bypass)
fun proxPlaybackHeaders(): Map<String, String> {
    val h = LinkedHashMap<String, String>()
    ProxCFStore.getCookies()?.let { h["Cookie"] = it }
    ProxCFStore.getUserAgent()?.let { h["User-Agent"] = it }
    return h
}

fun initProxCFBypass(context: Context) {
    ProxCFStore.init(context)
}
