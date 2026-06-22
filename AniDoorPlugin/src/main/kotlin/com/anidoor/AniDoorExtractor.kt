package com.anidoor

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.DeserializationFeature
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.api.Log

open class MegaPlayExtractor(private val sourceName: String = "MegaPlay") : ExtractorApi() {
    override val name = sourceName
    override val mainUrl = "https://megaplay.buzz"
    override val requiresReferer = false

    private val mapper = ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val domain = Regex("""https?://[^/]+""").find(url)?.value ?: mainUrl
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0",
            "Accept" to "*/*",
            "X-Requested-With" to "XMLHttpRequest",
            "Referer" to "$domain/",
            "Origin" to domain
        )

        runCatching {
            val document = app.get(url, headers = headers).document
            val id = document.selectFirst("#megaplay-player")?.attr("data-id")?.takeIf { it.isNotBlank() }
                ?: Regex("""data-id=["'](\d+)""").find(document.html())?.groupValues?.get(1)
                ?: document.selectFirst("#megaplay-player")?.attr("data-realid")?.takeIf { it.isNotBlank() }
                ?: Regex("""data-realid=["'](\d+)""").find(document.html())?.groupValues?.get(1)
                ?: Regex("""/stream/s-\d+/(\d+)""").find(url)?.groupValues?.get(1)
                ?: Regex("""/stream/ani/\d+/\d+/sub""").find(url)?.let {
                    // Try parsing from url pattern
                    url.substringBefore("?").removeSuffix("/").substringAfterLast("/")
                } ?: return@runCatching

            val response = app.get("$domain/stream/getSources?id=$id", headers = headers).parsedSafe<Response>()
                ?: return@runCatching

            val sourcesNode = response.sources
            val m3u8 = when {
                sourcesNode == null -> null
                sourcesNode.isArray -> {
                    val first = sourcesNode.firstOrNull()
                    when {
                        first == null -> null
                        first.isObject -> first.get("file")?.asText()
                        first.isTextual -> first.asText()
                        else -> null
                    }
                }
                sourcesNode.isObject -> sourcesNode.get("file")?.asText()
                sourcesNode.isTextual -> sourcesNode.asText()
                else -> null
            } ?: return@runCatching

            generateM3u8(name, m3u8, domain, headers = headers).forEach(callback)
            response.tracks.forEach { track ->
                val file = track.file ?: return@forEach
                if (track.kind == "captions" || track.kind == "subtitles") {
                    subtitleCallback(newSubtitleFile(track.label ?: "Subtitle", file) {
                        this.headers = mapOf("Referer" to "$domain/", "Origin" to domain)
                    })
                }
            }
        }.onFailure { error ->
            Log.e(name, "API extraction failed, trying WebView: ${error.message}")
            val resolver = WebViewResolver(
                interceptUrl = Regex("""\.m3u8"""),
                additionalUrls = listOf(Regex("""\.m3u8""")),
                script = """document.querySelector('.jw-icon-display,.vds-play-button')?.click();""",
                useOkhttp = false,
                timeout = 15_000L
            )
            val m3u8 = app.get(url, referer = domain, interceptor = resolver).url
            if (m3u8.contains(".m3u8")) {
                generateM3u8(name, m3u8, domain, headers = headers).forEach(callback)
            }
        }
    }

    data class Response(
        @JsonProperty("sources") val sources: JsonNode? = null,
        @JsonProperty("tracks") val tracks: List<Track> = emptyList()
    )

    data class Track(
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("kind") val kind: String? = null
    )
}

class TryEmbedExtractor : ExtractorApi() {
    override var name = "TryEmbed"
    override var mainUrl = "https://tryembed.us.cc"
    override val requiresReferer = true

    private val mapper = ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val domain = Regex("""https?://[^/]+""").find(url)?.value ?: mainUrl
        
        // Headers mimicking browser CORS request
        val corsHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
            "Referer" to url,
            "Origin" to domain,
            "Accept" to "application/json,*/*",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "same-origin",
            "Sec-Fetch-Dest" to "empty"
        )

        runCatching {
            // First load the page to establish cookie/auth session
            val docResponse = app.get(url, headers = mapOf("User-Agent" to corsHeaders["User-Agent"]!!, "Referer" to (referer ?: "https://anidoor.me/")))
            val cookie = docResponse.headers.get("Set-Cookie") ?: ""

            // Decode RAW_PAYLOAD from inline js
            val html = docResponse.text
            val payloadMatch = Regex("""window\.RAW_PAYLOAD\s*=\s*["']([^"']+)["']""").find(html)
                ?: Regex("""RAW_PAYLOAD\s*=\s*["']([^"']+)["']""").find(html)
                ?: return@runCatching
            
            val decodedBytes = android.util.Base64.decode(payloadMatch.groupValues[1], android.util.Base64.DEFAULT)
            val decodedStr = String(decodedBytes, Charsets.UTF_8)
            val payloadNode = mapper.readTree(decodedStr)
            
            val metaNode = payloadNode.get("meta") ?: return@runCatching
            val anilistId = metaNode.get("anilist_id")?.asText() ?: return@runCatching
            val episode = metaNode.get("episode")?.asText() ?: return@runCatching
            val audio = metaNode.get("audio")?.asText() ?: "sub"

            val streamDataUrl = "$domain/api/stream_data?id=$anilistId&episode=$episode&audio=$audio"
            
            val apiHeaders = corsHeaders.toMutableMap()
            if (cookie.isNotEmpty()) {
                apiHeaders["Cookie"] = cookie
            }

            val apiResponse = app.get(streamDataUrl, headers = apiHeaders)
            if (apiResponse.code != 200) return@runCatching
            
            val dataNode = mapper.readTree(apiResponse.text)
            val providers = dataNode.get("providers") ?: return@runCatching
            
            if (providers.isArray) {
                for (provider in providers) {
                    val status = provider.get("status")?.asText() ?: ""
                    if (status != "ready") continue
                    
                    val providerName = provider.get("name")?.asText() ?: "TryEmbed"
                    val qualities = provider.get("qualities") ?: continue
                    
                    if (qualities.isArray) {
                        for (qualityNode in qualities) {
                            val label = qualityNode.get("name")?.asText() ?: "HD"
                            val directUrl = qualityNode.get("directUrl")?.asText()
                            
                            val streamUrl = when {
                                directUrl != null -> directUrl
                                else -> {
                                    val token = qualityNode.get("token")?.asText()
                                    if (token != null) "$domain/s/$token.m3u8" else null
                                }
                            }
                            
                            val fallbackToken = qualityNode.get("fallbackToken")?.asText()
                            val fallbackUrl = if (fallbackToken != null) "$domain/s/$fallbackToken.m3u8" else null
                            
                            if (streamUrl != null) {
                                callback(
                                    newExtractorLink(
                                        source = providerName,
                                        name = "$providerName - $label",
                                        url = streamUrl,
                                        type = ExtractorLinkType.M3U8
                                    ) {
                                        quality = getQualityFromName(label)
                                        this.headers = mapOf("Referer" to "$domain/")
                                    }
                                )
                            }
                            
                            if (fallbackUrl != null) {
                                callback(
                                    newExtractorLink(
                                        source = providerName,
                                        name = "$providerName - $label (Fallback)",
                                        url = fallbackUrl,
                                        type = ExtractorLinkType.M3U8
                                    ) {
                                        quality = getQualityFromName(label)
                                        this.headers = mapOf("Referer" to "$domain/")
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }.onFailure { error ->
            Log.e(name, "TryEmbed extraction failed: ${error.message}")
        }
    }
}
