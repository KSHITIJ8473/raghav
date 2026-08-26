package com.kdesa

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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.DialogFragment
import com.lagradost.api.Log
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.app
import com.lagradost.nicehttp.NiceResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

private const val TAG = "Kdesa_CF"

private const val POLL_INTERVAL_MS = 1000L
private const val SOLVER_TIMEOUT_MS = 120_000L
private const val COOKIE_TTL_MS = 60L * 60 * 1000

internal object KdesaCF {
    private const val PREFS = "kdesa_cf"

    private var prefs: android.content.SharedPreferences? = null

    data class CfSession(val cookies: String, val userAgent: String, val ts: Long)

    private val sessions = ConcurrentHashMap<String, CfSession>()
    private val bypassMutex = Mutex()

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            for ((_, entry) in prefs!!.all) {
                if (entry is String && entry.contains("|||")) {
                    val parts = entry.split("|||")
                    if (parts.size == 3) {
                        val host = parts[0]
                        sessions[host] = CfSession(parts[1], parts[2], System.currentTimeMillis())
                    }
                }
            }
        }
    }

    fun saveSession(host: String, cookies: String, ua: String) {
        sessions[host] = CfSession(cookies, ua, System.currentTimeMillis())
        prefs?.edit()?.putString("cf_$host", "$host|||$cookies|||$ua")?.apply()
        Log.d(TAG, "saved CF session for $host")
    }

    fun clearSession(host: String) {
        sessions.remove(host)
        prefs?.edit()?.remove("cf_$host")?.apply()
    }

    private fun validSession(host: String): CfSession? {
        val s = sessions[host] ?: return null
        if (!s.cookies.contains("cf_clearance")) return null
        if (System.currentTimeMillis() - s.ts > COOKIE_TTL_MS) return null
        return s
    }

    fun isBlocked(res: NiceResponse): Boolean {
        if (res.code != 403 && res.code != 503) return false
        return try {
            val body = res.text.lowercase()
            body.contains("just a moment") ||
                body.contains("cf-mitigated") ||
                body.contains("checking your browser") ||
                body.contains("_cf_chl")
        } catch (e: Exception) {
            true
        }
    }

    private fun buildHeaders(url: String, base: Map<String, String>): Map<String, String> {
        val host = Uri.parse(url).host ?: ""
        val h = base.toMutableMap()
        val session = validSession(host)
        if (session != null) {
            h["Cookie"] = session.cookies
            if (h["User-Agent"] == null) h["User-Agent"] = session.userAgent
        }
        if (h["User-Agent"] == null) {
            h["User-Agent"] =
                "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
        }
        return h
    }

    suspend fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        timeout: Long = 30_000L
    ): NiceResponse {
        val host = Uri.parse(url).host ?: ""
        var res = app.get(url, headers = buildHeaders(url, headers), timeout = timeout)
        if (!isBlocked(res)) return res
        Log.e(TAG, "GET $url -> ${res.code} blocked by Cloudflare, starting bypass")

        bypassMutex.withLock {
            validSession(host)?.let {
                res = app.get(url, headers = buildHeaders(url, headers), timeout = timeout)
                if (!isBlocked(res)) return res
            }
            clearSession(host)
            if (!showBypassDialog("https://$host/")) {
                Log.e(TAG, "CF bypass dialog failed for $host")
                return res
            }
            repeat(2) {
                res = app.get(url, headers = buildHeaders(url, headers), timeout = timeout)
                if (!isBlocked(res)) return res
            }
        }
        return res
    }

    suspend fun post(
        url: String,
        headers: Map<String, String> = emptyMap(),
        data: Map<String, String> = emptyMap(),
        timeout: Long = 30_000L
    ): NiceResponse {
        val host = Uri.parse(url).host ?: ""
        var res = app.post(url, headers = buildHeaders(url, headers), data = data, timeout = timeout)
        if (!isBlocked(res)) return res
        Log.e(TAG, "POST $url -> ${res.code} blocked by Cloudflare, starting bypass")

        bypassMutex.withLock {
            validSession(host)?.let {
                res = app.post(url, headers = buildHeaders(url, headers), data = data, timeout = timeout)
                if (!isBlocked(res)) return res
            }
            clearSession(host)
            if (!showBypassDialog("https://$host/")) {
                Log.e(TAG, "CF bypass dialog failed for $host")
                return res
            }
            repeat(2) {
                res = app.post(url, headers = buildHeaders(url, headers), data = data, timeout = timeout)
                if (!isBlocked(res)) return res
            }
        }
        return res
    }
}

class KdesaCFDialog(private val targetUrl: String, private val onFinished: (Boolean) -> Unit) :
    DialogFragment() {

    private var webView: WebView? = null
    private var statusText: TextView? = null
    private var progressBar: ProgressBar? = null
    private val handler = Handler(Looper.getMainLooper())
    private var cookiesSaved = false
    private val startedAt = System.currentTimeMillis()

    private val cookiePoll = object : Runnable {
        override fun run() {
            if (cookiesSaved) return
            if (System.currentTimeMillis() - startedAt > SOLVER_TIMEOUT_MS) {
                updateStatus("Timed out. Close and retry.")
                handler.removeCallbacks(this)
                onFinishedSafe(false)
                dismissAllowingStateLoss()
                return
            }
            try {
                val uri = Uri.parse(targetUrl)
                val host = "${uri.scheme}://${uri.host}"
                val cookies = CookieManager.getInstance().getCookie(host) ?: ""
                if (cookies.contains("cf_clearance")) {
                    saveCookiesAndDismiss(cookies, host)
                    return
                }
            } catch (e: Exception) {
                Log.e(TAG, "cookie poll: ${e.message}")
            }
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    private fun onFinishedSafe(ok: Boolean) {
        if (!cookiesSaved) {
            cookiesSaved = true
            onFinished(ok)
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return AlertDialog.Builder(requireContext()).create()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreateView(
        inflater: android.view.LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val dp = resources.displayMetrics.density
        val screenH = resources.displayMetrics.heightPixels
        val webViewHeight = (screenH * 0.7).toInt()

        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * dp).toInt(), (16 * dp).toInt(), (20 * dp).toInt(), (12 * dp).toInt())
            setBackgroundColor(Color.parseColor("#14141F"))
            layoutParams = ViewGroup.LayoutParams(-1, -2)
        }

        root.addView(TextView(requireContext()).apply {
            text = "Cloudflare Bypass"
            textSize = 18f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, (4 * dp).toInt())
        })

        TextView(requireContext()).apply {
            text = "Solving challenge for ${Uri.parse(targetUrl).host}. The dialog closes automatically."
            textSize = 12f
            setTextColor(Color.parseColor("#A0A0B0"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, (10 * dp).toInt())
        }.also { statusText = it; root.addView(it) }

        progressBar = ProgressBar(requireContext()).also {
            root.addView(it, LinearLayout.LayoutParams(-1, (4 * dp).toInt()))
        }

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
            flush()
        }
        webView?.loadUrl(targetUrl)
        handler.postDelayed(cookiePoll, POLL_INTERVAL_MS)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildWebView(): WebView {
        return WebView(requireContext()).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                userAgentString = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
            }
            webChromeClient = object : WebChromeClient() {}
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = false

                override fun onPageFinished(view: WebView?, url: String?) {
                    updateStatus("Checking…")
                    try {
                        val uri = Uri.parse(url ?: targetUrl)
                        val host = "${uri.scheme}://${uri.host}"
                        CookieManager.getInstance().flush()
                        val cookies = CookieManager.getInstance().getCookie(host) ?: ""
                        if (cookies.contains("cf_clearance")) {
                            saveCookiesAndDismiss(cookies, host)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "onPageFinished: ${e.message}")
                    }
                }
            }
        }
    }

    private fun saveCookiesAndDismiss(cookies: String, host: String) {
        if (cookiesSaved) return
        cookiesSaved = true
        handler.removeCallbacks(cookiePoll)
        val ua = webView?.settings?.userAgentString ?: ""
        KdesaCF.saveSession(host, cookies, ua)
        updateStatus("Success! Cookie captured.")
        webView?.postDelayed({
            if (isAdded) {
                onFinished(true)
                dismissAllowingStateLoss()
            }
        }, 800)
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        handler.removeCallbacks(cookiePoll)
        onFinishedSafe(false)
    }

    private fun updateStatus(msg: String) {
        activity?.runOnUiThread {
            statusText?.text = msg
        }
    }

    override fun onDestroyView() {
        handler.removeCallbacks(cookiePoll)
        webView?.apply { stopLoading(); destroy() }
        webView = null
        super.onDestroyView()
    }
}

private suspend fun showBypassDialog(url: String): Boolean = withContext(Dispatchers.Main) {
    val activity = CommonActivity.activity as? AppCompatActivity
    if (activity == null || activity.isFinishing || activity.isDestroyed) {
        Log.e(TAG, "no activity available for CF dialog")
        return@withContext false
    }
    suspendCancellableCoroutine { cont ->
        val dialog = KdesaCFDialog(url) { success ->
            if (cont.isActive) cont.resume(success)
        }
        try {
            dialog.show(activity.supportFragmentManager, "KdesaCFDialog")
        } catch (e: Exception) {
            Log.e(TAG, "failed to show CF dialog: ${e.message}")
            if (cont.isActive) cont.resume(false)
        }
        cont.invokeOnCancellation { dialog.dismissAllowingStateLoss() }
    }
}
