package com.laddu100

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.parseJson

// ─── PBT Token Management ──────────────────────────────────────────────────

object AnivexaApi {
    private const val BASE_URL = "https://anivexa.vercel.app"
    private var cachedToken: String? = null
    private var tokenExpiry: Long = 0

    /**
     * Get a valid PBT token, refreshing if expired.
     * Token is cached for 30 minutes.
     */
    suspend fun getPbtToken(): String {
        if (cachedToken != null && System.currentTimeMillis() < tokenExpiry) {
            return cachedToken!!
        }
        return refreshToken()
    }

    suspend fun refreshToken(): String {
        try {
            val response = app.get(
                "$BASE_URL/api/pbt",
                headers = mapOf("Accept" to "application/json")
            )
            val json = parseJson<PbtResponse>(response.text)
            cachedToken = json.t
            tokenExpiry = System.currentTimeMillis() + 30 * 60 * 1000 // 30 min cache
            return cachedToken ?: ""
        } catch (_: Exception) {
            return cachedToken ?: ""
        }
    }

    /**
     * Make a GET request to the Anivexa API with proper headers.
     */
    suspend fun apiGet(endpoint: String): String {
        val token = getPbtToken()
        return app.get(
            "$BASE_URL$endpoint",
            headers = mapOf(
                "X-KV-PBT" to token,
                "Accept" to "application/json, text/plain, */*",
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
                "Referer" to "$BASE_URL/"
            )
        ).text
    }

    fun getBaseUrl() = BASE_URL
}

// ─── Data Classes ───────────────────────────────────────────────────────────

// PBT Token response
@JsonIgnoreProperties(ignoreUnknown = true)
data class PbtResponse(
    @JsonProperty("t") val t: String? = null
)

// Standard list response from /api/v/trending, /api/v/popular, /api/v/search, etc.
@JsonIgnoreProperties(ignoreUnknown = true)
data class AnivexaListResponse(
    @JsonProperty("data") val data: List<AnivexaMedia>? = null
)

// Single anime info response from /api/v/anime/{id}
@JsonIgnoreProperties(ignoreUnknown = true)
data class AnivexaInfoResponse(
    @JsonProperty("data") val data: AnivexaMedia? = null
)

// Anime media object (used in list and info responses)
@JsonIgnoreProperties(ignoreUnknown = true)
data class AnivexaMedia(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("idMal") val idMal: Int? = null,
    @JsonProperty("title") val title: AnivexaTitle? = null,
    @JsonProperty("coverImage") val coverImage: AnivexaCoverImage? = null,
    @JsonProperty("bannerImage") val bannerImage: String? = null,
    @JsonProperty("format") val format: String? = null,
    @JsonProperty("status") val status: String? = null,
    @JsonProperty("episodes") val episodes: Int? = null,
    @JsonProperty("seasonYear") val seasonYear: Int? = null,
    @JsonProperty("season") val season: String? = null,
    @JsonProperty("averageScore") val averageScore: Int? = null,
    @JsonProperty("meanScore") val meanScore: Int? = null,
    @JsonProperty("genres") val genres: List<String>? = null,
    @JsonProperty("description") val description: String? = null,
    @JsonProperty("duration") val duration: Int? = null,
    @JsonProperty("isAdult") val isAdult: Boolean? = null,
    @JsonProperty("popularity") val popularity: Int? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AnivexaTitle(
    @JsonProperty("romaji") val romaji: String? = null,
    @JsonProperty("english") val english: String? = null,
    @JsonProperty("native") val native: String? = null,
    @JsonProperty("userPreferred") val userPreferred: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AnivexaCoverImage(
    @JsonProperty("extraLarge") val extraLarge: String? = null,
    @JsonProperty("large") val large: String? = null,
    @JsonProperty("medium") val medium: String? = null,
    @JsonProperty("color") val color: String? = null
)

// ─── Episodes Response ──────────────────────────────────────────────────────

@JsonIgnoreProperties(ignoreUnknown = true)
data class AnivexaEpisodesResponse(
    @JsonProperty("providers") val providers: Map<String, AnivexaProvider>? = null,
    @JsonProperty("mappings") val mappings: AnivexaMappings? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AnivexaProvider(
    @JsonProperty("meta") val meta: AnivexaProviderMeta? = null,
    @JsonProperty("episodes") val episodes: AnivexaEpisodeCategories? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AnivexaProviderMeta(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("totalEpisodes") val totalEpisodes: Int? = null,
    @JsonProperty("currentEpisode") val currentEpisode: Int? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AnivexaEpisodeCategories(
    @JsonProperty("sub") val sub: List<AnivexaEpisode>? = null,
    @JsonProperty("dub") val dub: List<AnivexaEpisode>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AnivexaEpisode(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("number") val number: Int? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("airDate") val airDate: String? = null,
    @JsonProperty("duration") val duration: Int? = null,
    @JsonProperty("audio") val audio: String? = null,
    @JsonProperty("description") val description: String? = null,
    @JsonProperty("filler") val filler: Boolean? = null,
    @JsonProperty("image") val image: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AnivexaMappings(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("malId") val malId: Int? = null,
    @JsonProperty("aniId") val aniId: Int? = null,
    @JsonProperty("anidbId") val anidbId: Int? = null,
    @JsonProperty("episodes") val episodes: Int? = null
)

// ─── Watch/Sources Response ─────────────────────────────────────────────────

// Watch response can have two formats:
// Format 1 (anikoto, animepahe, etc.): {"ssub": {"streams": [...], "subtitles": [...]}, "sdub": {...}}
// Format 2 (anidbapp): {"streams": [...], "anilistId": ..., "episode": ...}
@JsonIgnoreProperties(ignoreUnknown = true)
data class AnivexaWatchResponse(
    @JsonProperty("ssub") val ssub: AnivexaWatchData? = null,
    @JsonProperty("sdub") val sdub: AnivexaWatchData? = null,
    @JsonProperty("streams") val streams: List<AnivexaStream>? = null,
    @JsonProperty("subtitles") val subtitles: List<AnivexaSubtitle>? = null,
    @JsonProperty("anilistId") val anilistId: Int? = null,
    @JsonProperty("episode") val episode: Int? = null,
    @JsonProperty("audio") val audio: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AnivexaWatchData(
    @JsonProperty("streams") val streams: List<AnivexaStream>? = null,
    @JsonProperty("subtitles") val subtitles: List<AnivexaSubtitle>? = null,
    @JsonProperty("intro") val intro: AnivexaSkipTime? = null,
    @JsonProperty("outro") val outro: AnivexaSkipTime? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AnivexaStream(
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("type") val type: String? = null,
    @JsonProperty("referer") val referer: String? = null,
    @JsonProperty("server") val server: String? = null,
    @JsonProperty("priority") val priority: Int? = null,
    @JsonProperty("default") val default: Boolean? = null,
    @JsonProperty("embed") val embed: String? = null,
    @JsonProperty("audio") val audio: String? = null,
    @JsonProperty("isActive") val isActive: Boolean? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AnivexaSubtitle(
    @JsonProperty("file") val file: String? = null,
    @JsonProperty("label") val label: String? = null,
    @JsonProperty("kind") val kind: String? = null,
    @JsonProperty("language") val language: String? = null,
    @JsonProperty("format") val format: String? = null,
    @JsonProperty("default") val default: Boolean? = null,
    @JsonProperty("source") val source: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AnivexaSkipTime(
    @JsonProperty("start") val start: Double? = null,
    @JsonProperty("end") val end: Double? = null
)