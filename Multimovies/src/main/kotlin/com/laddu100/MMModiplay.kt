package com.laddu100

import com.lagradost.api.Log
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import org.jsoup.Jsoup

// the rozgarlelo hub fronts several mirror platforms; newer dropdown entries
// carry their own target url, older ones still resolve through the hub proxy
object MMCineverse {

    private const val TAG = "MM_Cineverse"
    private const val HUB = "https://rozgarlelo.modiplay.xyz"

    // hosts running the shared api v1 player with the static protocol derived cipher
    private val apiV1Hosts = mapOf(
        "multimovies.rpmhub.site" to "RPM Share",
        "multimovies.embedseek.xyz" to "Seek Streaming",
        "multimovies.p2pplay.pro" to "Stream P2P",
        "server1.uns.bio" to "Upn Share",
    )

    private val APIV1_KEY = "kiemtienmua911ca".toByteArray(Charsets.UTF_8)
    private val APIV1_IV = "1234567890oiuytr".toByteArray(Charsets.UTF_8)

    private data class SubServer(val url: String, val platform: String, val name: String, val code: String)

    private fun decryptApiV1(hex: String): JSONObject? {
        val data = try {
            MMCrypto.hexToBytes(hex)
        } catch (e: Exception) {
            Log.d(TAG, "api v1 hex: ${e.message}")
            return null
        }
        if (data.isEmpty() || data.size % 16 != 0) return null
        val plain = MMCrypto.aesCbcDecrypt(data, APIV1_KEY, APIV1_IV) ?: return null
        return try {
            JSONObject(String(plain, Charsets.UTF_8))
        } catch (e: Exception) {
            Log.d(TAG, "api v1 json: ${e.message}")
            null
        }
    }

    suspend fun resolveApiV1(
        host: String,
        code: String,
        label: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        MMNet.get(
            "https://$host/api/v1/info?id=$code",
            referer = "https://$host/",
        )?.let { decryptApiV1(it) }

        val videoHex = MMNet.get(
            "https://$host/api/v1/video?id=$code&w=1920&h=1080&r=$HUB",
            referer = "https://$host/",
        ) ?: return false
        val video = decryptApiV1(videoHex) ?: return false
        // only the video payload carries the release name the quality is read from
        val title = video.optString("title")

        var added = false
        val cfNative = video.optString("cfNative")
        if (cfNative.isNotBlank()) {
            val quality = Regex("(\\d{3,4})p").find(title)?.groupValues?.get(1)
            callback(
                newExtractorLink(label, label + (quality?.let { " ${it}p" } ?: ""), cfNative, type = ExtractorLinkType.M3U8)
            )
            added = true
        }
        val tiktok = video.optString("hlsVideoTiktok")
        if (tiktok.isNotBlank()) {
            val ttDomain = try {
                JSONObject(video.optString("streamingConfig"))
                    .getJSONObject("adjust")
                    .getJSONObject("Tiktok")
                    .optString("domain")
            } catch (e: Exception) {
                ""
            }
            val ttVersion = try {
                JSONObject(video.optString("streamingConfig"))
                    .getJSONObject("adjust")
                    .getJSONObject("Tiktok")
                    .getJSONObject("params")
                    .optString("v")
            } catch (e: Exception) {
                ""
            }
            val url = if (ttDomain.isNotBlank()) {
                "https://$host/hlsmod/$ttDomain$tiktok?v=$ttVersion"
            } else {
                "https://$host$tiktok"
            }
            callback(newExtractorLink(label, "$label Relay", url, type = ExtractorLinkType.M3U8))
            added = true
        }
        return added
    }

    private suspend fun resolveProxyPage(
        platform: String,
        code: String,
        displayName: String,
        labelPrefix: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val proxyUrl = "$HUB/proxy.php?p=${MMNet.urlEncode(platform)}&c=${MMNet.urlEncode(code)}" +
            "&title=&site_ref=${MMNet.urlEncode(MMSite.referer)}&noredirect=1"

        val resp = try {
            app.get(
                proxyUrl,
                headers = mapOf(
                    "User-Agent" to MMNet.UA,
                    "Referer" to "$HUB/",
                ),
                timeout = 30_000L,
                allowRedirects = false,
            )
        } catch (e: Exception) {
            Log.d(TAG, "proxy $platform: ${e.message}")
            return false
        }

        if (resp.code in 300..399) {
            val location = resp.headers["location"] ?: return false
            val host = MMNet.hostOf(location)
            val hash = location.substringAfter("#", "")
            val name = apiV1Hosts[host]
            if (name != null && hash.isNotBlank()) {
                return resolveApiV1(host, hash, "$labelPrefix $name", callback)
            }
            if (host == "bysetayico.com" && hash.isBlank()) {
                val fileCode = location.substringAfterLast("/")
                return MMFilemoonBridge.resolveByse("$labelPrefix Filemoon", fileCode, callback)
            }
            return false
        }

        return resolveProxyHtml(resp.text, "$labelPrefix $displayName", callback)
    }

    // the hub relay player either hands a var src playlist (relative to the hub)
    // or proxies the target page over with direct links in it
    private suspend fun resolveProxyHtml(html: String, label: String, callback: (ExtractorLink) -> Unit): Boolean {
        val src = Regex("var\\s+src=\"([^\"]+)\"").find(html)?.groupValues?.get(1)
        if (src != null) {
            val stream = MMNet.abs(HUB, MMNet.deEsc(src))
            if (stream.startsWith("http")) {
                callback(
                    newExtractorLink(
                        label,
                        label,
                        stream,
                        type = ExtractorLinkType.M3U8,
                    ) {
                        this.headers = mapOf("Referer" to "$HUB/")
                    }
                )
                return true
            }
        }
        val urls = Regex("https?://[^\"'\\s\\\\]+\\.m3u8[^\"'\\s\\\\]*")
            .findAll(html)
            .map { MMNet.deEsc(it.groupValues.first()) }
            .filter { it.startsWith("http") }
            .toSet()
        for (u in urls) {
            callback(
                newExtractorLink(
                    label,
                    label,
                    u,
                    type = ExtractorLinkType.M3U8,
                )
            )
        }
        return urls.isNotEmpty()
    }

    suspend fun resolve(
        embedUrl: String,
        labelPrefix: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val html = MMNet.get(embedUrl, referer = MMSite.referer) ?: return false
        val doc = Jsoup.parse(html)

        // switchServer(url, platform, displayName, fileCode, title, element)
        val servers = doc.select(".srv-item").mapNotNull { el ->
            val onclick = el.attr("onclick")
            val m = Regex("switchServer\\('([^']*)','([^']*)','([^']*)','([^']*)'").find(onclick)
                ?: return@mapNotNull null
            SubServer(MMNet.deEsc(m.groupValues[1]), m.groupValues[2], m.groupValues[3], m.groupValues[4])
        }.distinctBy { it.platform }

        // the default frame mirrors the first dropdown entry, keep the order stable
        val defaultFrame = doc.selectFirst("#playerFrame")?.attr("src")
        val ordered = if (defaultFrame != null) {
            val defaultPlatform = Regex("[?&]p=([^&]+)").find(defaultFrame)?.groupValues?.get(1)
            servers.sortedByDescending { it.platform == defaultPlatform }
        } else servers

        var any = false
        for (server in ordered) {
            try {
                any = resolveMirror(server, labelPrefix, callback) || any
            } catch (e: Exception) {
                // a dead mirror must not take the rest down
                Log.e(TAG, "server ${server.platform}: ${e.message}")
            }
        }
        return any
    }

    // a full url goes straight to the resolver that owns the host, everything
    // else falls back to the hub proxy which still knows every platform
    private suspend fun resolveMirror(
        server: SubServer,
        labelPrefix: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        if (server.url.startsWith("http")) {
            val host = MMNet.hostOf(server.url)
            val code = server.url.substringAfter("#", "").trim().ifBlank { server.code }
            val apiV1Name = apiV1Hosts[host]
            if (apiV1Name != null && code.isNotBlank()) {
                return resolveApiV1(host, code, "$labelPrefix $apiV1Name", callback)
            }
            if (host == "bysetayico.com") {
                return MMFilemoonBridge.resolveByse("$labelPrefix Filemoon", server.code, callback)
            }
            if (MMXvidStyle.isXvidStyle(host) &&
                MMXvidStyle.resolve(server.url, "$labelPrefix ${server.name}", callback)
            ) {
                return true
            }
            if (MMXvidStyle.resolveGeneric(server.url, "$labelPrefix ${server.name}", callback)) {
                return true
            }
        } else if (server.url.startsWith("/")) {
            val page = MMNet.get(MMNet.abs(HUB, server.url), referer = "$HUB/")
            if (page != null && resolveProxyHtml(page, "$labelPrefix ${server.name}", callback)) {
                return true
            }
        }
        return resolveProxyPage(server.platform, server.code, server.name, labelPrefix, callback)
    }
}

// translates bysetayico file links into the shared player domain before the heavy flow
object MMFilemoonBridge {

    private const val TAG = "MM_Filemoon"

    suspend fun resolveByse(
        label: String,
        code: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val details = MMNet.get(
            "https://bysetayico.com/api/videos/$code/embed/details",
            referer = "https://bysetayico.com/e/$code",
        ) ?: return false
        val frameUrl = try {
            JSONObject(details).optString("embed_frame_url")
        } catch (e: Exception) {
            Log.d(TAG, "byse details: ${e.message}")
            ""
        }
        if (frameUrl.isBlank()) return false
        return MMFilemoon.resolve(frameUrl, code, label, "bysetayico.com", callback)
    }
}
