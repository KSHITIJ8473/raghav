package com.laddu100

import com.lagradost.api.Log
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import org.jsoup.Jsoup

// the rozgarlelo hub fronts several mirror platforms; some it resolves server side
// into a relay player page, others it redirects to their own player domains
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

    private data class SubServer(val platform: String, val name: String, val code: String)

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
        val info = MMNet.get(
            "https://$host/api/v1/info?id=$code",
            referer = "https://$host/",
        )?.let { decryptApiV1(it) }
        val title = info?.optString("title").orEmpty()

        val videoHex = MMNet.get(
            "https://$host/api/v1/video?id=$code&w=1920&h=1080&r=$HUB",
            referer = "https://$host/",
        ) ?: return false
        val video = decryptApiV1(videoHex) ?: return false

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
            "&title=&site_ref=https%3A%2F%2Fmultimovies.garden%2F&noredirect=1"

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

        val html = resp.text
        val src = Regex("var\\s+src=\"([^\"]+)\"").find(html)?.groupValues?.get(1)
        if (src.isNullOrBlank()) return false
        val stream = MMNet.deEsc(src)
        callback(
            newExtractorLink(
                "$labelPrefix $displayName",
                "$labelPrefix $displayName",
                stream,
                type = ExtractorLinkType.M3U8,
            ) {
                this.headers = mapOf("Referer" to "$HUB/")
            }
        )
        return true
    }

    suspend fun resolve(
        embedUrl: String,
        labelPrefix: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val html = MMNet.get(embedUrl, referer = "https://multimovies.garden/") ?: return false
        val doc = Jsoup.parse(html)

        // switchServer(embedUrl, platform, displayName, fileCode, title, element)
        val servers = doc.select(".srv-item").mapNotNull { el ->
            val onclick = el.attr("onclick")
            val m = Regex("switchServer\\('([^']*)','([^']*)','([^']*)','([^']*)'").find(onclick)
                ?: return@mapNotNull null
            SubServer(m.groupValues[2], m.groupValues[3], m.groupValues[4])
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
                any = resolveProxyPage(
                    server.platform,
                    server.code,
                    server.name,
                    labelPrefix,
                    callback,
                ) || any
            } catch (e: Exception) {
                // a dead mirror must not take the rest down
                Log.e(TAG, "server ${server.platform}: ${e.message}")
            }
        }
        return any
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
