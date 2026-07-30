package com.enma

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.lagradost.api.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlin.coroutines.resume

/**
 * Decrypts enma.lol API responses using the site's own WASM module (ada.wasm).
 *
 * The site encrypts ALL API responses with a WASM-based AES decryption.
 * The WASM binary (4KB, AssemblyScript) is served from https://www.enma.lol/ada.wasm
 * and the function name is derived from https://www.enma.lol/ada.manifest.
 *
 * This singleton loads a minimal HTML page in a WebView that instantiates the WASM
 * and exposes a decrypt() function. Kotlin calls it via evaluateJavascript +
 * JavascriptInterface callback for async to sync bridging.
 */
object EnmaDecryptor {
    private const val TAG = "EnmaDecryptor"
    private const val BASE_URL = "https://www.enma.lol/"

    @Volatile
    private var webView: WebView? = null

    @Volatile
    private var initialized = false

    @Volatile
    private var appContext: Context? = null

    private val initMutex = Mutex()

    fun setContext(context: Context) {
        appContext = context
    }

    // Callback bridge: JS calls AndroidDecrypt.onResult(result) which resumes the coroutine
    private class DecryptBridge {
        @Volatile
        var pendingCont: kotlinx.coroutines.CancellableContinuation<String>? = null

        @JavascriptInterface
        fun onResult(result: String) {
            val cont = pendingCont
            pendingCont = null
            cont?.resume(result)
        }

        @JavascriptInterface
        fun onReady() {
            Log.d(TAG, "WASM ready")
        }

        @JavascriptInterface
        fun onError(error: String) {
            Log.e(TAG, "WASM error: $error")
            val cont = pendingCont
            pendingCont = null
            cont?.resume("")
        }
    }

    private val bridge = DecryptBridge()

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun init(context: Context) {
        if (initialized) return
        initMutex.withLock {
            if (initialized) return
            withContext(Dispatchers.Main) {
                Log.d(TAG, "Initializing WebView + WASM...")
                val wv = WebView(context)
                wv.settings.javaScriptEnabled = true
                wv.settings.domStorageEnabled = true
                wv.settings.allowFileAccess = false
                wv.addJavascriptInterface(bridge, "AndroidDecrypt")

                val html = """
                    <html><body><script>
                    var Rs=null, funcName=null;
                    async function initWasm(){
                        var w=await fetch('${BASE_URL}ada.wasm');
                        var m=await fetch('${BASE_URL}ada.manifest');
                        var wb=await w.arrayBuffer();
                        var mf=await m.json();
                        var s=mf.s, e=mf.e;
                        funcName=String.fromCharCode.apply(null, e.map(function(l,c){return l^(s>>(c&15))&255}));
                        var r=await WebAssembly.instantiate(wb,{env:{abort:function(){}}});
                        Rs=r.instance.exports;
                    }
                    window._decrypt=function(enc){
                        try{
                            var dec=atob(enc.trim());
                            var len=dec.length;
                            var bytes=new Uint8Array(len);
                            for(var i=0;i<len;i++)bytes[i]=dec.charCodeAt(i);
                            var dp=Rs.__pin(Rs.__new(len,1))>>>0;
                            var hp=Rs.__new(12,5)>>>0;
                            var v=new DataView(Rs.memory.buffer);
                            v.setUint32(hp,dp,true);
                            v.setUint32(hp+4,dp,true);
                            v.setUint32(hp+8,len,true);
                            new Uint8Array(Rs.memory.buffer,dp,len).set(bytes);
                            Rs.__unpin(dp);
                            var rp=Rs[funcName](hp);
                            v=new DataView(Rs.memory.buffer);
                            var rdp=v.getUint32(rp+4,true);
                            var rl=v.getUint32(rp+8,true);
                            var rb=new Uint8Array(Rs.memory.buffer,rdp,rl).slice();
                            return new TextDecoder().decode(rb);
                        }catch(e){
                            return 'DECRYPT_ERROR:'+e.message;
                        }
                    };
                    initWasm().then(function(){
                        window._ready=true;
                        AndroidDecrypt.onReady();
                    }).catch(function(e){
                        AndroidDecrypt.onError('init: '+e.message);
                    });
                    </script></body></html>
                """.trimIndent()

                wv.loadDataWithBaseURL(BASE_URL, html, "text/html", "UTF-8", null)
                webView = wv

                // Wait for WASM to initialize (up to 15 seconds)
                var attempts = 0
                while (attempts < 30) {
                    delay(500)
                    val ready = suspendCancellableCoroutine<String?> { c ->
                        wv.evaluateJavascript("(window._ready===true)?'YES':'NO'") { result ->
                            c.resume(result)
                        }
                    }
                    if (ready == "YES") {
                        initialized = true
                        Log.d(TAG, "WASM initialized successfully")
                        return@withContext
                    }
                    attempts++
                }
                Log.e(TAG, "WASM init timeout after 15s")
            }
        }
    }

    /**
     * Decrypt an encrypted base64 API response to JSON string.
     * Must be called after init(). Returns "" on failure.
     */
    suspend fun decrypt(encrypted: String): String {
        if (!initialized) {
            Log.e(TAG, "decrypt called before init")
            return ""
        }

        // Base64 strings only contain A-Za-z0-9+/= so safe to embed in JS string
        val safeEnc = encrypted.trim()

        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                bridge.pendingCont = cont
                val js = "AndroidDecrypt.onResult(window._decrypt('$safeEnc'))"
                webView?.evaluateJavascript(js, null) ?: run {
                    bridge.pendingCont = null
                    cont.resume("")
                }
            }
        }
    }

    /**
     * Convenience: fetch a URL, get the encrypted response, decrypt it, return JSON.
     */
    suspend fun fetchAndDecrypt(
        url: String,
        headers: Map<String, String>
    ): String? {
        // Auto-initialize the WASM decryptor on first use
        if (!initialized) {
            val ctx = appContext ?: run {
                Log.e(TAG, "No context available for WASM init")
                return null
            }
            init(ctx)
        }
        return try {
            val encrypted = com.lagradost.cloudstream3.app.get(url, headers = headers, timeout = 30_000L).text
            if (encrypted.isBlank()) return null
            val trimmed = encrypted.trim()
            // If it starts with { or [ it is already JSON (not encrypted)
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                return trimmed
            }
            val decrypted = decrypt(trimmed)
            if (decrypted.isBlank() || decrypted.startsWith("DECRYPT_ERROR:")) {
                Log.e(TAG, "decrypt failed for $url: $decrypted")
                null
            } else {
                decrypted
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchAndDecrypt failed for $url: ${e.message}")
            null
        }
    }
}
