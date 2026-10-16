package com.laddu100

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.nicehttp.RequestBodyTypes
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.atomic.AtomicReference

@JsonIgnoreProperties(ignoreUnknown = true)
data class AnvTitle(
    val english: String? = null,
    val romaji: String? = null,
    val native: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AnvCover(
    val large: String? = null,
    val extraLarge: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AnvAiring(val episode: Int? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AnvNamed(val name: String? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AnvStudios(val nodes: List<AnvNamed>? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AnvStreamEp(val title: String? = null, val thumbnail: String? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AnvPageInfo(val hasNextPage: Boolean? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AnvCatalogPage(
    val media: List<AnvMedia>? = null,
    val pageInfo: AnvPageInfo? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AnvCatalogData(
    @JsonProperty("Page") val page: AnvCatalogPage? = null,
    @JsonProperty("Media") val media: AnvMedia? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AnvCatalogResponse(val data: AnvCatalogData? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AnvMedia(
    val id: Int? = null,
    val slug: String? = null,
    val title: AnvTitle? = null,
    val synonyms: List<String>? = null,
    val description: String? = null,
    val coverImage: AnvCover? = null,
    val bannerImage: String? = null,
    val format: String? = null,
    val status: String? = null,
    val episodes: Int? = null,
    val nextAiringEpisode: AnvAiring? = null,
    val seasonYear: Int? = null,
    val season: String? = null,
    val genres: List<String>? = null,
    val averageScore: Int? = null,
    val duration: Int? = null,
    val studios: AnvStudios? = null,
    val idMal: Int? = null,
    val isAdult: Boolean? = null,
    val streamingEpisodes: List<AnvStreamEp>? = null,
    val recommendations: AnvRecommendations? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AnvRecMedia(
    val id: Int? = null,
    val slug: String? = null,
    val title: AnvTitle? = null,
    val coverImage: AnvCover? = null,
    val format: String? = null,
    val seasonYear: Int? = null,
    val isAdult: Boolean? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AnvRecNode(val mediaRecommendation: AnvRecMedia? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AnvRecommendations(val nodes: List<AnvRecNode>? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AnvSchedule(
    val id: Long? = null,
    val episode: Int? = null,
    val airingAt: Long? = null,
    val media: AnvMedia? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AnvRecentResponse(val schedules: List<AnvSchedule>? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AnvTrack(
    val id: String? = null,
    val src: String? = null,
    val type: String? = null,
    val label: String? = null,
    val language: String? = null,
    val default: Boolean? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AnvSource(
    val id: String? = null,
    val label: String? = null,
    val url: String? = null,
    val type: String? = null,
    val tracks: List<AnvTrack>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AnvVariant(
    val id: String? = null,
    val label: String? = null,
    val sources: List<AnvSource>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AnvEpisodeResponse(
    val status: String? = null,
    val variants: List<AnvVariant>? = null
)

internal object AnvSession {

    // the server keeps it for a day, refresh a good while before that
    private const val TTL = 20 * 60 * 60 * 1000L

    @Volatile
    private var cookie: String? = null

    @Volatile
    private var fetchedAt = 0L

    fun current(): String? = cookie

    // the episode api and every /stream url refuse requests without this
    // cookie, an episode page hands a fresh one out on the way in
    suspend fun ensure(pageUrl: String, force: Boolean = false): String? {
        val known = cookie
        if (!force && known != null && System.currentTimeMillis() - fetchedAt < TTL) return known
        return try {
            val res = app.get(pageUrl, headers = AnvApi.plainHeaders())
            val setCookie = res.headers["set-cookie"].orEmpty()
            val found = Regex("shiro_watch=([^;]+)").find(setCookie)
            if (found != null) {
                cookie = "shiro_watch=" + found.groupValues[1].trim()
                fetchedAt = System.currentTimeMillis()
            }
            cookie
        } catch (_: Exception) {
            cookie
        }
    }
}

internal object AnvApi {

    const val BASE = "https://anv.to"

    private val regionKeywords = listOf(
        "brazil" to "(Brazil)",
        "brasil" to "(Brazil)",
        "brazilian" to "(Brazil)",
        "latin america" to "(Latin America)",
        "latin american" to "(Latin America)",
        "espana" to "(Spain)",
        "spain" to "(Spain)",
        "castilian" to "(Spain)",
        "simplified" to "(Simplified)",
        "traditional" to "(Traditional)",
        "sdh" to "(SDH)"
    )

    private val codeRegions = mapOf(
        "pt-BR" to "(Brazil)",
        "es-419" to "(Latin America)",
        "es-ES" to "(Spain)",
        "zh-Hans" to "(Simplified)",
        "zh-Hant" to "(Traditional)"
    )

    fun plainHeaders(): Map<String, String> = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/155.0.0.0 Safari/537.36",
        "Accept-Language" to "en-US,en;q=0.9"
    )

    private fun jsonHeaders(): Map<String, String> = plainHeaders() + mapOf(
        "Accept" to "application/json",
        "Content-Type" to "application/json",
        "Origin" to BASE,
        "Referer" to "$BASE/"
    )

    private fun jsonBody(payload: Map<String, Any?>): okhttp3.RequestBody {
        return payload.toJson().toRequestBody(RequestBodyTypes.JSON.toMediaTypeOrNull())
    }

    suspend fun catalog(operation: String, variables: Map<String, Any?>): AnvCatalogResponse? {
        val body = jsonBody(mapOf("operation" to operation, "variables" to variables))
        val res = app.post("$BASE/api/catalog", headers = jsonHeaders(), requestBody = body)
        return try {
            parseJson<AnvCatalogResponse>(res.text)
        } catch (_: Exception) {
            null
        }
    }

    suspend fun detail(slug: String): AnvMedia? {
        return catalog("AnimeDetail", mapOf("slug" to slug))?.data?.media
    }

    suspend fun recentEpisodes(): AnvRecentResponse? {
        val res = app.get("$BASE/api/recent-episodes", headers = jsonHeaders())
        return try {
            parseJson<AnvRecentResponse>(res.text)
        } catch (_: Exception) {
            null
        }
    }

    suspend fun episode(mediaId: Int, malId: Int?, episode: Int, pageUrl: String): AnvEpisodeResponse? {
        val payload = mapOf<String, Any?>(
            "anilistId" to mediaId,
            "malId" to malId,
            "episode" to episode
        )
        val body = jsonBody(payload)
        val cookie = AnvSession.ensure(pageUrl) ?: return null
        val headers = jsonHeaders() + mapOf(
            "Cookie" to cookie,
            "Referer" to pageUrl
        )
        val res = app.post("$BASE/api/episode", headers = headers, requestBody = body)
        if (res.code == 403) {
            // expired mid session, take a new cookie and try once more
            val fresh = AnvSession.ensure(pageUrl, force = true) ?: return null
            val retryHeaders = jsonHeaders() + mapOf(
                "Cookie" to fresh,
                "Referer" to pageUrl
            )
            val retry = app.post("$BASE/api/episode", headers = retryHeaders, requestBody = body)
            return try {
                parseJson<AnvEpisodeResponse>(retry.text)
            } catch (_: Exception) {
                null
            }
        }
        return try {
            parseJson<AnvEpisodeResponse>(res.text)
        } catch (_: Exception) {
            null
        }
    }

    fun absolute(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return if (url.startsWith("http")) url else BASE + url
    }

    suspend fun masterQuality(url: String, pageUrl: String): Int? {
        val cookie = AnvSession.current() ?: return null
        return try {
            val res = app.get(
                url,
                headers = plainHeaders() + mapOf(
                    "Cookie" to cookie,
                    "Referer" to pageUrl
                )
            )
            Regex("RESOLUTION=(\\d+)x(\\d+)")
                .find(res.text)
                ?.groupValues
                ?.getOrNull(2)
                ?.toIntOrNull()
        } catch (_: Exception) {
            null
        }
    }

    // the labels bury the region inside parentheses in half a dozen shapes,
    // the subtitle picker reads better as "Spanish (Spain)" than the raw form
    fun trackName(track: AnvTrack): String {
        val raw = track.label?.trim() ?: return track.language ?: return ""
        val base = raw.substringBefore("(").replace(Regex("\\s+"), " ").trim()
        val paren = raw.substringAfter("(", "").substringBefore(")").lowercase()
        var region: String? = null
        for ((keyword, tag) in regionKeywords) {
            if (paren.contains(keyword)) {
                region = tag
                break
            }
        }
        if (region == null) region = codeRegions[track.language]
        return if (region != null) "$base $region" else base
    }
}
