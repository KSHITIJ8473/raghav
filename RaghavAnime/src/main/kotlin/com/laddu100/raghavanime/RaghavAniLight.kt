package com.laddu100.raghavanime

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDate
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class RaghavAniLight : MainAPI() {
    override var mainUrl = "https://anilight.live"
    override var name = "AniLight"
    override var lang = "en"
    override val hasMainPage = false
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    override suspend fun search(query: String): List<SearchResponse> {
        RaghavAniLightApi.refreshDomain()
        mainUrl = RaghavAniLightApi.site()
        return RaghavAniLightApi.search(query).mapNotNull { media ->
            val slug = media.slug?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val title = media.title?.display() ?: return@mapNotNull null
            newAnimeSearchResponse(title, "$mainUrl/anime/$slug", tvTypeOf(media.format)) {
                this.posterUrl = media.coverImage?.best()
                this.year = media.seasonYear
                this.otherName = media.title?.romaji
                media.averageScore?.let { score = Score.from100(it) }
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        RaghavAniLightApi.refreshDomain()
        mainUrl = RaghavAniLightApi.site()
        val slug = url.trimEnd('/').substringAfterLast("/").takeIf { it.isNotBlank() } ?: return null
        val anime = RaghavAniLightApi.animeDetail(slug) ?: return null
        val title = anime.title?.display() ?: return null
        val animeId = anime.id ?: return null

        val watch = RaghavAniLightApi.watch(slug)
        val entries = watch?.episodes.orEmpty()

        val hasDub = entries.any { it.dub == true }
        val hasSub = entries.any { it.sub == true }

        // the app hides the sub dub switcher on movie types, so dual audio movies stay regular anime
        val tvType = when {
            anime.format == "MOVIE" && hasDub -> TvType.Anime
            else -> tvTypeOf(anime.format)
        }

        val subEpisodes = if (hasSub) {
            entries.filter { it.sub == true }.map { it.toEpisode(animeId, "sub") }
        } else {
            emptyList()
        }
        val dubEpisodes = if (hasDub) {
            entries.filter { it.dub == true }.map { it.toEpisode(animeId, "dub") }
        } else {
            emptyList()
        }

        val showStatus = when (anime.status) {
            "RELEASING" -> ShowStatus.Ongoing
            "FINISHED" -> ShowStatus.Completed
            else -> null
        }

        val response = newAnimeLoadResponse(title, "$mainUrl/anime/$slug", tvType) {
            this.engName = anime.title?.english
            this.japName = anime.title?.romaji ?: anime.title?.native
            this.posterUrl = anime.coverImage?.best()
            this.backgroundPosterUrl = anime.bannerImage?.takeIf { it.isNotBlank() }
            this.plot = anime.description?.let(::stripHtml)
            this.tags = anime.genres.orEmpty()
            this.year = anime.seasonYear
            this.duration = anime.duration
            this.showStatus = showStatus
            anime.averageScore?.let { score = Score.from100(it) }
            if (subEpisodes.isNotEmpty()) addEpisodes(DubStatus.Subbed, subEpisodes)
            if (dubEpisodes.isNotEmpty()) addEpisodes(DubStatus.Dubbed, dubEpisodes)
        }
        return response
    }

    private fun tvTypeOf(format: String?): TvType = when (format) {
        "MOVIE" -> TvType.AnimeMovie
        "OVA", "ONA", "SPECIAL" -> TvType.OVA
        else -> TvType.Anime
    }

    private fun AniLightEpisode.toEpisode(animeId: Int, audio: String): Episode {
        val num = number ?: 1
        val data = AniLightEpisodeData(
            animeId = animeId,
            episode = num,
            audio = audio
        ).toJson()
        return newEpisode(data) {
            this.episode = num
            // blank names fall back to the plain number so the row never shows gaps
            this.name = title?.takeIf { it.isNotBlank() } ?: "Episode $num"
            this.posterUrl = img?.takeIf { it.startsWith("http") }
            this.description = buildString {
                if (isFiller == true) append("Filler episode")
                airedAt?.let {
                    if (isNotEmpty()) append(" | ")
                    append("Aired ${it.substringBefore("T").take(10)}")
                }
                description?.takeIf { it.isNotBlank() }?.let {
                    if (isNotEmpty()) append(" | ")
                    append(stripHtml(it))
                }
            }.takeIf { it.isNotBlank() }
            parseAirDate(airedAt)?.let { addDate(it) }
        }
    }

    private fun parseAirDate(raw: String?): Date? {
        if (raw.isNullOrBlank()) return null
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("UTC")
            sdf.parse(raw.substringBefore("T").take(10))
        } catch (e: Exception) {
            null
        }
    }

    private fun stripHtml(html: String): String =
        html.replace(Regex("<br\\s*/?>"), "\n")
            .replace(Regex("<[^>]+>"), "")
            .trim()

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val ref = try {
            parseJson<AniLightEpisodeData>(data)
        } catch (e: Exception) {
            Log.d("AniLight", "episode data parse: ${e.message}")
            return false
        }
        val animeId = ref.animeId ?: return false
        val episode = ref.episode ?: return false
        val language = if (ref.audio == "dub") "dub" else "sub"
        val audioLabel = if (language == "dub") "DUB" else "SUB"

        val seenSubs = mutableSetOf<String>()
        val seenLinks = mutableSetOf<String>()
        var emitted = false

        // the site's own library, one row per quality, direct mp4 files
        val own = RaghavAniLightApi.sources(animeId, episode, language, backup = false)
        own?.sources.orEmpty().forEach { entry ->
            val token = entry.tokens().firstOrNull() ?: return@forEach
            val streamUrl = RaghavAniLightApi.resolveStream(token) ?: return@forEach
            if (!seenLinks.add(streamUrl)) return@forEach
            val height = qualityHeight(entry.quality)
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = buildString {
                        append("AniLight ").append(audioLabel)
                        height?.let { append(" ${it}p") }
                    },
                    url = streamUrl,
                    type = ExtractorLinkType.VIDEO
                ) {
                    height?.let { quality = it }
                }
            )
            emitted = true
        }
        own?.tracks.orEmpty().let { emitSubtitles(it, seenSubs, subtitleCallback) }

        // the megaplay scraped mirrors, hls playlists behind the same balancer
        val backup = RaghavAniLightApi.sources(animeId, episode, language, backup = true)
        backup?.sources.orEmpty().forEach { entry ->
            val tokens = entry.tokens()
            val results = coroutineScope {
                tokens.mapIndexed { index, token ->
                    async(Dispatchers.IO) {
                        val streamUrl = RaghavAniLightApi.resolveStream(token) ?: return@async null
                        val master = RaghavAniLightApi.hlsMaster(streamUrl) ?: return@async null
                        index to master
                    }
                }
            }.mapNotNull { it.await() }

            results.sortedBy { it.first }.forEach { (_, master) ->
                val (url, maxHeight) = master
                if (!seenLinks.add(url)) return@forEach
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = buildString {
                            append("MegaPlay ").append(audioLabel)
                            maxHeight?.let { append(" ${it}p") } ?: append(" HLS")
                        },
                        url = url,
                        type = ExtractorLinkType.M3U8
                    ) {
                        maxHeight?.let { quality = it }
                    }
                )
                emitted = true
            }
        }
        backup?.tracks.orEmpty().let { emitSubtitles(it, seenSubs, subtitleCallback) }

        return emitted
    }

    // only caption rows are kept, sprite tracks cannot be played by the player
    private suspend fun emitSubtitles(
        tracks: List<AniLightCaption>,
        seen: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        tracks.forEach { track ->
            if (track.kind != null && track.kind != "captions") return@forEach
            val token = track.url?.takeIf { it.isNotBlank() } ?: return@forEach
            val rawLabel = track.label?.takeIf { it.isNotBlank() }
                ?: track.lang?.takeIf { it.isNotBlank() }
                ?: return@forEach
            val label = prettifySubtitleLabel(rawLabel)
            if (label.isBlank() || !seen.add(label.lowercase())) return@forEach
            try {
                subtitleCallback.invoke(
                    newSubtitleFile(label, RaghavAniLightApi.captionsUrl(token))
                )
            } catch (e: Exception) {
                Log.d("AniLight", "subtitle emit: ${e.message}")
            }
        }
    }

    // iso codes are expanded so the player list reads the same language on both rows
    private fun prettifySubtitleLabel(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return trimmed
        if (trimmed.any { it.isWhitespace() || it.isUpperCase() }) return trimmed
        val parts = trimmed.split("-")
        val base = when (parts.first().lowercase()) {
            "ar" -> "Arabic"
            "baq" -> "Basque"
            "cat" -> "Catalan"
            "cze" -> "Czech"
            "dan" -> "Danish"
            "de" -> "German"
            "dut" -> "Dutch"
            "en" -> "English"
            "es" -> "Spanish"
            "fil" -> "Filipino"
            "fin" -> "Finnish"
            "fr" -> "French"
            "glg" -> "Galician"
            "gre" -> "Greek"
            "heb" -> "Hebrew"
            "hrv" -> "Croatian"
            "hun" -> "Hungarian"
            "id" -> "Indonesian"
            "it" -> "Italian"
            "ja" -> "Japanese"
            "ko" -> "Korean"
            "may" -> "Malay"
            "nob" -> "Norwegian"
            "pol" -> "Polish"
            "pt" -> "Portuguese"
            "ro", "rum" -> "Romanian"
            "ru" -> "Russian"
            "swe" -> "Swedish"
            "th" -> "Thai"
            "tur" -> "Turkish"
            "ukr" -> "Ukrainian"
            "vi" -> "Vietnamese"
            "zh" -> "Chinese"
            else -> return trimmed
        }
        return when (parts.getOrNull(1)) {
            null -> base
            "signs" -> "$base (Signs)"
            "sdh" -> "$base (SDH)"
            else -> "$base (${parts.drop(1).joinToString("-")})"
        }
    }

    // quality labels arrive as pixel heights like 1080p or 1036p
    private fun qualityHeight(label: String?): Int? {
        val digits = label?.filter { it.isDigit() } ?: return null
        return digits.toIntOrNull()?.takeIf { it in 100..4320 }
    }
}

internal object RaghavAniLightApi {

    private const val TAG = "AniLight"

    private const val DEFAULT_SITE = "https://anilight.live"
    private const val DEFAULT_API = "https://api.anilight.live/api"

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/157.0.0.0 Safari/537.36"

    @Volatile
    private var site = DEFAULT_SITE

    @Volatile
    private var api = DEFAULT_API

    fun site(): String = site

    fun urlEncode(s: String): String = URLEncoder.encode(s, "UTF-8")

    // the api lives on an api. subdomain of the site so both follow one stored domain
    suspend fun refreshDomain() {
        val domain = FirebaseDomainHelper.getDomain("anilight")?.removeSuffix("/") ?: return
        if (domain == site) return
        val host = domain.substringAfter("://").substringBefore('/')
        site = domain
        api = "${domain.substringBefore("://")}://api.$host/api"
        // a session minted against the old host is worthless after a domain swap
        sessionToken = null
        sessionExpiresAt = 0L
    }

    private fun browserHeaders(): Map<String, String> = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "application/json, text/plain, */*",
        "Accept-Language" to "en-US,en;q=0.9",
        "Referer" to "$site/"
    )

    private suspend fun getJson(url: String, timeout: Long = 20L): String? {
        return try {
            val res = app.get(url, headers = browserHeaders(), timeout = timeout)
            if (res.isSuccessful) res.text else null
        } catch (e: Exception) {
            Log.d(TAG, "GET $url: ${e.message}")
            null
        }
    }

    // the site falls back to a direct verify token when the sources/session handshake is refused, every sources call carries the header the api demands
    @Volatile
    private var sessionToken: String? = null
    @Volatile
    private var sessionExpiresAt: Long = 0L

    suspend fun session(force: Boolean = false): String? {
        val now = System.currentTimeMillis()
        if (!force && sessionToken != null && now < sessionExpiresAt - 60_000L) {
            return sessionToken
        }
        return try {
            val res = app.post(
                "$api/sources/verify",
                headers = browserHeaders(),
                json = mapOf("token" to "direct"),
                timeout = 15L
            )
            if (!res.isSuccessful) return sessionToken
            val parsed = parseJson<AniLightSession>(res.text)
            val token = parsed.sessionToken?.takeIf { it.isNotBlank() } ?: return sessionToken
            sessionToken = token
            sessionExpiresAt = parsed.expiresAt ?: (now + 24 * 60 * 60 * 1000L)
            token
        } catch (e: Exception) {
            Log.d(TAG, "session: ${e.message}")
            sessionToken
        }
    }

    suspend fun sources(id: Int, episode: Int, language: String, backup: Boolean): AniLightSources? {
        val url = "$api/sources?id=$id&episode=$episode&language=$language" +
            if (backup) "&backup=true" else ""
        for (attempt in 0..1) {
            val token = session(attempt == 1) ?: return null
            try {
                val res = app.get(
                    url,
                    headers = browserHeaders() + mapOf("x-source-session" to token),
                    timeout = 20L
                )
                if (res.code == 401 || res.code == 403) {
                    if (attempt == 0) continue
                    return null
                }
                if (res.code == 404) return null
                if (!res.isSuccessful) return null
                return parseJson<AniLightSources>(res.text)
            } catch (e: Exception) {
                Log.d(TAG, "sources: ${e.message}")
                if (attempt == 1) return null
            }
        }
        return null
    }

    // every stream token resolves through the load balancer, the cdn link behind the redirect is what the player gets
    suspend fun resolveStream(token: String): String? {
        return try {
            val res = app.get(
                "$api/lb/e/$token",
                headers = browserHeaders(),
                allowRedirects = false,
                timeout = 15L
            )
            val location = res.headers["location"]?.trim().orEmpty()
            if (location.startsWith("http")) {
                location
            } else if (res.code in 300..399) {
                "$api/lb/e/$token"
            } else {
                null
            }
        } catch (e: Exception) {
            Log.d(TAG, "lb resolve: ${e.message}")
            null
        }
    }

    // backup mirrors sometimes answer forbidden for a title the scraper never captured, the playlist is fetched up front so dead mirrors are dropped
    suspend fun hlsMaster(url: String): Pair<String, Int?>? {
        return try {
            val res = app.get(url, headers = browserHeaders(), timeout = 20L)
            if (!res.isSuccessful) return null
            val body = res.text
            if (!body.startsWith("#EXTM3U")) return null
            val maxHeight = Regex("RESOLUTION=(\\d+)x(\\d+)")
                .findAll(body)
                .maxOfOrNull { it.groupValues[2].toIntOrNull() ?: 0 }
            url to maxHeight?.takeIf { it > 0 }
        } catch (e: Exception) {
            Log.d(TAG, "hls master: ${e.message}")
            null
        }
    }

    fun captionsUrl(token: String): String = "$api/proxy/captions?token=${urlEncode(token)}"

    suspend fun search(query: String): List<AniLightMedia> {
        val body = getJson("$api/search?q=${urlEncode(query)}&hideAdult=true") ?: return emptyList()
        return try {
            parseJson<List<AniLightMedia>>(body).filter { it.isAdult != true }
        } catch (e: Exception) {
            Log.d(TAG, "search parse: ${e.message}")
            emptyList()
        }
    }

    suspend fun animeDetail(slug: String): AniLightMedia? {
        val body = getJson("$api/anime/$slug") ?: return null
        return try {
            parseJson<AniLightMedia>(body)
        } catch (e: Exception) {
            Log.d(TAG, "detail parse: ${e.message}")
            null
        }
    }

    suspend fun watch(slug: String): AniLightWatch? {
        val body = getJson("$api/watch/$slug") ?: return null
        return try {
            parseJson<AniLightWatch>(body)
        } catch (e: Exception) {
            Log.d(TAG, "watch parse: ${e.message}")
            null
        }
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class AniLightTitles(
    val romaji: String? = null,
    val english: String? = null,
    val native: String? = null,
    val userPreferred: String? = null
) {
    fun display(): String? = english?.takeIf { it.isNotBlank() }
        ?: romaji?.takeIf { it.isNotBlank() }
        ?: userPreferred?.takeIf { it.isNotBlank() }
        ?: native?.takeIf { it.isNotBlank() }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class AniLightCover(
    val extraLarge: String? = null,
    val large: String? = null,
    val medium: String? = null
) {
    fun best(): String? = extraLarge?.takeIf { it.isNotBlank() }
        ?: large?.takeIf { it.isNotBlank() }
        ?: medium?.takeIf { it.isNotBlank() }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class AniLightMedia(
    val id: Int? = null,
    val slug: String? = null,
    val anilistId: Int? = null,
    val idMal: Int? = null,
    val title: AniLightTitles? = null,
    val coverImage: AniLightCover? = null,
    val bannerImage: String? = null,
    val description: String? = null,
    val format: String? = null,
    val status: String? = null,
    val duration: Int? = null,
    val genres: List<String>? = null,
    val averageScore: Double? = null,
    val isAdult: Boolean? = null,
    val seasonYear: Int? = null,
    val trailer: AniLightTrailer? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AniLightTrailer(
    val id: String? = null,
    val site: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AniLightWatch(
    val id: Int? = null,
    val episodes: List<AniLightEpisode>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AniLightEpisode(
    val number: Int? = null,
    val title: String? = null,
    val description: String? = null,
    val img: String? = null,
    val isFiller: Boolean? = null,
    val airedAt: String? = null,
    val sub: Boolean? = null,
    val dub: Boolean? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AniLightSources(
    val tracks: List<AniLightCaption>? = null,
    val sources: List<AniLightSourceEntry>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AniLightCaption(
    val url: String? = null,
    val kind: String? = null,
    val lang: String? = null,
    val label: String? = null
)

// the self hosted rows carry one token per quality entry while the backup row packs every mirror token into a single list
@JsonIgnoreProperties(ignoreUnknown = true)
data class AniLightSourceEntry(
    val url: Any? = null,
    val quality: String? = null
) {
    fun tokens(): List<String> = when (val u = url) {
        is String -> if (u.isBlank()) emptyList() else listOf(u)
        is List<*> -> u.filterIsInstance<String>().filter { it.isNotBlank() }
        else -> emptyList()
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class AniLightSession(
    val sessionToken: String? = null,
    val expiresAt: Long? = null
)

data class AniLightEpisodeData(
    val animeId: Int? = null,
    val episode: Int? = null,
    val audio: String? = null
)
