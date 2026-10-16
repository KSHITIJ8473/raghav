package com.laddu100.raghavanime

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.nicehttp.RequestBodyTypes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody

// the watch cookie only comes out of a real episode page, this title has
// been on the site since the start and is not going anywhere
private const val ANV_SESSION_PAGE = "https://anv.to/anime/one-piece-odmau-episode-1"

private fun anvHeaders(): Map<String, String> = mapOf(
    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/155.0.0.0 Safari/537.36",
    "Accept-Language" to "en-US,en;q=0.9"
)

private object AnvSession {

    // the server keeps it for a day, ask again a good while before that
    private const val TTL = 20L * 60 * 60 * 1000

    @Volatile
    private var cookie: String? = null

    @Volatile
    private var fetchedAt = 0L

    fun current(): String? = cookie

    suspend fun ensure(force: Boolean = false): String? {
        val known = cookie
        if (!force && known != null && System.currentTimeMillis() - fetchedAt < TTL) return known
        return try {
            val res = app.get(ANV_SESSION_PAGE, headers = anvHeaders())
            val found = Regex("shiro_watch=([^;]+)").find(res.headers["set-cookie"].orEmpty())
            if (found != null) {
                cookie = "shiro_watch=" + found.groupValues[1].trim()
                fetchedAt = System.currentTimeMillis()
            }
            cookie
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            cookie
        }
    }
}

class RaghavAnv : MainAPI() {
    override var mainUrl = "https://anv.to"
    override var name = "Anv"
    override val hasMainPage = false
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AnvTrack(
        val label: String? = null,
        val language: String? = null,
        val src: String? = null,
        val type: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AnvSource(
        val label: String? = null,
        val url: String? = null,
        val tracks: List<AnvTrack>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AnvVariant(
        val id: String? = null,
        val sources: List<AnvSource>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AnvEpisodeResponse(
        val status: String? = null,
        val variants: List<AnvVariant>? = null
    )

    // the api wants the mal slot present even when unknown
    private fun episodeBody(anilistId: Int, episode: Int): okhttp3.RequestBody {
        return mapOf<String, Any?>(
            "anilistId" to anilistId,
            "malId" to null,
            "episode" to episode
        ).toJson().toRequestBody(RequestBodyTypes.JSON.toMediaTypeOrNull())
    }

    private fun apiHeaders(cookie: String): Map<String, String> {
        return anvHeaders() + mapOf(
            "Accept" to "application/json",
            "Content-Type" to "application/json",
            "Origin" to mainUrl,
            "Referer" to "$mainUrl/",
            "Cookie" to cookie
        )
    }

    private suspend fun episode(anilistId: Int, episode: Int, cookie: String): AnvEpisodeResponse? {
        val body = episodeBody(anilistId, episode)
        val res = app.post("$mainUrl/api/episode", headers = apiHeaders(cookie), requestBody = body)
        if (res.code == 403) {
            // expired mid session, take a new cookie and try once more
            val fresh = AnvSession.ensure(force = true) ?: return null
            val retry = app.post("$mainUrl/api/episode", headers = apiHeaders(fresh), requestBody = body)
            return try {
                parseJson<AnvEpisodeResponse>(retry.text)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                null
            }
        }
        return try {
            parseJson<AnvEpisodeResponse>(res.text)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        }
    }

    suspend fun loadLinksByAnilistId(
        anilistId: Int,
        episode: Int,
        isDub: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val cookie = AnvSession.ensure() ?: return false
        val resp = episode(anilistId, episode, cookie) ?: return false
        if (resp.status != "ready") return false

        val wanted = if (isDub) "dub" else "sub"
        val variant = resp.variants.orEmpty().firstOrNull { it.id == wanted } ?: return false

        class Pending(val label: String, val raw: String, val url: String, val tracks: List<AnvTrack>)

        val pending = mutableListOf<Pending>()
        val seenUrls = HashMap<String, Int>()
        for (source in variant.sources.orEmpty()) {
            val raw = source.url ?: continue
            if (raw.isBlank()) continue
            val label = source.label?.trim() ?: continue
            val stream = if (raw.startsWith("http")) raw else "$mainUrl$raw"
            // pear and banana point at the same stream and the player drops a
            // link whose url repeats, the repeats carry a marker the cdn ignores
            val repeat = seenUrls[stream] ?: 0
            val url = if (repeat == 0) stream else stream + (if ('?' in stream) "&" else "?") + "f=$repeat"
            seenUrls[stream] = repeat + 1
            pending.add(Pending(label, stream, url, source.tracks.orEmpty()))
        }
        if (pending.isEmpty()) return false

        val reqHeaders = anvHeaders() + mapOf(
            "Cookie" to cookie,
            "Referer" to ANV_SESSION_PAGE
        )

        // one probe per real stream, pear and banana would only fetch the
        // same playlist twice
        val qualities = coroutineScope {
            pending.map { it.raw }.distinct().map { stream ->
                async { stream to masterQuality(stream, reqHeaders) }
            }.awaitAll().toMap()
        }

        val seenSubs = mutableSetOf<String>()
        var found = false
        for (p in pending) {
            callback(
                newExtractorLink(
                    "Anv",
                    "Anv ${if (isDub) "Dub" else "Sub"} - ${p.label}",
                    p.url,
                    ExtractorLinkType.M3U8
                ) {
                    this.quality = qualities[p.raw] ?: 0
                    this.headers = reqHeaders
                }
            )
            found = true
            for (track in p.tracks) {
                val src = track.src ?: continue
                if (src.isBlank()) continue
                val subUrl = if (src.startsWith("http")) src else "$mainUrl$src"
                if (seenSubs.add(subUrl)) {
                    subtitleCallback(
                        newSubtitleFile(trackName(track), subUrl) {
                            this.headers = reqHeaders
                        }
                    )
                }
            }
        }
        return found
    }

    private suspend fun masterQuality(url: String, headers: Map<String, String>): Int? {
        return try {
            val res = app.get(url, headers = headers)
            Regex("RESOLUTION=(\\d+)x(\\d+)")
                .find(res.text)
                ?.groupValues
                ?.getOrNull(2)
                ?.toIntOrNull()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        }
    }

    // the labels bury the region inside parentheses in half a dozen shapes,
    // the subtitle picker reads better as "Spanish (Spain)" than the raw form
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

    private fun trackName(track: AnvTrack): String {
        val raw = track.label?.trim() ?: return track.language ?: "English"
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
