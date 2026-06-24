package com.laddu100

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.api.Log

/** Extractor for embedindia.st. */
class EmbedIndiaExtractor : ExtractorApi() {
    override val name = "EmbedIndia"
    override val mainUrl = "https://embedindia.st"
    override val requiresReferer = false

    private val ua =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"

    private val m3u8Pattern =
        Regex("""(https?://[^\s"'\\]+\.m3u8(?:[^\s"'\\]*)?)""")

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

        // WebView resolver fallback
        try {
            val resolver = WebViewResolver(
                interceptUrl = Regex("""(?i)\.m3u8(?:\?|$)"""),
                additionalUrls = listOf(Regex("""(?i)\.m3u8(?:\?|$)""")),
                script = null,
                useOkhttp = false,
                timeout = 10_000L
            )
            val resolved = app.get(
                url,
                referer = referer ?: "$embedHost/",
                interceptor = resolver
            ).url
            if (resolved.contains(".m3u8", ignoreCase = true)) {
                emitLink(callback, name, resolved, embedHost, 0)
            } else {
                Log.w(name, "WebViewResolver returned non-m3u8 URL for $url: $resolved")
            }
        } catch (e: Exception) {
            Log.w(name, "WebViewResolver failed for $url: ${e.message}")
        }
    }

    private suspend fun emitLink(
        callback: (ExtractorLink) -> Unit,
        sourceName: String,
        m3u8Url: String,
        refererHost: String,
        index: Int
    ) {
        // Get host referrer
        val m3u8Host = try {
            val uri = java.net.URI(m3u8Url)
            "${uri.scheme}://${uri.host}"
        } catch (e: Exception) {
            refererHost
        }
        callback.invoke(
            newExtractorLink(
                source = sourceName,
                name = if (index == 0) sourceName else "$sourceName (Alt ${index + 1})",
                url = m3u8Url,
                type = ExtractorLinkType.M3U8
            ) {
                this.headers = mapOf(
                    "User-Agent" to ua,
                    "Referer" to "$m3u8Host/",
                    "Origin" to m3u8Host
                )
            }
        )
    }
}
