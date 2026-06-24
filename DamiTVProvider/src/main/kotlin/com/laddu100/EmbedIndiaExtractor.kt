package com.laddu100

import android.content.Context
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.ConsoleMessage
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.resume
import android.util.Log

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink

/** Extractor for embedindia.st. */
class EmbedIndiaExtractor(private val context: Context) : ExtractorApi() {
    override val name = "EmbedIndia"
    override val mainUrl = "https://embedindia.st"
    override val requiresReferer = false

    private val ua =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    private val m3u8Pattern =
        Regex("""(https?://[^\\s"'\\\\]+\\.m3u8(?:[^\\s"'\\\\]*)?)""")

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val embedHost = try {
            val uri = java.net.URI(url)
            "${uri.scheme}://${uri.host}"
        } catch (e: Exception) {
            mainUrl
        }

        val fetchHeaders = mapOf(
            "User-Agent" to ua,
            "Referer" to "$embedHost/",
            "Origin" to embedHost,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
        )

        // Direct HTML parsing
        try {
            val html = app.get(url, headers = fetchHeaders, timeout = 20L).text
            val directMatches = m3u8Pattern
                .findAll(html)
                .map { it.value.replace("\\u0026", "&").replace("\\/", "/") }
                .toList()
                .distinct()
            if (directMatches.isNotEmpty()) {
                directMatches.forEachIndexed { idx, m3u8 ->
                    emitLink(callback, name, m3u8, embedHost, idx)
                }
                return
            }
        } catch (e: Exception) {
            Log.w(name, "fetch page failed for $url: ${e.message}")
        }

        // Custom WebView loader fallback
        try {
            val resolved = resolveStreamUrl(url, referer ?: "$embedHost/")
            if (resolved != null && (resolved.contains("m3u8", ignoreCase = true) || resolved.contains("master.txt", ignoreCase = true))) {
                emitLink(callback, name, resolved, embedHost, 0)
            } else {
                Log.w(name, "Custom WebView returned non-stream URL for $url: $resolved")
            }
        } catch (e: Exception) {
            Log.w(name, "Custom WebView failed for $url: ${e.message}")
        }
    }

    private suspend fun resolveStreamUrl(url: String, referer: String?): String? {
        val ctx = context
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                val captured = AtomicBoolean(false)
                var webView: WebView? = null

                val cleanUp = {
                    if (captured.compareAndSet(false, true)) {
                        try {
                            webView?.destroy()
                        } catch (e: Exception) {}
                        continuation.resume(null)
                    }
                }

                try {
                    webView = WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        settings.userAgentString = ua

                        webChromeClient = object : WebChromeClient() {
                            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                                val msg = consoleMessage?.message() ?: ""
                                Log.d("EmbedIndiaJS", "[Console] $msg")
                                return true
                            }
                        }

                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, pageUrl: String?) {
                                super.onPageFinished(view, pageUrl)
                                Log.d("EmbedIndia", "Page finished loading: $pageUrl")

                                // Inject our robust hook script
                                Handler(Looper.getMainLooper()).postDelayed({
                                    if (captured.get()) return@postDelayed
                                    view?.evaluateJavascript(playScript) { result ->
                                        Log.d("EmbedIndiaJS", "Hook injection result: $result")
                                    }
                                }, 1500)
                            }

                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): WebResourceResponse? {
                                val reqUrl = request?.url?.toString() ?: return null
                                Log.d("EmbedIndiaNet", "Request: $reqUrl")
                                
                                if ((reqUrl.contains("m3u8", ignoreCase = true) || reqUrl.contains("master.txt", ignoreCase = true)) && !captured.get()) {
                                    Log.d("EmbedIndia", "Captured stream URL: $reqUrl")
                                    if (captured.compareAndSet(false, true)) {
                                        Handler(Looper.getMainLooper()).post {
                                            try {
                                                webView?.destroy()
                                            } catch (e: Exception) {}
                                        }
                                        continuation.resume(reqUrl)
                                    }
                                }
                                return super.shouldInterceptRequest(view, request)
                            }
                        }
                    }

                    val headers = HashMap<String, String>()
                    headers["Referer"] = referer ?: "https://dami-tv.pro/"
                    headers["Origin"] = try {
                        val uri = java.net.URI(url)
                        "${uri.scheme}://${uri.host}"
                    } catch (e: Exception) {
                        "https://embedindia.st"
                    }

                    Log.d("EmbedIndia", "Loading URL in WebView: $url")
                    webView.loadUrl(url, headers)

                    // Timeout after 30 seconds
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (captured.compareAndSet(false, true)) {
                            Log.d("EmbedIndia", "Timeout waiting for stream link")
                            try {
                                webView.destroy()
                            } catch (e: Exception) {}
                            continuation.resume(null)
                        }
                    }, 30000)

                } catch (e: Exception) {
                    Log.e("EmbedIndia", "Error initializing WebView: ${e.message}")
                    cleanUp()
                }
            }
        }
    }

    private val playScript = """
        (function() {
            if (window.__interceptor_installed) return "already_installed";
            Object.defineProperty(window, '__interceptor_installed', {
                value: true,
                writable: true,
                configurable: true,
                enumerable: false
            });

            function log(msg) {
                console.log("[Hook] " + msg);
            }

            log("Installing stealth hooks...");

            function triggerInterception(url) {
                if (!url) return;
                var urlStr = (url && typeof url.toString === 'function') ? url.toString() : url;
                log("Triggering interception for URL: " + urlStr);
                if (urlStr.indexOf('m3u8') !== -1 || urlStr.indexOf('master.txt') !== -1) {
                    window.location.href = urlStr;
                }
            }

            // 1. Hook HTMLMediaElement.prototype.src
            try {
                var originalSrcDescriptor = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, 'src');
                if (originalSrcDescriptor && originalSrcDescriptor.set) {
                    var originalSet = originalSrcDescriptor.set;
                    Object.defineProperty(HTMLMediaElement.prototype, 'src', {
                        set: function(val) {
                            log("MediaElement src set: " + val);
                            triggerInterception(val);
                            return originalSet.apply(this, arguments);
                        },
                        configurable: true,
                        enumerable: true
                    });
                    log("MediaElement.src hooked.");
                }
            } catch(e) {
                log("Error hooking MediaElement.src: " + e.message);
            }

            // 2. Hook HTMLSourceElement.prototype.src
            try {
                var originalSourceSrcDescriptor = Object.getOwnPropertyDescriptor(HTMLSourceElement.prototype, 'src');
                if (originalSourceSrcDescriptor && originalSourceSrcDescriptor.set) {
                    var originalSourceSet = originalSourceSrcDescriptor.set;
                    Object.defineProperty(HTMLSourceElement.prototype, 'src', {
                        set: function(val) {
                            log("SourceElement src set: " + val);
                            triggerInterception(val);
                            return originalSourceSet.apply(this, arguments);
                        },
                        configurable: true,
                        enumerable: true
                    });
                    log("SourceElement.src hooked.");
                }
            } catch(e) {
                log("Error hooking SourceElement.src: " + e.message);
            }

            // 3. Hook Element.prototype.setAttribute
            try {
                var originalSetAttribute = Element.prototype.setAttribute;
                Element.prototype.setAttribute = function(name, value) {
                    if (typeof name === 'string' && name.toLowerCase() === 'src') {
                        log("setAttribute src: " + value);
                        triggerInterception(value);
                    }
                    return originalSetAttribute.apply(this, arguments);
                };
                Element.prototype.setAttribute.toString = function() {
                    return "function setAttribute() { [native code] }";
                };
                log("Element.setAttribute hooked.");
            } catch(e) {
                log("Error hooking setAttribute: " + e.message);
            }

            // 4. Hook window.fetch
            try {
                var originalFetch = window.fetch;
                window.fetch = function() {
                    var args = arguments;
                    var url = args[0];
                    var urlStr = "";
                    if (typeof url === 'string') {
                        urlStr = url;
                    } else if (url && typeof url === 'object' && url.url) {
                        urlStr = url.url;
                    } else if (url && typeof url.toString === 'function') {
                        urlStr = url.toString();
                    }
                    if (urlStr) {
                        log("Fetch: " + urlStr);
                        if (urlStr.indexOf('m3u8') !== -1 || urlStr.indexOf('master.txt') !== -1) {
                            triggerInterception(urlStr);
                        }
                        if (urlStr.indexOf('/fetch') !== -1) {
                            return originalFetch.apply(this, args).then(function(response) {
                                var clone = response.clone();
                                clone.text().then(function(text) {
                                    log("Fetch response (/fetch): " + text.substring(0, 300));
                                    if (text.indexOf('m3u8') !== -1) {
                                        try {
                                            var json = JSON.parse(text);
                                            var streamUrl = json.url || json.stream || json.file || json.data || text;
                                            triggerInterception(streamUrl);
                                        } catch(e) {
                                            var match = text.match(/(https?:\\/[^\\s\"']+\\.m3u8[^\\s\"']*)/);
                                            if (match) {
                                                triggerInterception(match[1]);
                                            }
                                        }
                                    }
                                }).catch(function(err) {});
                                return response;
                            });
                        }
                    }
                    return originalFetch.apply(this, args);
                };
                window.fetch.toString = function() {
                    return "function fetch() { [native code] }";
                };
                log("window.fetch hooked.");
            } catch(e) {
                log("Error hooking fetch: " + e.message);
            }

            // 5. Hook XMLHttpRequest
            try {
                var originalOpen = XMLHttpRequest.prototype.open;
                var originalSend = XMLHttpRequest.prototype.send;
                
                XMLHttpRequest.prototype.open = function(method, url) {
                    var urlStr = (url && typeof url.toString === 'function') ? url.toString() : url;
                    this._url = urlStr;
                    this._method = method;
                    log("XHR open: " + urlStr);
                    if (typeof urlStr === 'string') {
                        if (urlStr.indexOf('m3u8') !== -1 || urlStr.indexOf('master.txt') !== -1) {
                            triggerInterception(urlStr);
                        }
                    }
                    return originalOpen.apply(this, arguments);
                };
                XMLHttpRequest.prototype.open.toString = function() {
                    return "function open() { [native code] }";
                };

                XMLHttpRequest.prototype.send = function() {
                    var self = this;
                    var url = this._url;
                    var urlStr = (url && typeof url.toString === 'function') ? url.toString() : url;
                    if (typeof urlStr === 'string' && urlStr.indexOf('/fetch') !== -1) {
                        this.addEventListener('readystatechange', function() {
                            if (self.readyState === 4) {
                                if (self.status >= 200 && self.status < 300) {
                                    var text = self.responseText;
                                    log("XHR response (/fetch): " + text.substring(0, 300));
                                    if (text.indexOf('m3u8') !== -1) {
                                        try {
                                            var json = JSON.parse(text);
                                            var streamUrl = json.url || json.stream || json.file || json.data || text;
                                            triggerInterception(streamUrl);
                                        } catch(e) {
                                            var match = text.match(/(https?:\\/[^\\s\"']+\\.m3u8[^\\s\"']*)/);
                                            if (match) {
                                                triggerInterception(match[1]);
                                            }
                                        }
                                    }
                                }
                            }
                        }, false);
                    }
                    return originalSend.apply(this, arguments);
                };
                XMLHttpRequest.prototype.send.toString = function() {
                    return "function send() { [native code] }";
                };
                log("XMLHttpRequest hooked.");
            } catch(e) {
                log("Error hooking XHR: " + e.message);
            }

            // 6. Hook Hls.js
            try {
                var installHlsHook = function(HlsClass) {
                    if (HlsClass.prototype && !HlsClass.prototype._hooked) {
                        HlsClass.prototype._hooked = true;
                        var originalLoadSource = HlsClass.prototype.loadSource;
                        if (originalLoadSource) {
                            HlsClass.prototype.loadSource = function(src) {
                                log("Hls.loadSource: " + src);
                                triggerInterception(src);
                                return originalLoadSource.apply(this, arguments);
                            };
                            HlsClass.prototype.loadSource.toString = function() {
                                return "function loadSource() { [native code] }";
                            };
                        }
                    }
                };

                if (window.Hls) {
                    installHlsHook(window.Hls);
                } else {
                    var hlsVal = undefined;
                    Object.defineProperty(window, 'Hls', {
                        get: function() { return hlsVal; },
                        set: function(val) {
                            hlsVal = val;
                            try { installHlsHook(val); } catch(e) {}
                        },
                        configurable: true,
                        enumerable: true
                    });
                }
            } catch(e) {
                log("Error hooking Hls: " + e.message);
            }

            // 7. Hook jwplayer
            try {
                var installJwHook = function(jwplayerFunc) {
                    if (!jwplayerFunc._hooked) {
                        jwplayerFunc._hooked = true;
                        window.jwplayer = function() {
                            var playerInstance = jwplayerFunc.apply(this, arguments);
                            if (playerInstance && !playerInstance._hooked) {
                                playerInstance._hooked = true;
                                var originalSetup = playerInstance.setup;
                                if (originalSetup) {
                                    playerInstance.setup = function(config) {
                                        log("jwplayer.setup");
                                        if (config && config.file) {
                                            triggerInterception(config.file);
                                        }
                                        if (config && config.playlist) {
                                            try {
                                                config.playlist.forEach(function(item) {
                                                    if (item.file) triggerInterception(item.file);
                                                    if (item.sources) {
                                                        item.sources.forEach(function(src) {
                                                            if (src.file) triggerInterception(src.file);
                                                        });
                                                    }
                                                });
                                            } catch(e) {}
                                        }
                                        return originalSetup.apply(this, arguments);
                                    };
                                    playerInstance.setup.toString = function() {
                                        return "function setup() { [native code] }";
                                    };
                                }
                            }
                            return playerInstance;
                        };
                        for (var key in jwplayerFunc) {
                            if (jwplayerFunc.hasOwnProperty(key)) {
                                window.jwplayer[key] = jwplayerFunc[key];
                            }
                        }
                    }
                };

                if (window.jwplayer) {
                    installJwHook(window.jwplayer);
                } else {
                    var jwVal = undefined;
                    Object.defineProperty(window, 'jwplayer', {
                        get: function() { return jwVal; },
                        set: function(val) {
                            jwVal = val;
                            try { installJwHook(val); } catch(e) {}
                        },
                        configurable: true,
                        enumerable: true
                    });
                }
            } catch(e) {
                log("Error hooking jwplayer: " + e.message);
            }

            // 8. Periodic tag checker & player triggers
            var checkerInterval = setInterval(function() {
                var videos = document.querySelectorAll('video');
                if (videos && videos.length > 0) {
                    for (var i = 0; i < videos.length; i++) {
                        var src = videos[i].src;
                        if (src && src.indexOf('m3u8') !== -1) {
                            log("Checker found video src: " + src);
                            triggerInterception(src);
                            clearInterval(checkerInterval);
                        }
                        var sources = videos[i].querySelectorAll('source');
                        for (var j = 0; j < sources.length; j++) {
                            var ssrc = sources[j].src;
                            if (ssrc && ssrc.indexOf('m3u8') !== -1) {
                                log("Checker found source src: " + ssrc);
                                triggerInterception(ssrc);
                                clearInterval(checkerInterval);
                            }
                        }
                    }
                }
            }, 500);

            var playInterval = setInterval(function() {
                var jwButton = document.querySelector('.jw-icon-display') || 
                               document.querySelector('.jw-display-icon-container') ||
                               document.querySelector('.jw-icon-play');
                if (jwButton) {
                    log("Clicking play button");
                    jwButton.click();
                }
                
                var videos = document.querySelectorAll('video');
                if (videos) {
                    for (var i = 0; i < videos.length; i++) {
                        try { 
                            videos[i].play(); 
                        } catch(e) {}
                    }
                }
                
                var playOverlay = document.querySelector('[class*="play"]') || 
                                  document.querySelector('[id*="play"]');
                if (playOverlay && playOverlay !== document.body && !playOverlay.classList.contains('jw-icon-play')) {
                    try { playOverlay.click(); } catch(e) {}
                }
            }, 500);

            setTimeout(function() {
                clearInterval(checkerInterval);
                clearInterval(playInterval);
            }, 25000);

            return "hooks_installed";
        })()
    """

    private suspend fun emitLink(
        callback: (ExtractorLink) -> Unit,
        sourceName: String,
        m3u8Url: String,
        refererHost: String,
        index: Int
    ) {
        callback.invoke(
            newExtractorLink(
                source = sourceName,
                name = if (index == 0) sourceName else "$sourceName (Alt ${index + 1})",
                url = m3u8Url,
                type = ExtractorLinkType.M3U8
            ) {
                this.headers = mapOf(
                    "User-Agent" to ua,
                    "Referer" to "$refererHost/",
                    "Origin" to refererHost
                )
            }
        )
    }
}
