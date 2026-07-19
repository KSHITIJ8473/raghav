package com.laddu100.raghavanime

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import java.net.URLEncoder

class RaghavAnidap : MainAPI() {
    override var mainUrl = "https://anidap.se"
    override var name = "Anidap"
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private val chadHost get() = mainUrl.replace("://", "://chad.")
    private val chadUrl get() = "$chadHost/rest/api"
    private val TAG = "RaghavAnidap"
    private val baseHeaders get() = mapOf("Referer" to "$mainUrl/home")

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SearchResponseData(
        @JsonProperty("results") val results: List<AnimeItem>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AnimeItem(
        @JsonProperty("id") val id: String,
        @JsonProperty("title") val title: AnimeTitle? = null,
        @JsonProperty("image") val image: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AnimeTitle(
        @JsonProperty("romaji") val romaji: String? = null,
        @JsonProperty("english") val english: String? = null,
        @JsonProperty("userPreferred") val userPreferred: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AnimeDetail(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("title") val title: AnimeTitle? = null,
        @JsonProperty("titleEnglish") val titleEnglish: String? = null,
        @JsonProperty("coverImage") val coverImage: CoverImage? = null,
        @JsonProperty("episodes") val episodes: Int? = null,
        @JsonProperty("totalEpisodes") val totalEpisodes: Int? = null,
        @JsonProperty("slug") val slug: String? = null,
        @JsonProperty("format") val format: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class CoverImage(
        @JsonProperty("large") val large: String? = null,
        @JsonProperty("extraLarge") val extraLarge: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ServersResponse(
        @JsonProperty("subProviders") val subProviders: List<Provider>? = null,
        @JsonProperty("dubProviders") val dubProviders: List<Provider>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Provider(
        @JsonProperty("id") val id: String,
        @JsonProperty("default") val default: Boolean? = null,
        @JsonProperty("tip") val tip: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SourcesResponse(
        @JsonProperty("sources") val sources: List<Source>? = null,
        @JsonProperty("tracks") val tracks: List<Track>? = null,
        @JsonProperty("headers") val headers: Map<String, String>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Source(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("quality") val quality: String? = null,
        @JsonProperty("type") val type: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Track(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("kind") val kind: String? = null,
        @JsonProperty("lang") val lang: String? = null
    )

    private fun cleanTitle(s: String): String {
        return s.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun parseQuality(qualityStr: String?): Int {
        if (qualityStr.isNullOrBlank() || qualityStr.equals("auto", ignoreCase = true)) {
            return Qualities.Unknown.value
        }
        val match = Regex("(\\d{3,4})").find(qualityStr)
        val height = match?.groupValues?.get(1)?.toIntOrNull() ?: return Qualities.Unknown.value
        return when {
            height >= 2160 -> Qualities.P2160.value
            height >= 1440 -> Qualities.P1440.value
            height >= 1080 -> Qualities.P1080.value
            height >= 720 -> Qualities.P720.value
            height >= 600 -> Qualities.P720.value
            height >= 480 -> Qualities.P480.value
            height >= 360 -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    private fun buildLabel(providerId: String, quality: String?): String {
        val q = if (!quality.isNullOrBlank() && !quality.equals("auto", ignoreCase = true)) " $quality" else ""
        return "$name - $providerId$q"
    }

    override suspend fun search(query: String): List<SearchResponse> {
        mainUrl = FirebaseDomainHelper.getDomain("anidap") ?: mainUrl
        if (query.length < 2) return emptyList()
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "$mainUrl/api/anime/search?q=$encoded"

        var lastError: String? = null
        for (attempt in 1..3) {
            try {
                val res = app.get(url, headers = baseHeaders, timeout = 15_000L)
                val body = res.text
                if (body.contains("error code", ignoreCase = true)) {
                    lastError = "server error: ${body.take(50)}"
                    kotlinx.coroutines.delay(2000L * attempt)
                    continue
                }
                val parsed = parseJson<SearchResponseData>(body)
                val results = parsed.results ?: emptyList()
                return results.mapNotNull { item ->
                    val title = item.title?.userPreferred ?: item.title?.english ?: item.title?.romaji
                        ?: return@mapNotNull null
                    if (title == "Unknown") return@mapNotNull null
                    val data = "$mainUrl|${item.id}"
                    newAnimeSearchResponse(title, data, TvType.Anime) {
                        this.posterUrl = item.image
                        addDubStatus(dubExist = true, subExist = true)
                    }
                }
            } catch (e: Exception) {
                lastError = e.message
                kotlinx.coroutines.delay(2000L * attempt)
            }
        }
        return emptyList()
    }

    override suspend fun load(url: String): LoadResponse? {
        mainUrl = FirebaseDomainHelper.getDomain("anidap") ?: mainUrl
        val animeId = url.removePrefix("$mainUrl/").removePrefix("$mainUrl|").trim()

        return try {

            val detailRes = app.get("$mainUrl/api/anime/$animeId", headers = baseHeaders, timeout = 30_000L)
            val detailRoot = parseJson<com.fasterxml.jackson.databind.JsonNode>(detailRes.text)
            val dataNode = detailRoot.path("data")
            val detail = parseJson<AnimeDetail>(dataNode.toString())

            val slug = detail.slug ?: detail.id ?: animeId
            val title = detail.title?.userPreferred ?: detail.title?.english ?: detail.titleEnglish ?: "Unknown"
            val poster = detail.coverImage?.extraLarge ?: detail.coverImage?.large
            val totalEps = detail.episodes ?: detail.totalEpisodes ?: 0


            val serversUrl = "$chadUrl/servers?id=$slug&epNum=1"
            val serversRes = cfAppGetAnidap(
                serversUrl,
                headers = mapOf("Referer" to "$mainUrl/", "Accept" to "application/json")
            )

            val servers = if (serversRes.code == 200 && !serversRes.text.contains("bot_detected") && !serversRes.text.contains("\"error\"")) {
                try { parseJson<ServersResponse>(serversRes.text) } catch (e: Exception) { ServersResponse() }
            } else ServersResponse()

            val subProviders = servers.subProviders?.filter { it.id.isNotBlank() } ?: emptyList()
            val dubProviders = servers.dubProviders?.filter { it.id.isNotBlank() } ?: emptyList()


            if (totalEps <= 0) return null

            val subEpisodes = if (subProviders.isNotEmpty()) (1..totalEps).map { epNum ->
                val ids = subProviders.joinToString(",") { it.id }
                val tips = subProviders.joinToString(";;") { it.id + "=" + (it.tip ?: "") }
                newEpisode("$mainUrl|$slug|$epNum|sub|$ids|$tips") {
                    this.episode = epNum
                    this.name = "Episode $epNum"
                }
            } else emptyList()

            val dubEpisodes = if (dubProviders.isNotEmpty()) (1..totalEps).map { epNum ->
                val ids = dubProviders.joinToString(",") { it.id }
                val tips = dubProviders.joinToString(";;") { it.id + "=" + (it.tip ?: "") }
                newEpisode("$mainUrl|$slug|$epNum|dub|$ids|$tips") {
                    this.episode = epNum
                    this.name = "Episode $epNum"
                }
            } else emptyList()

            val tvType = when (detail.format) {
                "MOVIE" -> TvType.AnimeMovie
                "OVA", "ONA" -> TvType.OVA
                else -> TvType.Anime
            }

            return newAnimeLoadResponse(title, url, tvType) {
                this.posterUrl = poster
                if (subEpisodes.isNotEmpty()) addEpisodes(DubStatus.Subbed, subEpisodes)
                if (dubEpisodes.isNotEmpty()) addEpisodes(DubStatus.Dubbed, dubEpisodes)
            }
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val cleanData = data.removePrefix("$mainUrl/").removePrefix("$mainUrl|").trim()
        val parts = cleanData.split("|")
        if (parts.size < 5) {
            return false
        }
        val slug = parts[0]
        val epNum = parts[1]
        val type = parts[2]
        val providerIds = parts[3].split(",").filter { it.isNotBlank() }
        val tipsMap: Map<String, String> = if (parts[4].isNotBlank()) {
            parts[4].split(";;").mapNotNull { entry ->
                val eqIdx = entry.indexOf('=')
                if (eqIdx > 0) entry.substring(0, eqIdx) to entry.substring(eqIdx + 1)
                else null
            }.toMap()
        } else emptyMap()

        if (providerIds.isEmpty()) return false

        var found = false
        for (providerId in providerIds) {
            val tip = tipsMap[providerId]
            try {
                val sourcesUrl = "$chadUrl/sources?id=$slug&epNum=$epNum&type=$type&providerId=$providerId"
                val sourcesRes = cfAppGetAnidap(
                    sourcesUrl,
                    headers = mapOf("Referer" to "$mainUrl/", "Accept" to "application/json")
                )

                if (sourcesRes.code != 200 || sourcesRes.text.contains("bot_detected") || sourcesRes.text.contains("\"error\"")) {
                    continue
                }

                val sourcesData = try { parseJson<SourcesResponse>(sourcesRes.text) } catch (e: Exception) {
                    continue
                }

                val sources = sourcesData.sources ?: emptyList()
                val tracks = sourcesData.tracks ?: emptyList()
                val apiHeaders: Map<String, String> = sourcesData.headers ?: emptyMap()

                if (sources.isEmpty()) continue

                for (track in tracks) {
                    var trackUrl = track.url ?: continue
                    if (trackUrl.isBlank()) continue
                    trackUrl = trackUrl.replace("https:///", "https://").replace("http:///", "http://")
                    if (!trackUrl.startsWith("http")) {
                        trackUrl = "$chadHost/${trackUrl.removePrefix("/")}"
                    }
                    val label = track.label ?: track.lang ?: "Subtitle"
                    if (track.kind == "captions" || track.kind == "subtitles" || track.kind == "metadata") {
                        val subHeaders = when {
                            trackUrl.contains("lostproject.club") -> mapOf("Referer" to "https://megaplay.buzz/")
                            trackUrl.contains("megaplay.buzz") -> mapOf("Referer" to "https://megaplay.buzz/")
                            else -> apiHeaders
                        }
                        subtitleCallback.invoke(newSubtitleFile(label, trackUrl) {
                            this.headers = subHeaders
                        })
                    }
                }

                for (source in sources) {
                    val sourceUrl = source.url ?: continue
                    if (sourceUrl.isBlank()) continue
                    val sourceType = source.type ?: ""
                    val quality = source.quality ?: "auto"
                    val qualityInt = parseQuality(quality)
                    val label = buildLabel(providerId, quality)

                    val isM3u8 = sourceUrl.contains(".m3u8") ||
                        sourceType.contains("mpegurl", ignoreCase = true) ||
                        sourceType.contains("m3u8", ignoreCase = true)
                    val isMp4 = sourceUrl.contains(".mp4") || sourceUrl.contains(".webm") ||
                        sourceType.contains("mp4", ignoreCase = true) ||
                        sourceType.contains("webm", ignoreCase = true)

                    when {
                        isM3u8 -> {
                            callback.invoke(
                                newExtractorLink(label, label, sourceUrl, type = ExtractorLinkType.M3U8) {
                                    this.headers = apiHeaders
                                    this.quality = qualityInt
                                }
                            )
                            found = true
                        }
                        isMp4 -> {
                            callback.invoke(
                                newExtractorLink(label, label, sourceUrl, type = ExtractorLinkType.VIDEO) {
                                    this.headers = apiHeaders
                                    this.quality = qualityInt
                                }
                            )
                            found = true
                        }
                        else -> {
                            val referer = apiHeaders["Referer"] ?: apiHeaders["referer"] ?: "$mainUrl/"
                            val loaded = try {
                                loadExtractor(sourceUrl, referer, subtitleCallback, callback)
                            } catch (_: Exception) { false }
                            if (loaded) {
                                found = true
                            } else {
                                callback.invoke(
                                    newExtractorLink(label, label, sourceUrl, type = ExtractorLinkType.VIDEO) {
                                        this.headers = apiHeaders
                                        this.quality = qualityInt
                                    }
                                )
                                found = true
                            }
                        }
                    }
                }
            } catch (e: Exception) {
            }
        }

        return found
    }
}
