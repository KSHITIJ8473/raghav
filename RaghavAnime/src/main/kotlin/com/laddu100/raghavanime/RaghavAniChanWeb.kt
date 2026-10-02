package com.laddu100.raghavanime

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.api.Log
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

// the anichan watch api only answers real browser clients so the fetch runs in a webview
object RaghavAniChanWeb {

    private const val TAG = "RaghavAniChan"
    private const val DEFAULT_URL = "https://anichan.to"
    private const val FETCH_TIMEOUT = 40_000L
    private const val CACHE_TTL = 10 * 60 * 1000L
    private const val EMPTY_CACHE_TTL = 60 * 1000L

    private val mainHandler = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private var webView: WebView? = null
    @Volatile
    private var host = DEFAULT_URL
    @Volatile
    private var loadedHost: String? = null
    @Volatile
    private var pageReady = false
    private val readyWaiters = java.util.concurrent.CopyOnWriteArrayList<(Boolean) -> Unit>()

    private val pending = ConcurrentHashMap<String, (String?) -> Unit>()
    private val cache = ConcurrentHashMap<String, CacheEntry>()

    private class CacheEntry(val json: String, val ts: Long, val empty: Boolean)

    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    suspend fun refreshDomain() {
        FirebaseDomainHelper.getDomain("anichan")?.let { host = it }
    }

    fun url(): String = host

    private class Bridge {
        @JavascriptInterface
        fun onResult(token: String, json: String) {
            pending.remove(token)?.invoke(json)
        }
    }

    suspend fun warmPage(): Boolean = waitForPage()

    private suspend fun waitForPage(): Boolean {
        if (pageReady && webView != null && loadedHost == host) return true
        return suspendCancellableCoroutine { cont ->
            readyWaiters.add { ok -> if (cont.isActive) cont.resume(ok) }
            mainHandler.post { createIfNeeded(host) }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createIfNeeded(currentHost: String) {
        val current = webView
        if (current != null) {
            if (loadedHost == currentHost) return
            // domain changed under us, reload the probe page on the new host
            loadedHost = currentHost
            pageReady = false
            current.loadUrl("$currentHost/watch-session-probe")
            return
        }
        val ctx = appContext ?: run {
            readyWaiters.forEach { it(false) }
            readyWaiters.clear()
            return
        }
        try {
            val wv = WebView(ctx)
            wv.settings.javaScriptEnabled = true
            wv.settings.domStorageEnabled = true
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
            wv.addJavascriptInterface(Bridge(), "anichanBridge")
            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    if (url != null && url.startsWith(host)) {
                        loadedHost = host
                        pageReady = true
                        readyWaiters.forEach { it(true) }
                        readyWaiters.clear()
                    }
                }
            }
            webView = wv
            loadedHost = currentHost
            wv.loadUrl("$currentHost/watch-session-probe")
        } catch (e: Exception) {
            Log.e(TAG, "webview init failed: ${e.message}")
            readyWaiters.forEach { it(false) }
            readyWaiters.clear()
        }
    }

    private fun fetchScript(anilistId: Int, ep: Int, categories: List<String>, token: String): String {
        val cats = categories.joinToString(",") { "\"$it\"" }
        return """
(async () => {
    const token = "$token";
    const cats = [$cats];
    const tiers = ["fast", "rest"];
    const W = "6be19f72";
    const K1 = "wuEoOR48/o2oD14z71nB9MG2eC3T5q+uayZj5fb3Xsk=";
    const K2 = "0Avpb+a3t0ihtp6DLmBkfB3wStHog0zOAhKALhnfeC4=";
    const b64 = (t) => Uint8Array.from(atob(t), (c) => c.charCodeAt(0));
    const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
    const deliver = (o) => { try { anichanBridge.onResult(token, JSON.stringify(o)); } catch (e) {} };

    let session = null;
    let keys = window.__anichanKeys || [W, K1, K2];
    let hmacKey = null;
    let scans = 0;
    let sessionMisses = 0;
    let sealMisses = 0;
    let emptyMisses = 0;

    // the site keeps one session alive for hours, reuse it or the limiter kicks in
    const newSession = async (force) => {
        if (!force) {
            const cached = window.__anichanSession;
            if (cached && cached.n && cached.exp * 1000 > Date.now() + 60000) return cached;
        } else {
            window.__anichanSession = null;
        }
        try {
            const r = await fetch("/api/watch/session", {
                method: "POST",
                headers: {"Content-Type": "application/json"},
                body: JSON.stringify({token: ""}),
                cache: "no-store"
            });
            if (!r.ok) return null;
            const j = await r.json();
            if (j && j.n) window.__anichanSession = j;
            return j || null;
        } catch (e) { return null; }
    };

    const currentKey = async () => {
        if (!hmacKey) {
            const a = b64(keys[1]), b = b64(keys[2]);
            const x = new Uint8Array(a.length);
            for (let i = 0; i < a.length; i++) x[i] = a[i] ^ b[i];
            hmacKey = await crypto.subtle.importKey("raw", x, {name: "HMAC", hash: "SHA-256"}, false, ["sign"]);
        }
        return [keys[0], hmacKey];
    };

    // every bundle ships its own key triple, scanning the chunks survives a rotation
    const findBundleKeys = async () => {
        if (scans >= 2) return false;
        scans++;
        try {
            const urls = new Set();
            for (const s of document.scripts) {
                if (s.src && s.src.indexOf("/_next/static/chunks/") >= 0) urls.add(s.src);
            }
            try {
                for (const e of performance.getEntriesByType("resource")) {
                    if (e.name.indexOf("/_next/static/chunks/") >= 0 && e.name.endsWith(".js")) urls.add(e.name);
                }
            } catch (e2) {}
            try {
                const page = await fetch("/", {cache: "force-cache"});
                const html = await page.text();
                const re = /src="(\/_next\/static\/chunks\/[^"]+\.js)"/g;
                let m;
                while ((m = re.exec(html)) !== null) urls.add(location.origin + m[1]);
            } catch (e3) {}
            const triple = /["']([0-9a-f]{8})["'],[A-Za-z_$][\w$]*=["']([A-Za-z0-9+/=]{44})["'],[A-Za-z_$][\w$]*=["']([A-Za-z0-9+/=]{44})["']/;
            for (const u of urls) {
                try {
                    const t = await (await fetch(u, {cache: "force-cache"})).text();
                    const m = triple.exec(t);
                    if (m && b64(m[2]).length === 32 && b64(m[3]).length === 32) {
                        const found = [m[1], m[2], m[3]];
                        if (found.join() === keys.join()) return false;
                        keys = found;
                        window.__anichanKeys = keys;
                        hmacKey = null;
                        return true;
                    }
                } catch (e4) {}
            }
        } catch (e5) {}
        return false;
    };

    const openSealed = async (j, nonce, key) => {
        const mac = await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(nonce));
        const ak = await crypto.subtle.importKey("raw", mac, {name: "AES-GCM"}, false, ["decrypt"]);
        return JSON.parse(new TextDecoder().decode(await crypto.subtle.decrypt({name: "AES-GCM", iv: b64(j.i)}, ak, b64(j.d))));
    };

    const load = async (cat, tier) => {
        for (let i = 0; i < 4; i++) {
            if (!session || !session.n) {
                session = await newSession(false);
                if (!session || !session.n) {
                    // 429 "slow down" once too many sessions are minted, back off harder each miss
                    sessionMisses++;
                    await sleep(400 * Math.min(sessionMisses, 4));
                    continue;
                }
                sessionMisses = 0;
            }
            try {
                const pair = await currentKey();
                const r = await fetch("/api/watch/servers?anilistId=$anilistId&ep=$ep&category=" + cat + "&tier=" + tier,
                    {headers: {"X-Wk": pair[0]}, cache: "no-store"});
                if (r.status === 401) {
                    // stale worker session, a fresh one fixes it
                    session = await newSession(true);
                    await sleep(250);
                    continue;
                }
                if (r.status === 429 || r.status >= 500) {
                    await sleep(r.status === 429 ? 1500 : 400);
                    continue;
                }
                if (!r.ok) return {servers: []};
                let j = await r.json();
                if (j && j.v === 1) {
                    let opened = null;
                    try {
                        opened = await openSealed(j, session.n, pair[1]);
                    } catch (e) { opened = null; }
                    if (!opened) {
                        // a key rotation needs the bundle scan, anything else heals with a new session
                        sealMisses++;
                        if (sealMisses >= 2) await findBundleKeys();
                        session = await newSession(true);
                        await sleep(250);
                        continue;
                    }
                    sealMisses = 0;
                    emptyMisses = 0;
                    j = opened;
                } else if (!j || !j.servers || j.servers.length === 0) {
                    // a rotation answers with the same empty list as a burst, scan on the second empty
                    emptyMisses++;
                    if (emptyMisses >= 2 && await findBundleKeys()) {
                        emptyMisses = 0;
                        continue;
                    }
                    await sleep(300);
                    continue;
                }
                return j || {servers: []};
            } catch (e7) {
                await sleep(300);
            }
        }
        return {servers: []};
    };

    const merged = [];
    const seen = new Set();
    for (const cat of cats) {
        for (const tier of tiers) {
            const j = await load(cat, tier);
            for (const sv of (j && j.servers) || []) {
                if (cat === "hsub" && !sv.subType && sv.type === "hls") sv.subType = "hard";
                const key = sv.type === "embed"
                    ? [sv.name, sv.label, sv.embed].join("|")
                    : [sv.name, sv.label, sv.subType].join("|");
                if (seen.has(key)) continue;
                seen.add(key);
                merged.push(sv);
            }
        }
    }

    // download mirrors sit on their own endpoint outside the tier split
    try {
        if (!session) session = await newSession(false);
        let r = await fetch("/api/watch/ext-downloads?anilistId=$anilistId&ep=$ep", {cache: "no-store"});
        if (r.status === 401) {
            session = await newSession(true);
            r = await fetch("/api/watch/ext-downloads?anilistId=$anilistId&ep=$ep", {cache: "no-store"});
        }
        if (r.ok) {
            const dj = await r.json();
            const bucket = dj && dj.kiwi ? (cats[0] === "dub" ? dj.kiwi.dub : dj.kiwi.sub) : null;
            for (const q in (bucket || {})) {
                const u = bucket[q];
                if (typeof u === "string" && u.indexOf("http") === 0) {
                    merged.push({name: "kiwi", label: "Kiwi " + q, type: "embed", embed: u, subType: "soft"});
                }
            }
        }
    } catch (e8) {}

    deliver({servers: merged});
})();
"""
    }

    private suspend fun evaluate(script: String, token: String): String? {
        return withTimeoutOrNull(FETCH_TIMEOUT) {
            suspendCancellableCoroutine { cont ->
                pending[token] = { json -> if (cont.isActive) cont.resume(json) }
                cont.invokeOnCancellation { pending.remove(token) }
                mainHandler.post {
                    val wv = webView
                    if (wv == null) {
                        pending.remove(token)?.invoke(null)
                    } else {
                        wv.evaluateJavascript(script, null)
                    }
                }
            }
        }
    }

    // a second run is cheap once the bundle keys are cached
    private suspend fun runFetch(anilistId: Int, ep: Int, categories: List<String>): String? {
        val token = UUID.randomUUID().toString()
        return RaghavPerf.withWebView {
            evaluate(fetchScript(anilistId, ep, categories, token), token)
        }
    }

    suspend fun fetchServers(anilistId: Int, ep: Int, category: String): String? {
        val key = "$anilistId:$ep:$category"
        val now = System.currentTimeMillis()
        cache[key]?.let { entry ->
            val ttl = if (entry.empty) EMPTY_CACHE_TTL else CACHE_TTL
            if (now - entry.ts < ttl) return entry.json
            cache.remove(key)
        }

        if (!waitForPage()) {
            Log.e(TAG, "webview page unavailable")
            return null
        }

        // hardsub versions live on their own category, they ride along under the sub tab
        val categories = if (category == "dub") listOf("dub") else listOf("sub", "hsub")
        var result = runFetch(anilistId, ep, categories)
            ?: runFetch(anilistId, ep, categories)
        if (result == null) {
            Log.e(TAG, "servers fetch timed out for $key")
            return null
        }

        // the site serves empty lists under burst load too, mirror its own retry once
        if (isEmptyList(result)) {
            delay(2500L)
            result = runFetch(anilistId, ep, categories) ?: result
        }

        val empty = isEmptyList(result)
        cache[key] = CacheEntry(result, System.currentTimeMillis(), empty)
        return result
    }

    private fun isEmptyList(json: String): Boolean {
        return try {
            parseJson<ServersEnvelope>(json).servers.isNullOrEmpty()
        } catch (e: Exception) {
            Log.d(TAG, "servers parse check failed: ${e.message}")
            true
        }
    }
}
