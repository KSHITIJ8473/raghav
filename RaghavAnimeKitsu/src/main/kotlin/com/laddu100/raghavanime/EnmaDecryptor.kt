package com.laddu100.raghavanime

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.api.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

object EnmaDecryptor {
    private const val TAG = "EnmaDecryptor"
    private const val PAGE_URL = "https://www.enma.lol/home"
    private val mapper = ObjectMapper()

    @Volatile private var webView: WebView? = null
    @Volatile private var initialized = false
    @Volatile private var appContext: Context? = null
    @Volatile private var initStarted = false
    @Volatile private var readySignal: CompletableDeferred<Unit>? = null

    private val initScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun setContext(context: Context) {
        appContext = context
    }

    private class DecryptBridge {
        @JavascriptInterface
        fun onReady() {
            initialized = true
            readySignal?.complete(Unit)
        }

        @JavascriptInterface
        fun onError(error: String) {
            Log.e(TAG, "WASM init error: $error")
            readySignal?.completeExceptionally(Exception(error))
        }

        @JavascriptInterface
        fun log(msg: String) {
        }
    }

    private val bridge = DecryptBridge()

    private val injectScript = """
        (function() {
            if (window._enmaDecryptLoaded) return;
            window._enmaDecryptLoaded = true;
            var Rs=null, funcName=null;
            async function initWasm(){
                var w=await fetch('/ada.wasm');
                var m=await fetch('/ada.manifest');
                var wb=await w.arrayBuffer();
                var mf=await m.json();
                funcName=String.fromCharCode.apply(null, mf.e.map(function(l,c){return l^(mf.s>>(c&15))&255}));
                var r=await WebAssembly.instantiate(wb,{env:{abort:function(){}}});
                Rs=r.instance.exports;
            }
            window._doDecrypt=function(){
                try{
                    var enc=window._pendingEnc;
                    if(!enc){return;}
                    window._pendingEnc=null;
                    window._decryptResult=null;
                    var dec=atob(enc.trim());
                    var len=dec.length;
                    var bytes=new Uint8Array(len);
                    for(var i=0;i<len;i++)bytes[i]=dec.charCodeAt(i);
                    var dp=Rs.__pin(Rs.__new(len,1))>>>0;
                    var hp=Rs.__new(12,5)>>>0;
                    var v=new DataView(Rs.memory.buffer);
                    v.setUint32(hp,dp,true);v.setUint32(hp+4,dp,true);v.setUint32(hp+8,len,true);
                    new Uint8Array(Rs.memory.buffer,dp,len).set(bytes);
                    Rs.__unpin(dp);
                    var rp=Rs[funcName](hp);
                    v=new DataView(Rs.memory.buffer);
                    var rdp=v.getUint32(rp+4,true);
                    var rl=v.getUint32(rp+8,true);
                    var rb=new Uint8Array(Rs.memory.buffer,rdp,rl).slice();
                    window._decryptResult=new TextDecoder().decode(rb);
                }catch(e){
                    window._decryptResult='DECRYPT_ERROR:'+e.message;
                }
            };
            initWasm().then(function(){AndroidDecrypt.onReady();}).catch(function(e){AndroidDecrypt.onError(e.message);});
        })();
    """.trimIndent()

    @SuppressLint("SetJavaScriptEnabled")
    fun startInit() {
        if (initStarted) return
        initStarted = true
        val ctx = appContext ?: return
        readySignal = CompletableDeferred()

        initScope.launch {
            try {
                webView?.destroy()
                webView = null
                initialized = false

                val wv = WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.blockNetworkImage = true
                    addJavascriptInterface(bridge, "AndroidDecrypt")
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            view?.evaluateJavascript(injectScript, null)
                        }
                    }
                    webChromeClient = WebChromeClient()
                    loadUrl(PAGE_URL)
                }
                webView = wv
            } catch (e: Exception) {
                Log.e(TAG, "init failed: ${e.message}")
                readySignal?.completeExceptionally(e)
            }
        }
    }

    private suspend fun awaitReady() {
        if (initialized) return
        val signal = readySignal ?: run {
            startInit()
            kotlinx.coroutines.delay(100)
            readySignal ?: return
        }
        withTimeoutOrNull(30_000L) { signal.await() }
    }

    suspend fun decrypt(encrypted: String): String {
        if (!initialized) { startInit(); awaitReady() }
        val wv = webView ?: return ""
        if (!initialized) return ""

        return withContext(Dispatchers.Main) {
            wv.evaluateJavascript(
                "window._pendingEnc=${jsonEncode(encrypted)};window._decryptResult=null;window._doDecrypt();",
                null
            )

            var result: String? = null
            var attempts = 0
            while (attempts < 75 && result == null) {
                kotlinx.coroutines.delay(200)
                result = suspendCancellableCoroutine<String?> { cont ->
                    wv.evaluateJavascript("window._decryptResult") { res ->
                        cont.resume(res)
                    }
                }
                if (result == null || result == "null" || result == "undefined") {
                    result = null
                    attempts++
                }
            }

            if (result == null) return@withContext ""
            try {
                mapper.readValue(result, String::class.java)
            } catch (e: Exception) {
                ""
            }
        }
    }

    private fun jsonEncode(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c.code < 32) sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        return sb.append("\"").toString()
    }

    suspend fun fetchAndDecrypt(url: String, headers: Map<String, String>): String? {
        if (!initialized) startInit()
        return try {
            val encrypted = com.lagradost.cloudstream3.app.get(url, headers = headers, timeout = 15_000L).text
            if (encrypted.isBlank()) return null
            val trimmed = encrypted.trim()
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) return trimmed
            if (!initialized) awaitReady()
            if (!initialized) return null
            val decrypted = decrypt(trimmed)
            if (decrypted.isBlank() || decrypted.startsWith("DECRYPT_ERROR:")) null
            else decrypted
        } catch (e: Exception) {
            Log.e(TAG, "fetchAndDecrypt failed: ${e.message}")
            null
        }
    }
}
