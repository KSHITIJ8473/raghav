package com.laddu100.raghavanime

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.api.Log
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

// the gateway rotates its handshake constants server side, so the site's own
// player bundle runs in a hidden webview and talks to it for us
object RaghavSenshiVhost {

    private const val TAG = "RaghavAnime"
    private const val FALLBACK_BUNDLE = "https://cdn.vidcloud.se/vjs/vendor.js"
    private const val BRIDGE_PATH = "/__senshi_bridge"

    private const val BUNDLE_TTL = 10 * 60 * 1000L
    private const val PAGE_TIMEOUT = 10_000L
    private const val OPEN_TIMEOUT = 12_000L
    private const val PROBE_TIMEOUT = 2_500L
    private const val PAGE_MAX_AGE = 4 * 60 * 1000L
    private const val NATIVE_MUTE_MS = 5 * 60 * 1000L
    private const val OPEN_CACHE_TTL = 4 * 60 * 1000L

    // only used when the system webview cannot be queried, real devices never hit this
    private const val FALLBACK_UA =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/154.0.0.0 Mobile Safari/537.36"

    private val mainHandler = Handler(Looper.getMainLooper())
    private var appContext: Context? = null

    @Volatile
    private var origin = "https://senshi.to"

    @Volatile
    private var bundleUrl: String? = null
    @Volatile
    private var bundleBody: String? = null
    @Volatile
    private var bundleTs = 0L

    private var webView: WebView? = null
    @Volatile
    private var pageUp = false
    @Volatile
    private var pageMode: Boolean = false
    @Volatile
    private var pageBuiltAt = 0L
    @Volatile
    private var loadedOrigin: String? = null
    @Volatile
    private var loadedBundle: String? = null
    @Volatile
    private var bundleLoadFailed = false
    @Volatile
    private var nativeMutedUntil = 0L
    private var readySignal: CompletableDeferred<Boolean> = CompletableDeferred()

    private val openMutex = Mutex()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<String>>()

    // the gateway throttles how much one session may open, repeats are served
    // from memory so a reloaded episode never touches it twice
    private class OpenCacheEntry(val sources: List<RaghavSenshi.VidcloudSource>, val ts: Long)
    private val openCache = ConcurrentHashMap<Int, OpenCacheEntry>()

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var cachedUa: String? = null

    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    suspend fun refreshDomain() {
        FirebaseDomainHelper.getDomain("senshi")?.let {
            val clean = it.removeSuffix("/")
            if (clean.isNotBlank()) origin = clean
        }
    }

    // the gateway rejects user agents it does not like and rotates those rules, so
    // the webview's own user agent is used everywhere. it updates itself with the
    // system webview and always matches the engine actually making the requests
    fun browserUa(): String {
        cachedUa?.let { return it }
        val ctx = appContext ?: return FALLBACK_UA
        val raw = try {
            WebSettings.getDefaultUserAgent(ctx)
        } catch (_: Exception) {
            null
        }
        val clean = raw
            ?.replace("; wv", "")
            ?.replace(Regex("""\s+Version/\d+\.\d+"""), "")
            ?.trim()
        if (!clean.isNullOrBlank() && clean.startsWith("Mozilla/5.0") && clean.contains("Chrome/")) {
            cachedUa = clean
            return clean
        }
        return FALLBACK_UA
    }

    private fun baseHeaders(): Map<String, String> = mapOf(
        "User-Agent" to browserUa(),
        "Accept" to "*/*",
        "Accept-Language" to "en-US,en;q=0.9",
        "Origin" to origin,
        "Referer" to "$origin/"
    )

    // the bundle moves host occasionally, the live homepage is the source of truth
    private suspend fun discoverBundleUrl(fresh: Boolean): String? {
        val now = System.currentTimeMillis()
        if (!fresh && bundleUrl != null && now - bundleTs < BUNDLE_TTL) {
            return bundleUrl
        }
        var url: String? = null
        try {
            val res = cfGet("$origin/", headers = baseHeaders(), timeout = 12_000L)
            if (res.code == 200) {
                url = Regex("""src=["']([^"']*vidcloud[^"']*\.js[^"']*)["']""")
                    .find(res.text)?.groupValues?.get(1)
                    ?.let { java.net.URI(origin).resolve(it).toString() }
            }
        } catch (_: Exception) {
        }
        if (url.isNullOrBlank()) {
            url = bundleUrl ?: FALLBACK_BUNDLE
        }
        bundleUrl = url
        bundleTs = now
        return url
    }

    private suspend fun fetchBundleBody(url: String): String? {
        val cached = bundleBody
        if (cached != null && bundleUrl == url && System.currentTimeMillis() - bundleTs < BUNDLE_TTL) {
            return cached
        }
        return try {
            val res = cfGet(url, headers = baseHeaders(), timeout = 15_000L)
            if (res.code == 200 && res.text.contains("__oct")) {
                bundleBody = res.text
                res.text
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private class Bridge {
        @JavascriptInterface
        fun request(url: String, method: String, headersJson: String, bodyB64: String): String {
            return relay(url, method, headersJson, bodyB64)
        }

        @JavascriptInterface
        fun onReady() {
            readySignal.complete(true)
        }

        @JavascriptInterface
        fun onBundleError() {
            bundleLoadFailed = true
            readySignal.complete(false)
        }

        @JavascriptInterface
        fun onResult(token: String, json: String) {
            pending.remove(token)?.complete(json)
        }
    }

    private fun relay(url: String, method: String, headersJson: String, bodyB64: String): String {
        return try {
            val extra = try {
                parseJson<Map<String, String>>(headersJson)
            } catch (_: Exception) {
                emptyMap()
            }
            val headers = senshiHeaders(baseHeaders() + extra, url)
            val builder = Request.Builder().url(url)
            headers.forEach { (k, v) -> builder.header(k, v) }
            if (method.equals("POST", ignoreCase = true)) {
                val bytes = if (bodyB64.isNotEmpty()) {
                    Base64.decode(bodyB64, Base64.NO_WRAP)
                } else {
                    ByteArray(0)
                }
                val contentType = headers["Content-Type"] ?: "image/png"
                builder.post(bytes.toRequestBody(contentType.toMediaType()))
            } else {
                builder.get()
            }
            client.newCall(builder.build()).execute().use { resp ->
                val body = try {
                    resp.body?.bytes() ?: ByteArray(0)
                } catch (_: Exception) {
                    ByteArray(0)
                }
                val b64 = Base64.encodeToString(body, Base64.NO_WRAP)
                "${resp.code}\n${resp.header("Content-Type") ?: ""}\n$b64"
            }
        } catch (_: Exception) {
            "0\n\n"
        }
    }

    private fun relayResource(url: String): WebResourceResponse? {
        return try {
            val builder = Request.Builder().url(url).get()
            baseHeaders().forEach { (k, v) -> builder.header(k, v) }
            client.newCall(builder.build()).execute().use { resp ->
                val body = resp.body?.bytes() ?: return null
                val reason = if (resp.isSuccessful) "OK" else "HTTP ${resp.code}"
                val flat = resp.headers.toMultimap().entries
                    .associate { it.key to (it.value.firstOrNull() ?: "") }
                WebResourceResponse(
                    resp.header("Content-Type") ?: "application/octet-stream",
                    reason,
                    resp.code,
                    if (resp.isSuccessful) "OK" else reason,
                    flat,
                    body.inputStream()
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    // the page only wires the open call through, the runtime and every gateway
    // request stay inside the webview's own network stack with its real user agent
    private fun nativePage(bundle: String): String {
        return """<!DOCTYPE html><html><head>
<script>
(function(){
  window.__bridgeOpen=function(token,id){
    var tries=0;
    (function run(){
      if(window.__oct){
        try{
          window.__oct.open(Number(id)).then(function(r){
            SenshiBridge.onResult(token,JSON.stringify({ok:true,r:r}));
          },function(err){
            SenshiBridge.onResult(token,JSON.stringify({ok:false,e:String(err&&err.message||err)}));
          });
        }catch(e){
          SenshiBridge.onResult(token,JSON.stringify({ok:false,e:String(e&&e.message||e)}));
        }
        return;
      }
      tries++;
      if(tries>40){SenshiBridge.onResult(token,JSON.stringify({ok:false,e:"runtime missing"}));return;}
      setTimeout(run,250);
    })();
  };
})();
</script>
<script src="$bundle" onload="SenshiBridge.onReady()" onerror="SenshiBridge.onBundleError()"></script>
</head><body></body></html>"""
    }

    // relay variant for devices whose webview cannot reach the gateway itself,
    // gateway calls are routed through okhttp with the webview's user agent
    private fun relayPage(bundle: String): String {
        return """<!DOCTYPE html><html><head>
<script>
(function(){
  function toBytes(b){var s=atob(b),u=new Uint8Array(s.length);for(var i=0;i<s.length;i++)u[i]=s.charCodeAt(i);return u;}
  function toB64(u){var s="";for(var i=0;i<u.length;i++)s+=String.fromCharCode(u[i]);return btoa(s);}
  window.fetch=function(url,opts){
    opts=opts||{};
    var b64="";
    if(opts.body){
      try{
        var b=opts.body;
        if(b instanceof Uint8Array)b64=toB64(b);
        else if(b instanceof ArrayBuffer)b64=toB64(new Uint8Array(b));
        else b64=btoa(String(b));
      }catch(e){}
    }
    var h={};
    if(opts.headers){for(var k in opts.headers){try{h[k]=String(opts.headers[k]);}catch(e2){}}}
    var out=SenshiBridge.request(String(url),opts.method||"GET",JSON.stringify(h),b64);
    var p=out.split("\n");
    var status=parseInt(p[0],10)||0;
    var ct=p.length>1?p[1]:"application/octet-stream";
    var body=p.length>2?toBytes(p.slice(2).join("\n")):new Uint8Array(0);
    return Promise.resolve({
      ok:status>=200&&status<300,
      status:status,
      headers:{get:function(n){return String(n).toLowerCase()==="content-type"?ct:null;}},
      arrayBuffer:function(){return Promise.resolve(body.buffer);},
      text:function(){return Promise.resolve(new TextDecoder().decode(body));},
      json:function(){return Promise.resolve(JSON.parse(new TextDecoder().decode(body)));}
    });
  };
  window.__bridgeOpen=function(token,id){
    var tries=0;
    (function run(){
      if(window.__oct){
        try{
          window.__oct.open(Number(id)).then(function(r){
            SenshiBridge.onResult(token,JSON.stringify({ok:true,r:r}));
          },function(err){
            SenshiBridge.onResult(token,JSON.stringify({ok:false,e:String(err&&err.message||err)}));
          });
        }catch(e){
          SenshiBridge.onResult(token,JSON.stringify({ok:false,e:String(e&&e.message||e)}));
        }
        return;
      }
      tries++;
      if(tries>60){SenshiBridge.onResult(token,JSON.stringify({ok:false,e:"runtime missing"}));return;}
      setTimeout(run,250);
    })();
  };
})();
</script>
<script src="$bundle"></script>
<script>SenshiBridge.onReady();</script>
</head><body></body></html>"""
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildWebView(nativeMode: Boolean, bundle: String) {
        val ctx = appContext ?: return
        val wv = WebView(ctx)
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.userAgentString = browserUa()
        CookieManager.getInstance().setAcceptCookie(true)
        try {
            CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
        } catch (_: Exception) {
        }
        wv.addJavascriptInterface(Bridge(), "SenshiBridge")
        wv.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val url = request?.url?.toString() ?: return null
                if (url == "$origin$BRIDGE_PATH") {
                    val html = if (nativeMode) nativePage(bundle) else relayPage(bundle)
                    return WebResourceResponse("text/html", "utf-8", html.byteInputStream())
                }
                if (nativeMode) {
                    // everything else, bundle included, goes through the webview itself
                    return null
                }
                if (url == bundle) {
                    val body = bundleBody
                        ?: return WebResourceResponse("application/javascript", "utf-8", ByteArray(0).inputStream())
                    return WebResourceResponse("application/javascript", "utf-8", body.byteInputStream())
                }
                val bundleHost = try {
                    java.net.URI(bundle).host
                } catch (_: Exception) {
                    null
                }
                if (bundleHost != null && url.startsWith("https://$bundleHost/")) {
                    return relayResource(url)
                }
                return null
            }

            override fun onRenderProcessGone(
                view: WebView?,
                detail: RenderProcessGoneDetail?
            ): Boolean {
                // low memory devices reclaim the renderer, the waiters must not hang
                pageUp = false
                pending.values.forEach { it.complete("") }
                mainHandler.post { destroyPage() }
                return true
            }
        }
        webView = wv
        loadedOrigin = origin
        loadedBundle = bundle
        pageMode = nativeMode
        pageBuiltAt = System.currentTimeMillis()
        wv.loadUrl("$origin$BRIDGE_PATH")
    }

    private fun destroyPage() {
        val wv = webView
        webView = null
        pageUp = false
        if (wv != null) {
            try {
                wv.stopLoading()
                wv.destroy()
            } catch (_: Exception) {
            }
        }
    }

    private suspend fun ensurePage(fresh: Boolean, nativeMode: Boolean): Boolean {
        val ageOk = System.currentTimeMillis() - pageBuiltAt < PAGE_MAX_AGE
        if (!fresh && ageOk && pageUp && webView != null && loadedOrigin == origin &&
            loadedBundle == bundleUrl && pageMode == nativeMode
        ) {
            return true
        }
        val bundle = discoverBundleUrl(fresh) ?: return false
        if (!nativeMode && fetchBundleBody(bundle) == null) {
            return false
        }
        val signal = CompletableDeferred<Boolean>()
        readySignal = signal
        bundleLoadFailed = false
        pageUp = false
        mainHandler.post {
            try {
                destroyPage()
                buildWebView(nativeMode, bundle)
            } catch (e: Exception) {
                Log.e(TAG, "bridge page failed: ${e.message}")
                signal.complete(false)
            }
        }
        val ok = withTimeoutOrNull(PAGE_TIMEOUT) { signal.await() } == true && !bundleLoadFailed
        pageUp = ok
        if (!ok) {
            mainHandler.post { destroyPage() }
        }
        return ok
    }

    // a quick runtime check before every open, dead pages fail here instead of
    // burning the whole open timeout
    private suspend fun probeRuntime(): Boolean {
        val wv = webView ?: return false
        val deferred = CompletableDeferred<Boolean>()
        mainHandler.post {
            try {
                wv.evaluateJavascript("!!window.__oct") { result ->
                    deferred.complete(result == "true")
                }
            } catch (_: Exception) {
                deferred.complete(false)
            }
        }
        return withTimeoutOrNull(PROBE_TIMEOUT) { deferred.await() } == true
    }

    private data class BridgeReply(
        val ok: Boolean = false,
        val r: Any? = null,
        val e: String? = null
    )

    // these mean the page itself cannot talk to the gateway, the relay takes over
    // for a while. anything else is treated as transient and retried natively
    private val nativeFailureMarks = listOf(
        "failed to fetch",
        "bootstrap failed",
        "decoder unavailable",
        "runtime missing",
        "policy mismatch",
        "not authorized"
    )

    private fun parseSources(raw: Any?): List<RaghavSenshi.VidcloudSource>? {
        if (raw == null) return emptyList()
        val asText = raw.toJson()
        return try {
            parseJson<List<RaghavSenshi.VidcloudSource>>(asText)
        } catch (_: Exception) {
            try {
                listOf(parseJson<RaghavSenshi.VidcloudSource>(asText))
            } catch (_: Exception) {
                null
            }
        }
    }

    // stays off the shared webview gate on purpose, the other sources would queue
    // ahead of it and the 35s source window is over before it gets a turn
    suspend fun fetchSources(sourceId: Int): List<RaghavSenshi.VidcloudSource>? = openMutex.withLock {
        val cached = openCache[sourceId]
        if (cached != null && System.currentTimeMillis() - cached.ts < OPEN_CACHE_TTL) {
            return@withLock cached.sources
        }
        openCache.remove(sourceId)

        var nativeMode = System.currentTimeMillis() >= nativeMutedUntil
        var fresh = false
        var authFails = 0
        for (attempt in 0..2) {
            if (!ensurePage(fresh, nativeMode)) {
                if (nativeMode) {
                    nativeMutedUntil = System.currentTimeMillis() + NATIVE_MUTE_MS
                    nativeMode = false
                }
                fresh = true
                continue
            }
            if (!probeRuntime()) {
                mainHandler.post { destroyPage() }
                if (nativeMode) {
                    nativeMutedUntil = System.currentTimeMillis() + NATIVE_MUTE_MS
                    nativeMode = false
                }
                fresh = true
                continue
            }
            val token = UUID.randomUUID().toString()
            val deferred = CompletableDeferred<String>()
            pending[token] = deferred
            val wv = webView
            if (wv == null) {
                pending.remove(token)
                continue
            }
            mainHandler.post {
                try {
                    wv.evaluateJavascript(
                        "window.__bridgeOpen&&window.__bridgeOpen(\"$token\",$sourceId);",
                        null
                    )
                } catch (e: Exception) {
                    Log.d(TAG, "open eval failed: ${e.message}")
                    pending.remove(token)?.complete("")
                }
            }
            val json = withTimeoutOrNull(OPEN_TIMEOUT) { deferred.await() }
            pending.remove(token)
            if (json != null) {
                val reply = try {
                    parseJson<BridgeReply>(json)
                } catch (_: Exception) {
                    null
                }
                if (reply != null && reply.ok) {
                    val parsed = parseSources(reply.r)
                    if (parsed != null) {
                        if (nativeMode) nativeMutedUntil = 0L
                        cacheOpen(sourceId, parsed)
                        return@withLock parsed
                    }
                }
                val err = (reply?.e ?: "").lowercase()
                when {
                    nativeMode && nativeFailureMarks.any { err.contains(it) } -> {
                        // the page cannot talk to the gateway at all, use the relay
                        nativeMutedUntil = System.currentTimeMillis() + NATIVE_MUTE_MS
                        nativeMode = false
                    }
                    err.contains("authorization failed") -> {
                        // the session hit its budget, a fresh one starts clean,
                        // but a second refusal means the transport itself is the problem
                        authFails++
                        if (authFails >= 2) nativeMode = !nativeMode
                    }
                    else -> nativeMode = !nativeMode
                }
            } else {
                nativeMode = !nativeMode
            }
            // stale session or bundle, drop the page so the next attempt rebuilds
            bundleTs = 0L
            fresh = true
            mainHandler.post { destroyPage() }
        }
        null
    }

    private fun cacheOpen(sourceId: Int, sources: List<RaghavSenshi.VidcloudSource>) {
        if (openCache.size >= 8) {
            val cutoff = System.currentTimeMillis() - OPEN_CACHE_TTL
            val iter = openCache.entries.iterator()
            while (iter.hasNext()) {
                if (iter.next().value.ts < cutoff) iter.remove()
            }
        }
        openCache[sourceId] = OpenCacheEntry(sources, System.currentTimeMillis())
    }
}
