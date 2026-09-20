package com.laddu100.raghavanime

import android.util.Base64
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup

// AniSuge serves per-episode sources through the mapper api its watch page
// loads via assets/js/mapper.js:
//   https://mapper.nekostream.site/api/mal/{data-mal}/{data-slug}/{data-timestamp}
// each provider node holds sub/dub entries with a stream url and a download map
object AniSugeMapper {
    private val json = ObjectMapper()
    private const val MAPPER_API = "https://mapper.nekostream.site/api/mal/"

    class MapperEntry(val url: String?, val downloads: Map<String, String>)

    suspend fun fetchProviders(malId: String, slug: String, timestamp: String): Map<String, Pair<MapperEntry?, MapperEntry?>>? {
        val text = try {
            app.get(
                MAPPER_API + "$malId/$slug/$timestamp",
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
                    "Accept" to "application/json"
                ),
                timeout = 15_000L
            ).text
        } catch (e: Exception) {
            Log.e("AniSuge", "mapper fetch failed: ${e.message}")
            return null
        }
        val root = try {
            json.readTree(text)
        } catch (e: Exception) {
            Log.e("AniSuge", "mapper response not json: ${e.message}")
            return null
        }
        if (!root.isObject) return null

        val out = linkedMapOf<String, Pair<MapperEntry?, MapperEntry?>>()
        val fields = root.fields()
        while (fields.hasNext()) {
            val (key, value) = fields.next()
            if (!value.isObject) continue
            out[key] = parseEntry(value.get("sub")) to parseEntry(value.get("dub"))
        }
        return out
    }

    private fun parseEntry(node: JsonNode?): MapperEntry? {
        if (node == null || !node.isObject) return null
        val url = node.get("url")?.takeIf { it.isTextual }?.asText()?.takeIf { it.isNotBlank() }
        val downloads = linkedMapOf<String, String>()
        val dl = node.get("download")
        if (dl != null && dl.isObject) {
            val it = dl.fields()
            while (it.hasNext()) {
                val (q, u) = it.next()
                if (u.isTextual && u.asText().startsWith("http")) downloads[q] = u.asText()
            }
        }
        if (url == null && downloads.isEmpty()) return null
        return MapperEntry(url, downloads)
    }

    // display names match the site's mapper.js
    fun displayProviderName(key: String): String = when (key.lowercase()) {
        "gogoanime" -> "Vidstream"
        "anivibe" -> "vibe-Stream"
        "animepahe" -> "Kiwi-Stream"
        else -> key.replaceFirstChar { it.uppercase() }
    }
}

// pahe download pages build a workers.dev url client-side; requesting it
// redirects to the kwik.cx file page
object PaheDownloadResolver {
    private val workersUrlRegex = Regex("""const\s+url\s*=\s*"(https?://[^"]+)"""")
    private val anyHttpsRegex = Regex(""""(https?://[^"]*workers\.dev[^"]*)"""")

    suspend fun resolveKwikUrl(paheUrl: String): String? {
        val page = try {
            app.get(
                paheUrl,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
                ),
                timeout = 15_000L
            ).text
        } catch (e: Exception) {
            Log.e("AniSuge", "pahe page failed: ${e.message}")
            return null
        }

        val base = workersUrlRegex.find(page)?.groupValues?.get(1)
            ?: anyHttpsRegex.find(page)?.groupValues?.get(1)
            ?: return null

        val redirector = base.trimEnd('/') + "/" + paheUrl.trimEnd('/').substringAfterLast('/')
        return try {
            val res = app.get(redirector, allowRedirects = false)
            val loc = res.headers["location"]
            if (loc != null && loc.startsWith("http")) loc else null
        } catch (e: Exception) {
            Log.e("AniSuge", "workers redirect failed: ${e.message}")
            null
        }
    }
}

// kwik.cx file pages pack their player script; the unpacked script either
// holds the source directly or a form whose action 302s to it
class KwikExtractor : ExtractorApi() {
    override val name = "Kwik"
    override val mainUrl = "https://kwik.cx"
    override val requiresReferer = true

    private val sourceRegex = Regex("""source\s*=\s*["']([^"']+)["']""")
    private val formActionRegex = Regex("""action="([^"]+)"""")
    private val formTokenRegex = Regex("""value="([^"]+)"""")

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        resolve(url, referer)?.let { (playUrl, pageUrl) ->
            callback.invoke(
                newExtractorLink(
                    name, name, playUrl,
                    if (playUrl.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = pageUrl
                    this.headers = mapOf("Referer" to pageUrl)
                }
            )
        } ?: run {
            Log.e("Kwik", "extraction failed for $url")
        }
    }

    suspend fun resolve(url: String, referer: String?): Pair<String, String>? {
        val page = try {
            app.get(
                url,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
                    "Referer" to (referer ?: mainUrl)
                ),
                timeout = 20_000L
            )
        } catch (e: Exception) {
            Log.e("Kwik", "page fetch failed: ${e.message}")
            return null
        }
        val html = page.text

        val unpacked = try {
            val packed = Jsoup.parse(html).selectFirst("script:containsData(function(p,a,c,k,e,d))")?.data()
            packed?.let { getAndUnpack(it) }
        } catch (e: Exception) {
            Log.e("Kwik", "unpack failed: ${e.message}")
            null
        }

        if (unpacked != null) {
            sourceRegex.find(unpacked)?.groupValues?.get(1)?.let { src ->
                if (src.startsWith("http")) return src to page.url
            }

            val action = formActionRegex.find(unpacked)?.groupValues?.get(1)
            val token = formTokenRegex.find(unpacked)?.groupValues?.get(1)
            if (action != null && token != null) {
                return postForRedirect(action, token, page.url)
            }
        }

        // some mirrors inline the source without a packer
        sourceRegex.find(html)?.groupValues?.get(1)?.let { src ->
            if (src.startsWith("http") && (src.contains(".m3u8") || src.contains(".mp4"))) {
                return src to page.url
            }
        }
        return null
    }

    private suspend fun postForRedirect(action: String, token: String, pageUrl: String): Pair<String, String>? {
        var tries = 0
        var code = 419
        var location = ""
        while (code != 302 && tries < 10) {
            try {
                val res = app.post(
                    action,
                    headers = mapOf(
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
                        "Referer" to pageUrl
                    ),
                    data = mapOf("_token" to token),
                    allowRedirects = false
                )
                code = res.code
                if (code == 302) location = res.headers["location"] ?: ""
            } catch (e: Exception) {
                Log.e("Kwik", "post attempt failed: ${e.message}")
            }
            tries++
        }
        return location.takeIf { it.startsWith("http") }?.let { it to pageUrl }
    }
}
