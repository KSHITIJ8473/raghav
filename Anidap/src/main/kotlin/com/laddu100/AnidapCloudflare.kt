package com.laddu100

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
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
import androidx.fragment.app.FragmentActivity
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
import kotlin.coroutines.resume

// 
//  Anidap Anti-Bot Bypass System
//  
//
//  anidap.lol & chad.anidap.lol use a CUSTOM anti-bot cookie called `_amx_id`
//  (NOT Cloudflare's cf_clearance). The cookie is a JWT-like token that binds
//  to the client's IP + User-Agent. When the anti-bot flags a request, it
//  returns HTTP 403 with body: {"error":"bot_detected","status":403} (37 bytes).
//
//  Detection triggers:
//  - Empty/missing User-Agent  → 403 "missing_ua" (35 bytes)
//  - Flagged IP + okhttp TLS fingerprint (JA3) → 403 "bot_detected" (37 bytes)
//  - The cookie binds to IP+UA, so we MUST use the SAME UA that the WebView
//    used when it obtained the cookie.
//
//  Architecture (adapted from AniDbCloudflare.kt):
//  1. AnidapCFStore     — persistent cookie/UA storage with TTL (SharedPreferences)
//  2. AnidapCFDialog    — BottomSheet WebView that loads a chad.anidap.lol API
//                         URL with a REAL browser TLS fingerprint + UA, captures
//                         the resulting `_amx_id` cookie + WebView UA
//  3. cfAppGet()        — request wrapper: try → detect 403 → bypass → retry
//  4. cfBypassMutex     — prevents multiple concurrent bypass dialogs
//
//  Key differences from AniDbCloudflare:
//  - Cookie name is `_amx_id` (not cf_clearance)
//  - Target host is `chad.anidap.lol` (the API subdomain that issues the cookie)
//  - The cookie is host-only (no Domain= attribute), so we MUST capture it
//    from chad.anidap.lol, not anidap.lol
//  - We MUST use the WebView's exact UA for all subsequent app.get() calls
//    because the cookie validates against (IP, UA) pairs
// 

private const val TAG = "Anidap_CFBypass"

// The API subdomain that issues and validates the _amx_id cookie
private const val CHAD_HOST = "https://chad.anidap.lol"

// A lightweight API endpoint that the WebView can load to trigger cookie issuance.
// We use the servers endpoint with a known slug — it returns small JSON and sets
// the _amx_id cookie on first hit.
private const val CF_TRIGGER_URL = "$CHAD_HOST/rest/api/servers?id=one-piece-p8k27&epNum=1"

// Phrases that indicate the anti-bot blocked the request
private val BLOCK_PHRASES = listOf(
    "bot_detected", "missing_ua", "access denied", "forbidden",
    "rate_limit", "blocked", "just a moment", "checking your browser",
    "cloudflare", "challenge-platform", "enable javascript"
)

// 

private object AnidapCFStore {
    private const val PREFS_NAME = "AnidapCFBypass"
    private const val KEY_COOKIES = "cf_cookies"
    private const val KEY_UA = "cf_user_agent"
    private const val KEY_HOST = "cf_cookie_host"
    private const val KEY_TIMESTAMP = "cf_timestamp"
    // _amx_id expires in 24h (set by server), but we refresh sooner to be safe
    private const val COOKIE_TTL_MS = 45 * 60 * 1000L // 45 minutes

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
            Log.d(TAG, "Stored cookies expired (age=${(System.currentTimeMillis() - cachedTimestamp) / 1000}s)")
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
        Log.d(TAG, "Saved _amx_id cookies for $host (UA=${userAgent.take(50)}...)")
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

// ─

/**
 * Returns true if the response looks like it was blocked by the anti-bot.
 * Checks for HTTP 403/503 AND the presence of bot-detection phrases in the body.
 * Also catches the specific 37-byte `{"error":"bot_detected","status":403}` response.
 */
fun isAnidapBlocked(response: NiceResponse): Boolean {
    if (response.code != 403 && response.code != 503) return false
    val body = response.text.lowercase()
    // Quick check: the bot_detected response is exactly 37 bytes
    if (response.text.length < 200) {
        Log.d(TAG, "Blocked response (code=${response.code}, size=${response.text.length}): ${response.text.take(100)}")
        return BLOCK_PHRASES.any { body.contains(it) } || body.contains("error")
    }
    return BLOCK_PHRASES.any { body.contains(it) }
}

// 

private val cfBypassMutex = Mutex()

// ──

class AnidapCFDialog(
    private val targetUrl: String = CF_TRIGGER_URL,
    private val onFinished: ((Boolean) -> Unit)? = null
) : BottomSheetDialogFragment() {

    companion object {
        private const val POLL_INTERVAL_MS = 1500L
        private const val POLL_TIMEOUT_MS = 90000L
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
            Log.d(TAG, "Poll [${pollElapsedMs}ms] cookies for $targetHost → ${cookieStr.take(100)}")

            when {
                cookieStr.contains("_amx_id") -> {
                    // Cookie issued — but let's wait a moment to make sure the WebView
                    // also got a successful response (not a 403 in a real browser)
                    if (pollElapsedMs >= 3000) saveCookiesAndDismiss(cookieStr)
                    else scheduleNextPoll()
                }
                pollElapsedMs >= POLL_TIMEOUT_MS -> {
                    updateStatus("⏱️ Timed out. Try opening anidap.lol in a browser, then tap Bypass again.")
                }
                else -> scheduleNextPoll()
            }
        }
    }

    private fun scheduleNextPoll() {
        pollElapsedMs += POLL_INTERVAL_MS
        updateStatus("⏳ Loading anidap.lol in browser… (${pollElapsedMs / 1000}s)")
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

        // Title
        root.addView(TextView(requireContext()).apply {
            text = "🛡️ Anidap – Anti-Bot Bypass"
            textSize = 18f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, (8 * dp).toInt())
        })

        // Status text
        TextView(requireContext()).apply {
            text = "Loading anidap.lol in browser…"
            textSize = 13f
            setTextColor(Color.parseColor("#A0A0B0"))
            setPadding(0, 0, 0, (4 * dp).toInt())
        }.also { statusText = it; root.addView(it) }

        // Hint
        root.addView(TextView(requireContext()).apply {
            text = "This solves the 'bot_detected' 403 error. The dialog closes automatically once the cookie is captured."
            textSize = 11f
            setTextColor(Color.parseColor("#707080"))
            setPadding(0, 0, 0, (12 * dp).toInt())
        })

        // Progress bar
        ProgressBar(requireContext(), null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = (12 * dp).toInt() }
        }.also { progressBar = it; root.addView(it) }

        // WebView container
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
        // Clear stale _amx_id cookies before loading
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
            // Clear stale anti-bot cookies
            listOf("_amx_id", "cf_clearance", "__ddg1_", "__ddg2_").forEach { name ->
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
                // Use the default WebView UA (real Chrome on Android) — this is critical
                // because the _amx_id cookie binds to (IP, UA). We MUST use this exact
                // UA for all subsequent app.get() calls.
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
                    Log.d(TAG, "onPageFinished title='$title' url=$url")

                    updateStatus("✏️ Page loaded – checking cookies…")
                    CookieManager.getInstance().flush()

                    // Check cookies from the target host
                    val cookiesFromTarget = CookieManager.getInstance().getCookie(targetHost) ?: ""
                    val cookiesFromUrl = url?.let {
                        try {
                            val uri = Uri.parse(it)
                            CookieManager.getInstance().getCookie("${uri.scheme}://${uri.host}")
                        } catch (e: Exception) { null }
                    } ?: ""

                    val bestCookies = when {
                        cookiesFromTarget.contains("_amx_id") -> cookiesFromTarget
                        cookiesFromUrl.contains("_amx_id") -> cookiesFromUrl
                        else -> null
                    }

                    if (bestCookies != null) {
                        handler.removeCallbacks(cookiePollRunnable)
                        // Wait a moment to ensure the cookie is fully committed
                        handler.postDelayed({
                            if (!cookiesSaved) saveCookiesAndDismiss(bestCookies)
                        }, 1500)
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
        AnidapCFStore.save(cookieStr, ua, targetHost)

        Log.d(TAG, "Saved _amx_id cookies: ${cookieStr.take(120)}")
        Log.d(TAG, "Saved WebView UA: ${ua.take(80)}")
        updateStatus("Done! Cookie captured.")

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
                if (msg.startsWith("")) {
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

// 

private suspend fun showCFBypassDialogAndWait(url: String = CF_TRIGGER_URL): Boolean = withContext(Dispatchers.Main) {
    val activity = CommonActivity.activity as? AppCompatActivity
    if (activity == null || activity.isFinishing || activity.isDestroyed) {
        Log.e(TAG, "No activity available to show Anidap CF dialog")
        return@withContext false
    }
    suspendCancellableCoroutine { cont ->
        val dialog = AnidapCFDialog(url) { success ->
            if (cont.isActive) cont.resume(success)
        }
        try {
            dialog.show(activity.supportFragmentManager, "AnidapCFDialog")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show Anidap CF dialog: ${e.message}")
            if (cont.isActive) cont.resume(false)
        }
        cont.invokeOnCancellation { dialog.dismissAllowingStateLoss() }
    }
}

// 
//
//  This wraps app.get() with automatic anti-bot bypass for chad.anidap.lol:
//  1. If we have stored _amx_id cookies → add them + use stored WebView UA
//  2. If response is blocked (403 bot_detected) → show bypass dialog → retry
//  3. Mutex ensures only one dialog shows at a time
//  4. Up to 2 retry attempts after bypass
// 

suspend fun cfAppGet(
    url: String,
    headers: Map<String, String> = emptyMap(),
    timeout: Long = 30_000L
): NiceResponse {
    val targetHost = try {
        val uri = Uri.parse(url)
        "${uri.scheme}://${uri.host}"
    } catch (e: Exception) { url }

    // Build headers with stored _amx_id cookies + WebView UA + browser fingerprint
    fun buildCfHeaders(): Map<String, String> {
        val h = headers.toMutableMap()
        // Browser fingerprint headers
        if (!h.containsKey("Accept")) {
            h["Accept"] = "application/json, text/plain, */*"
        }
        if (!h.containsKey("Accept-Language")) {
            h["Accept-Language"] = "en-US,en;q=0.9"
        }
        if (!h.containsKey("Origin")) {
            h["Origin"] = "https://anidap.lol"
        }
        if (!h.containsKey("Sec-Fetch-Dest")) {
            h["Sec-Fetch-Dest"] = "empty"
            h["Sec-Fetch-Mode"] = "cors"
            h["Sec-Fetch-Site"] = "same-site"
        }
        h["sec-ch-ua-mobile"] = "?1"
        h["sec-ch-ua-platform"] = "\"Android\""
        // Stored _amx_id cookies + WebView UA (must match the UA that
        // was used when the cookie was issued, otherwise the cookie is rejected)
        AnidapCFStore.getCookies()?.let { cookies ->
            h["Cookie"] = cookies
            Log.d(TAG, "Using stored _amx_id cookies for $targetHost")
        }
        AnidapCFStore.getUserAgent()?.let { ua ->
            h["User-Agent"] = ua
        } ?: run {
            // Default browser-like UA if no stored UA
            if (!h.containsKey("User-Agent")) {
                h["User-Agent"] = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
            }
        }
        return h
    }

    // First attempt (with stored cookies if available)
    var response = try {
        app.get(url, headers = buildCfHeaders(), timeout = timeout)
    } catch (e: Exception) {
        Log.e(TAG, "Request failed: ${e.message}")
        throw e
    }

    if (!isAnidapBlocked(response)) return response

    // Blocked — need to bypass
    Log.d(TAG, "Anidap anti-bot blocked (HTTP ${response.code}) for $url — triggering bypass")

    // Use mutex so only ONE bypass dialog shows at a time
    cfBypassMutex.withLock {
        // Double-check: another coroutine may have already bypassed while we waited
        val cachedCookies = AnidapCFStore.getCookies()
        if (cachedCookies != null) {
            Log.d(TAG, "Cookies were refreshed by another coroutine — retrying")
            response = try { app.get(url, headers = buildCfHeaders(), timeout = timeout) } catch (e: Exception) { throw e }
            if (!isAnidapBlocked(response)) return response
        }

        // Clear stale cookies and show bypass dialog
        AnidapCFStore.clear()
        val bypassSuccess = showCFBypassDialogAndWait()

        if (!bypassSuccess) {
            Log.e(TAG, "Anidap CF bypass dialog failed/cancelled")
            return@withLock // response is still the blocked one
        }

        // Retry with new cookies (up to 2 attempts)
        for (attempt in 1..2) {
            Log.d(TAG, "Retrying request with new _amx_id cookies (attempt $attempt)")
            response = try { app.get(url, headers = buildCfHeaders(), timeout = timeout) } catch (e: Exception) { throw e }
            if (!isAnidapBlocked(response)) {
                Log.d(TAG, "Request succeeded after Anidap CF bypass (attempt $attempt)")
                return@withLock
            }
            Log.e(TAG, "Still blocked after retry $attempt")
        }
    }

    return response
}

// 

fun initAnidapCFBypass(context: Context) {
    AnidapCFStore.init(context)
    Log.d(TAG, "Anidap CF bypass initialized")
}
