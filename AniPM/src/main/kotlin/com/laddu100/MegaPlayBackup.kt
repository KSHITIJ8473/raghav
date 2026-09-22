package com.laddu100

import android.util.Base64
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.api.Log
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object MegaPlayCipher {
    private const val TAG = "MegaPlay"
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
            Log.d(TAG, "seed fetch failed: ${e.message}")
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

object MegaPlayBackup {
    private const val TAG = "MegaPlay"
    private val mapper = ObjectMapper()

    class MegaPlayStream(val m3u8: String, val subtitles: List<Pair<String, String>>)

    fun audioTypeFromUrl(url: String): String? =
        Regex("""/(dub|sub)(?:[/?#]|$)""").find(url)?.groupValues?.get(1)

    suspend fun resolveStream(embedUrl: String, referer: String?): MegaPlayStream? {
        val host = Regex("""https?://([^/]+)""").find(embedUrl)?.groupValues?.get(1) ?: return null
        val pageHeaders = mapOf(
            "User-Agent" to AniPMApi.USER_AGENT,
            "Referer" to (referer ?: "https://$host/")
        )

        val pageHtml = try {
            app.get(embedUrl, headers = pageHeaders).text
        } catch (e: Exception) {
            Log.d(TAG, "embed page failed for $host: ${e.message}")
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
                "User-Agent" to AniPMApi.USER_AGENT,
                "Accept" to "*/*",
                "X-Requested-With" to "XMLHttpRequest",
                "Origin" to base,
                "Referer" to embedUrl
            )
            for (endpoint in listOf("getSourcesNew", "getSources")) {
                val url = "$base/stream/$endpoint?id=$streamId&type=$audioType"
                val root = fetchJson(url, ajaxHeaders) ?: continue
                val streamUrl = extractStream(root, base)
                if (streamUrl != null) {
                    return MegaPlayStream(streamUrl, parseSubtitleTracks(root))
                }
            }
        }
        return null
    }

    private suspend fun extractStream(root: JsonNode, base: String): String? {
        val sources = root.get("sources")
        val plain = when {
            sources == null || sources.isNull -> null
            sources.isObject -> sources.get("file")?.asText()
            sources.isArray && sources.size() > 0 -> sources.get(0)?.get("file")?.asText()
            else -> null
        }
        if (!plain.isNullOrBlank()) return plain

        val enc = root.get("enc")?.takeIf { !it.isNull }?.asText() ?: return null
        return MegaPlayCipher.resolveEncStreamUrl(enc, base)
    }

    private fun parseSubtitleTracks(root: JsonNode): List<Pair<String, String>> {
        val subs = mutableListOf<Pair<String, String>>()
        val tracks = root.get("tracks") ?: return subs
        if (!tracks.isArray) return subs
        for (element in tracks) {
            val kind = element.get("kind")?.asText() ?: continue
            if (kind != "captions" && kind != "subtitles") continue
            val file = element.get("file")?.asText() ?: continue
            if (file.isBlank()) continue
            subs.add((element.get("label")?.asText() ?: "English") to file)
        }
        return subs
    }

    private suspend fun fetchJson(url: String, headers: Map<String, String>): JsonNode? {
        return try {
            mapper.readTree(app.get(url, headers = headers, timeout = 15_000L).text)
        } catch (e: Exception) {
            Log.d(TAG, "sources request failed: ${e.message}")
            null
        }
    }

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

    private val resolutionRegex = Regex("""RESOLUTION=(\d+)x(\d+)""")

    private fun parseVariants(masterUrl: String, masterText: String): List<VariantEntry> {
        val base = masterUrl.substringBefore('?').substringBeforeLast('/') + "/"
        val out = mutableListOf<VariantEntry>()
        val lines = masterText.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.startsWith("#EXT-X-STREAM-INF:")) {
                val quality = resolutionRegex.find(line)?.groupValues?.get(2)?.toIntOrNull()
                var j = i + 1
                while (j < lines.size && (lines[j].isBlank() || lines[j].startsWith("#"))) j++
                if (j < lines.size) {
                    val uri = lines[j].trim()
                    if (uri.isNotEmpty()) {
                        out.add(VariantEntry(if (uri.startsWith("http")) uri else base + uri, quality))
                    }
                    i = j
                }
            }
            i++
        }
        return out
    }

    // codec-less variants crash the ffmpeg renderer at track selection, so each quality goes out as its own playlist
    suspend fun emitVariantLinks(
        source: String,
        label: String,
        m3u8: String,
        referer: String,
        headers: Map<String, String>,
        seenLinks: MutableSet<String>,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val masterText = try {
            app.get(signUrl(m3u8), headers = headers, timeout = 15_000L).text
        } catch (e: Exception) {
            Log.d(TAG, "master playlist fetch failed: ${e.message}")
            null
        }

        val variants = masterText?.let { parseVariants(m3u8, it) }.orEmpty()
        if (variants.isEmpty()) {
            if (!seenLinks.add(m3u8)) return true
            callback.invoke(
                newExtractorLink(source, label, signUrl(m3u8), type = ExtractorLinkType.M3U8) {
                    this.referer = referer
                    this.headers = headers
                }
            )
            return true
        }

        var found = false
        for (v in variants) {
            if (!seenLinks.add(v.url)) continue
            val suffix = v.quality?.let { "${it}p" }.orEmpty()
            callback.invoke(
                newExtractorLink(
                    source,
                    if (suffix.isEmpty()) label else "$label $suffix",
                    signUrl(v.url),
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = referer
                    v.quality?.let { quality = it }
                    this.headers = headers
                }
            )
            found = true
        }
        return found
    }
}
