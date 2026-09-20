package com.laddu100.raghavanime

import android.util.Base64
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

// megaplay encrypts the enc field of getSources responses, seeds live in
// lib/newclient.min.js with pinned fallbacks
object MegaPlayCipher {
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
            Log.d("MegaPlay", "seed fetch failed: ${e.message}")
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
            Log.d("MegaPlay", "token decrypt failed: ${e.message}")
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

// megaplay-style players expose the playlist through getSourcesNew, the
// legacy endpoint only carries an encrypted payload on a dead cdn
object MegaPlayHelper {

    private const val TAG = "RaghavAnimeKitsu"
    private val mapper = ObjectMapper()

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    class MegaPlayStream(val m3u8: String, val subtitles: List<Pair<String, String>>)

    fun audioTypeFromUrl(url: String): String? =
        Regex("""/(dub|sub)(?:[/?#]|$)""").find(url)?.groupValues?.get(1)

    suspend fun resolveStream(
        embedUrl: String,
        referer: String?,
        sourceTag: String
    ): MegaPlayStream? {
        val host = Regex("""https?://([^/]+)""").find(embedUrl)?.groupValues?.get(1) ?: return null
        val pageHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to (referer ?: "https://$host/")
        )

        val pageHtml = try {
            app.get(embedUrl, headers = pageHeaders).text
        } catch (e: Exception) {
            Log.d(TAG, "[MegaPlay] embed page failed for $host: ${e.message}")
            return null
        }

        val streamId = Regex("""data-id=["'](\d+)""").find(pageHtml)?.groupValues?.get(1)
            ?: Regex("""data-realid=["'](\d+)""").find(pageHtml)?.groupValues?.get(1)
            ?: Regex("""/stream/s-\d+/(\d+)/""").find(embedUrl)?.groupValues?.get(1)
            ?: return null

        val audioType = audioTypeFromUrl(embedUrl)
            ?: Regex("""type\s*:\s*['"](dub|sub)['"]""").find(pageHtml)?.groupValues?.get(1)
            ?: "sub"

        val altHost = Regex("""data-domain=["']([^"']+)["']""").find(pageHtml)?.groupValues?.get(1)
        val hosts = listOfNotNull(host, altHost?.takeIf { it != host }).distinct()

        for (apiHost in hosts) {
            val base = "https://$apiHost"
            val ajaxHeaders = mapOf(
                "User-Agent" to USER_AGENT,
                "Accept" to "*/*",
                "X-Requested-With" to "XMLHttpRequest",
                "Origin" to base,
                "Referer" to embedUrl
            )
            for (endpoint in listOf("getSourcesNew", "getSources")) {
                val root = fetchJson("$base/stream/$endpoint?id=$streamId&type=$audioType", ajaxHeaders)
                    ?: continue
                val streamUrl = extractStream(root, base)
                if (streamUrl != null) {
                    val subs = parseSubtitleTracks(root, streamUrl)
                    return MegaPlayStream(streamUrl, subs)
                }
            }
        }
        return null
    }

    private suspend fun extractStream(root: JsonNode, base: String): String? {
        val sources = root.get("sources")
        val plain = when {
            sources == null -> null
            sources.isObject -> sources.get("file")?.asText()
            sources.isArray && sources.size() > 0 -> sources.get(0)?.get("file")?.asText()
            else -> null
        }
        if (!plain.isNullOrBlank()) return migrateLegacyUrl(plain)

        val enc = root.get("enc")?.takeIf { !it.isNull }?.asText() ?: return null
        val resolved = MegaPlayCipher.resolveEncStreamUrl(enc, base) ?: return null
        return migrateLegacyUrl(resolved)
    }

    // legacy imgnex paths carry an /anime prefix the megap mirrors dropped
    private fun migrateLegacyUrl(url: String): String {
        if (!url.contains("https://cdn.imgnex.top/anime")) return url
        return url.replace("https://cdn.imgnex.top/anime", "https://megap.norami.top")
    }

    private fun parseSubtitleTracks(root: JsonNode, m3u8: String): List<Pair<String, String>> {
        val subs = mutableListOf<Pair<String, String>>()
        val origin = Regex("""https?://[^/]+""").find(m3u8)?.value ?: "https://megap.norami.top"
        val tracks = root.get("tracks") ?: return subs
        if (!tracks.isArray) return subs
        for (element in tracks) {
            val kind = element.get("kind")?.asText() ?: continue
            if (kind != "captions" && kind != "subtitles") continue
            val file = element.get("file")?.asText() ?: continue
            if (file.isBlank()) continue
            val migrated = if (file.contains("https://cdn.imgnex.top/anime")) {
                file.replace("https://cdn.imgnex.top/anime", origin)
            } else file
            subs.add((element.get("label")?.asText() ?: "English") to migrated)
        }
        return subs
    }

    private suspend fun fetchJson(url: String, headers: Map<String, String>): JsonNode? {
        return try {
            mapper.readTree(app.get(url, headers = headers, timeout = 15_000L).text)
        } catch (e: Exception) {
            Log.d(TAG, "[MegaPlay] sources request failed: ${e.message}")
            null
        }
    }

    // the cdn 403s master.m3u8 without a token but only rejects expired ones
    private const val TOKEN_KEY = "MpCdnT0k3n!9f2K#xQ7vL5mR8wN1pY4s"
    private const val TOKEN_LIFETIME_SECONDS = 7L * 24L * 60L * 60L
    private val hexIdsRegex = Regex("""/([a-f0-9]{32})/([a-f0-9]{32})/""", RegexOption.IGNORE_CASE)

    private fun b64url(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    fun signUrl(url: String): String {
        val m = hexIdsRegex.find(url) ?: return url
        val expires = System.currentTimeMillis() / 1000L + TOKEN_LIFETIME_SECONDS
        val payload = "$expires|${m.groupValues[1].lowercase()}/${m.groupValues[2].lowercase()}"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(TOKEN_KEY.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val signature = mac.doFinal(payload.toByteArray(Charsets.UTF_8))
        val token = "${b64url(payload.toByteArray(Charsets.UTF_8))}.${b64url(signature)}"
        val sep = if (url.contains('?')) "&" else "?"
        return "$url${sep}token=$token"
    }

    private data class VariantEntry(val url: String, val quality: Int?)

    // i-frame entries are inline attributes so they never match the line
    // after #EXT-X-STREAM-INF
    private fun parseVariants(masterUrl: String, masterText: String): List<VariantEntry> {
        val base = masterUrl.substringBefore('?').let { it.substringBeforeLast('/') + "/" }
        val out = mutableListOf<VariantEntry>()
        val lines = masterText.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.startsWith("#EXT-X-STREAM-INF:")) {
                val res = Regex("""RESOLUTION=(\d+)x(\d+)""").find(line)
                val quality = res?.groupValues?.get(2)?.toIntOrNull()
                var j = i + 1
                while (j < lines.size && (lines[j].isBlank() || lines[j].startsWith("#"))) j++
                if (j < lines.size) {
                    val uri = lines[j].trim()
                    if (uri.isNotEmpty()) {
                        val absolute = if (uri.startsWith("http")) uri else base + uri
                        out.add(VariantEntry(absolute, quality))
                    }
                    i = j
                }
            }
            i++
        }
        return out
    }

    // one signed link per quality variant, the signed master as fallback
    suspend fun emitLinks(
        source: String,
        label: String,
        m3u8: String,
        referer: String,
        subtitles: List<Pair<String, String>>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (com.lagradost.cloudstream3.utils.ExtractorLink) -> Unit,
        withQualitySuffix: Boolean = true
    ): Boolean {
        val playHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to referer
        )

        val signedMaster = signUrl(m3u8)
        val masterText = try {
            app.get(signedMaster, headers = playHeaders, timeout = 15_000L).text
        } catch (e: Exception) {
            Log.d(TAG, "[MegaPlay] master playlist fetch failed: ${e.message}")
            null
        }

        var found = false
        val variants = masterText?.let { parseVariants(m3u8, it) } ?: emptyList()
        if (variants.isNotEmpty()) {
            for (v in variants) {
                val suffix = if (withQualitySuffix) v.quality?.let { "${it}p" } ?: "" else ""
                callback.invoke(
                    newExtractorLink(
                        source,
                        if (suffix.isEmpty()) label else "$label $suffix",
                        signUrl(v.url),
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = referer
                        v.quality?.let { quality = it }
                        this.headers = playHeaders
                    }
                )
                found = true
            }
        } else {
            callback.invoke(
                newExtractorLink(source, label, signedMaster, type = ExtractorLinkType.M3U8) {
                    this.referer = referer
                    this.headers = playHeaders
                }
            )
            found = true
        }

        for ((subLabel, subUrl) in subtitles) {
            subtitleCallback.invoke(
                newSubtitleFile(subLabel, subUrl) {
                    this.headers = playHeaders
                }
            )
        }
        return found
    }
}
