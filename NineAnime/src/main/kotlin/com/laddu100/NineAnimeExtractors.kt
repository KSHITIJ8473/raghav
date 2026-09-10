package com.laddu100

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import android.util.Base64
import com.lagradost.api.Log
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object MegaPlayCipher {
    private const val TAG = "MegaPlayCipher"
    private const val FALLBACK_KEY_SEED = "i?LMTAx0Q6,:}50U"
    private const val FALLBACK_IV_SEED = "W0;27ToaUpl_P%'c"

    @Volatile
    private var cachedSeeds: Pair<String, String>? = null

    private val keyPairRegex = Regex("[A-Za-z]\\w*=\"([^\"]{16})\",[A-Za-z]\\w*=\"([^\"]{16})\"")
    private val fileRegex = Regex("\"file\"\\s*:\\s*\"([^\"]+)\"")

    private fun fallback() = Pair(FALLBACK_KEY_SEED, FALLBACK_IV_SEED)

    private suspend fun keySeedCandidates(baseUrl: String): List<Pair<String, String>> {
        cachedSeeds?.let { return listOf(it, fallback()) }
        val dynamic = try {
            val js = app.get("$baseUrl/lib/newclient.min.js", timeout = 10_000L).text
            keyPairRegex.find(js)?.groupValues?.let { g ->
                Pair(g[1], g[2]).also { cachedSeeds = it }
            }
        } catch (e: Exception) {
            Log.w(TAG, "key seed fetch failed: ${e.message}")
            null
        }
        return listOfNotNull(dynamic, fallback())
    }

    private fun decryptToken(enc: String, keySeed: String, ivSeed: String): String? {
        return try {
            var b64 = enc.replace('-', '+').replace('_', '/')
            while (b64.length % 4 != 0) b64 += "="
            val cipherBytes = Base64.decode(b64, Base64.DEFAULT)

            val seedBytes = keySeed.toByteArray(Charsets.UTF_8)
            val keyBytes = ByteArray(32)
            System.arraycopy(seedBytes, 0, keyBytes, 0, minOf(32, seedBytes.size))

            val ivBytes = ByteArray(16)
            val ivSeedBytes = ivSeed.toByteArray(Charsets.UTF_8)
            System.arraycopy(ivSeedBytes, 0, ivBytes, 0, minOf(16, ivSeedBytes.size))

            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(ivBytes))
            String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.d(TAG, "token decrypt failed: ${e.message}")
            null
        }
    }

    suspend fun resolveEncStreamUrl(enc: String, baseUrl: String): String? {
        for ((keySeed, ivSeed) in keySeedCandidates(baseUrl)) {
            val plain = decryptToken(enc, keySeed, ivSeed) ?: continue
            fileRegex.find(plain)?.groupValues?.get(1)?.let { return it }
        }
        return null
    }
}

open class MegaPlayBaseExtractor(
    override val name: String,
    override val mainUrl: String
) : ExtractorApi() {
    override val requiresReferer = true

    // legacy imgnex cdn paths carry an /anime prefix the megap hosts dropped
    private fun migrateLegacyUrl(url: String, cdnOrigin: String?): String {
        if (!url.contains("https://cdn.imgnex.top/anime")) return url
        return url.replace("https://cdn.imgnex.top/anime", cdnOrigin ?: "https://megap.norami.top")
    }

    private fun cdnOriginFor(streamUrl: String): String? {
        if (streamUrl.contains("cdn.imgnex.top")) return null
        return Regex("""https?://[^/]+""").find(streamUrl)?.value
    }

    private suspend fun fetchSourcesRoot(endpoint: String, headers: Map<String, String>): JsonObject? {
        val text = try {
            app.get(endpoint, headers = headers).text
        } catch (e: Exception) {
            Log.e(name, "sources request failed ($endpoint): ${e.message}")
            return null
        }
        return try {
            JsonParser.parseString(text).asJsonObject
        } catch (e: Exception) {
            Log.e(name, "sources JSON parse failed: ${e.message}")
            null
        }
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val pageHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to (referer ?: "https://9anime.org.lv/")
        )

        val doc = try {
            app.get(url, headers = pageHeaders).document
        } catch (e: Exception) {
            Log.e(name, "Failed to load player iframe: ${e.message}")
            return
        }

        val playerEl = doc.selectFirst("#megaplay-player")
        val streamId = playerEl?.attr("data-id")
            ?: playerEl?.attr("data-realid")
            ?: Regex("""/stream/s-\d+/(\d+)/""").find(url)?.groupValues?.get(1)
            ?: return

        val type = if (url.contains("/dub", ignoreCase = true)) "dub" else "sub"

        val ajaxHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "*/*",
            "X-Requested-With" to "XMLHttpRequest",
            "Referer" to url,
        )

        // legacy getSources still returns encrypted payloads pinned to the dead imgnex host
        val root = fetchSourcesRoot("$mainUrl/stream/getSourcesNew?id=$streamId&type=$type", ajaxHeaders)
            ?: fetchSourcesRoot("$mainUrl/stream/getSources?id=$streamId&type=$type", ajaxHeaders)
            ?: return

        val resolved = extractStreamUrl(root)
        if (resolved.isNullOrBlank()) {
            Log.e(name, "No stream url in sources response for id=$streamId")
            return
        }

        val cdnOrigin = cdnOriginFor(resolved)
        val m3u8 = migrateLegacyUrl(resolved, cdnOrigin)

        val playbackHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "*/*",
            "Referer" to "$mainUrl/",
        )

        val generated = M3u8Helper.generateM3u8(name, m3u8, mainUrl, headers = playbackHeaders)
        if (generated.isNotEmpty()) {
            generated.forEach(callback)
        } else {
            callback(
                newExtractorLink(name, name, m3u8, ExtractorLinkType.M3U8) {
                    this.referer = "$mainUrl/"
                    this.headers = playbackHeaders
                }
            )
        }

        emitSubtitles(root, cdnOrigin, playbackHeaders, subtitleCallback)
    }

    private suspend fun extractStreamUrl(root: JsonObject): String? {
        val sources = root.get("sources")
        val legacy = when {
            sources == null -> null
            sources.isJsonObject -> sources.asJsonObject.get("file")?.asString
            sources.isJsonArray -> sources.asJsonArray.firstOrNull()?.asJsonObject?.get("file")?.asString
            else -> null
        }
        if (!legacy.isNullOrBlank()) return legacy
        val enc = root.get("enc")?.asString ?: return null
        return MegaPlayCipher.resolveEncStreamUrl(enc, mainUrl)
    }

    private suspend fun emitSubtitles(
        root: JsonObject,
        cdnOrigin: String?,
        headers: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        try {
            val tracks = root.getAsJsonArray("tracks") ?: return
            for (element in tracks) {
                val track = element.asJsonObject
                val kind = track.get("kind")?.asString ?: continue
                if (kind != "captions" && kind != "subtitles") continue
                val file = track.get("file")?.asString ?: continue
                val label = track.get("label")?.asString ?: "Unknown"
                subtitleCallback(
                    newSubtitleFile(label, migrateLegacyUrl(file, cdnOrigin)) {
                        this.headers = headers
                    }
                )
            }
        } catch (e: Exception) {
            Log.w(name, "subtitle track parse failed: ${e.message}")
        }
    }
}

class NineAnimeMegaPlay : MegaPlayBaseExtractor("MegaPlay", "https://megaplay.buzz")

class NineAnimeVidmoly : ExtractorApi() {
    override val name = "Vidmoly"
    override val mainUrl = "https://vidmoly.biz"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to (referer ?: "https://9anime.org.lv/")
        )
        val res = try {
            app.get(url, headers = headers)
        } catch (e: Exception) {
            Log.e(name, "Failed to load Vidmoly iframe: ${e.message}")
            return
        }
        val html = res.text

        val m3u8 = Regex("""file\s*:\s*['"](https?://[^'"]+?\.m3u8[^'"]*)['"]""").find(html)
            ?.groupValues?.get(1)
            ?: Regex("""https?://[^'"\s]+?\.m3u8[^'"\s]*""").find(html)?.value
        if (m3u8 == null) {
            Log.e(name, "No m3u8 found on Vidmoly page")
            return
        }

        val playbackHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to "$mainUrl/",
            "Origin" to mainUrl
        )

        val generated = M3u8Helper.generateM3u8(name, m3u8, mainUrl, headers = playbackHeaders)
        if (generated.isNotEmpty()) {
            generated.forEach(callback)
        } else {
            callback(
                newExtractorLink(name, name, m3u8, ExtractorLinkType.M3U8) {
                    this.referer = "$mainUrl/"
                    this.headers = playbackHeaders
                }
            )
        }
    }
}

class NineAnimeMoon : ExtractorApi() {
    override val name = "Moon"
    override val mainUrl = "https://bysesayeveum.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        runCatching {
            val resolver = WebViewResolver(
                interceptUrl = Regex("""(?i)\.(m3u8|mp4)(?:\?|$)"""),
                additionalUrls = listOf(Regex("""(?i)\.(m3u8|mp4)(?:\?|$)""")),
                // the byse player mounts in a cross-origin iframe, the outer click forwards to the inner play button
                script = """document.querySelector('button,[role="button"],.vjs-big-play-button,.jw-icon-display,.vds-play-button,[onclick]')?.click();""",
                // the moon player handshake routinely takes over a minute to clear
                useOkhttp = false,
                timeout = 90_000L
            )
            val resolved = app.get(url, referer = referer ?: "https://9anime.org.lv/", interceptor = resolver).url
            val headers = mapOf("Referer" to url)

            if (resolved.contains(".m3u8", ignoreCase = true)) {
                M3u8Helper.generateM3u8(name, resolved, url, headers = headers).forEach(callback)
            } else if (resolved.contains(".mp4", ignoreCase = true)) {
                callback(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = resolved,
                        type = INFER_TYPE
                    ) {
                        quality = getQualityFromName(resolved)
                        this.headers = headers
                    }
                )
            }
        }.onFailure { error ->
            Log.e(name, "WebView extraction failed: ${error.message}")
        }
    }
}
