package com.laddu100.kuroanime

import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDate
import com.lagradost.cloudstream3.addDubStatus
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.coroutines.cancellation.CancellationException

class KuroAnimeProvider : MainAPI() {

    override var mainUrl = KuroAnimeApi.MAIN_URL
    override var name = "KuroAnime"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)
    override var lang = "en"
    override val hasMainPage = true

    override val mainPage = mainPageOf(
        "latest-sub" to "Latest Episodes (Sub)",
        "latest-dub" to "Latest Episodes (Dub)",
        "trending" to "Trending Now",
        "top-rated" to "Top Rated",
        "favourites" to "All-Time Favourites",
        "season" to "Popular This Season",
        "upcoming" to "Upcoming Soon",
        "movies" to "Popular Movies",
    )

    companion object {
        private const val TAG = "KuroAnime"

        private val MIRURO_PROVIDERS = listOf("kiwi", "pewe", "moo", "bee", "hop", "bonk", "ally")

        private val KYREN_SERVERS = listOf("jett", "viper", "gekko", "kayo", "neon")
    }

    data class LinkData(
        val anilistId: Int,
        val malId: Int? = null,
        val ep: Int,
        val variant: String,
        val title: String,
        val year: Int? = null,
        val totalEps: Int? = null
    )

    class KuroAnimeException(message: String) : Exception(message)

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val (items, hasNext) = when (request.data) {
            "latest-sub", "latest-dub" -> {
                val lang = if (request.data == "latest-dub") "dub" else "sub"
                KuroAnimeApi.selfHostLatest(lang).mapNotNull { it.toSearchResponse() } to false
            }
            "trending" -> filterPage("sort=POPULARITY_DESC", page)
            "top-rated" -> filterPage("sort=SCORE_DESC", page)
            "favourites" -> filterPage("sort=FAVOURITES_DESC", page)
            "season" -> filterPage("season=${currentSeason()}&year=${currentYear()}&sort=POPULARITY_DESC", page)
            "upcoming" -> filterPage("startDate_greater=${upcomingCutoffIso()}&sort=POPULARITY_DESC", page)
            "movies" -> filterPage("format=MOVIE&sort=POPULARITY_DESC", page)
            else -> return null
        }
        return newHomePageResponse(request.name, items, hasNext = hasNext)
    }

    private suspend fun filterPage(params: String, page: Int): Pair<List<SearchResponse>, Boolean> {
        val envelope = KuroAnimeApi.filter(params, page)
            ?: return emptyList<SearchResponse>() to false
        val items = envelope.all.mapNotNull { it.toSearchResponse() }
        return items to (envelope.hasNextPage == true)
    }

    private fun MediaItem.toSearchResponse(): SearchResponse? {
        val mediaId = id ?: return null
        val subExist = (subCount ?: 0) > 0 || (airedCount ?: 0) > 0 || (episodes ?: 0) > 0
        val dubExist = hasDub == true || (dubCount ?: 0) > 0 || (uploadedDub ?: 0) > 0
        return newAnimeSearchResponse(displayTitle, "$mainUrl/anime/$mediaId") {
            this.posterUrl = coverImage?.best
            this.year = seasonYear ?: startDate?.year
            this.score = averageScore?.let { Score.from10((it / 10.0).toString()) }
            addDubStatus(dubExist = dubExist, subExist = subExist)
            this.otherName = format
        }
    }

    private fun currentSeason(): String {
        return when (java.util.Calendar.getInstance().get(java.util.Calendar.MONTH)) {
            in 0..1, 11 -> "WINTER"
            in 2..4 -> "SPRING"
            in 5..7 -> "SUMMER"
            else -> "FALL"
        }
    }

    private fun currentYear(): Int = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)

    private fun upcomingCutoffIso(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return KuroAnimeApi.search(query).mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val id = url.substringAfterLast("/").substringBefore("?").toIntOrNull() ?: return null
        val detail = KuroAnimeApi.localAnime(id) ?: KuroAnimeApi.kuroInfo(id)
            ?: throw KuroAnimeException("KuroAnime returned no details for id $id")

        data class Avail(
            val selfHost: List<SelfHostEpisode>,
            val pahe: AudioEpisodes?,
            val anidb: AudioEpisodes?,
            val miruro: MiruroEpisodes?
        )
        val avail = coroutineScope {
            val selfHostJob = async { runCatching { KuroAnimeApi.selfHost(id) }.getOrDefault(emptyList()) }
            val paheJob = async { runCatching { KuroAnimeApi.paheEpisodes(id) }.getOrNull() }
            val anidbJob = async { runCatching { KuroAnimeApi.anidbEpisodes(id) }.getOrNull() }
            val miruroJob = async { runCatching { KuroAnimeApi.miruroEpisodes(id) }.getOrNull() }
            Avail(selfHostJob.await(), paheJob.await(), anidbJob.await(), miruroJob.await())
        }
        val selfHostEps = avail.selfHost
        val pahe = avail.pahe
        val anidb = avail.anidb
        val miruro = avail.miruro

        val epTitles = mutableMapOf<Int, EpisodeMeta>()
        detail.episodesList.orEmpty().forEach { meta ->
            val n = meta.number ?: return@forEach
            epTitles[n] = meta
        }

        val selfSub = selfHostEps.filter { it.lang != "dub" }.mapNotNull { it.episode }.toSet()
        val selfDub = selfHostEps.filter { it.lang == "dub" }.mapNotNull { it.episode }.toSet()
        val paheSub = pahe?.sub.orEmpty().mapNotNull { it.number }.toSet()
        val paheDub = pahe?.dub.orEmpty().mapNotNull { it.number }.toSet()
        val anidbSub = anidb?.sub.orEmpty().mapNotNull { it.number }.toSet()
        val anidbDub = anidb?.dub.orEmpty().mapNotNull { it.number }.toSet()
        val miruroSub = mutableSetOf<Int>()
        val miruroDub = mutableSetOf<Int>()
        miruro?.providers?.values?.forEach { provider ->
            provider.episodes?.sub.orEmpty().forEach { miruroSub.add(it.number ?: return@forEach) }
            provider.episodes?.dub.orEmpty().forEach { miruroDub.add(it.number ?: return@forEach) }
        }

        val subAvail = selfSub + paheSub + anidbSub + miruroSub
        val dubAvail = selfDub + paheDub + anidbDub + miruroDub

        val candidates = listOfNotNull(
            detail.episodes,
            detail.airedCount,
            detail.nextAiringEpisode?.episode?.minus(1)?.takeIf { it > 0 },
            epTitles.keys.maxOrNull(),
            subAvail.maxOrNull(),
            dubAvail.maxOrNull()
        )
        val maxKnown = candidates.maxOrNull() ?: 0
        val isSingle = detail.format?.uppercase() == "MOVIE" || maxKnown <= 1
        val epCount = maxOf(1, if (isSingle) 1 else maxKnown)

        val aired = detail.airedCount
        val subLimit = when {
            subAvail.isNotEmpty() -> 0
            (detail.subCount ?: 0) > 0 -> maxOf(aired ?: 0, detail.subCount ?: 0)
            aired != null -> aired
            else -> if (maxKnown > 0) maxKnown else 0
        }
        val dubLimit = when {
            dubAvail.isNotEmpty() -> 0
            (detail.uploadedDub ?: 0) > 0 -> detail.uploadedDub ?: 0
            (detail.dubCount ?: 0) > 0 -> detail.dubCount ?: 0
            detail.hasDub == true -> maxOf(aired ?: 0, detail.episodes ?: 0)
            else -> 0
        }

        val subEps = mutableListOf<Episode>()
        val dubEps = mutableListOf<Episode>()
        for (n in 1..epCount) {
            val meta = epTitles[n]
            val title = meta?.title
                ?: if (isSingle) "Complete Movie" else "Episode $n"
            val filler = buildString {
                if (meta?.filler == true) append("[Filler]")
                if (meta?.recap == true) {
                    if (isNotEmpty()) append(" ")
                    append("[Recap]")
                }
            }.trim()
            val builder: (Episode).() -> Unit = {
                this.name = title
                this.episode = n
                this.posterUrl = meta?.thumbnail
                this.description = when {
                    meta?.description != null && filler.isNotEmpty() -> "${meta.description}\n$filler"
                    meta?.description != null -> meta.description
                    filler.isNotEmpty() -> filler
                    else -> null
                }
                parseAirDate(meta?.airDate)?.let { this.addDate(it) }
            }
            val hasSub = n in subAvail || (subAvail.isEmpty() && n <= subLimit)
            val hasDub = n in dubAvail || (dubAvail.isEmpty() && n <= dubLimit)
            val base = LinkData(
                anilistId = detail.id ?: id,
                malId = detail.idMal,
                ep = n,
                variant = "sub",
                title = detail.displayTitle,
                year = detail.seasonYear ?: detail.startDate?.year,
                totalEps = detail.episodes
            )
            if (hasSub) subEps.add(newEpisode(base.toJson(), builder))
            if (hasDub) dubEps.add(newEpisode(base.copy(variant = "dub").toJson(), builder))
        }
        if (subEps.isEmpty() && dubEps.isEmpty()) return null

        val tvType = when (detail.format?.uppercase()) {
            "OVA", "ONA", "SPECIAL", "MUSIC" -> TvType.OVA
            else -> TvType.Anime
        }

        return newAnimeLoadResponse(detail.displayTitle, url, tvType) {
            this.posterUrl = detail.coverImage?.best
            this.backgroundPosterUrl = detail.bannerImage
            this.plot = detail.description?.let { cleanHtml(it) }
            this.tags = detail.genres.orEmpty()
            this.year = detail.seasonYear ?: detail.startDate?.year
            this.showStatus = when (detail.status?.uppercase()) {
                "RELEASING" -> ShowStatus.Ongoing
                "FINISHED", "CANCELLED", "NOT_YET_RELEASED" -> ShowStatus.Completed
                else -> null
            }
            this.score = detail.averageScore?.let { Score.from10((it / 10.0).toString()) }
            this.duration = detail.duration
            addMalId(detail.idMal?.takeIf { it > 0 })
            addAniListId((detail.id ?: id).takeIf { it > 0 })
            if (subEps.isNotEmpty()) addEpisodes(DubStatus.Subbed, subEps)
            if (dubEps.isNotEmpty()) addEpisodes(DubStatus.Dubbed, dubEps)
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

    private fun cleanHtml(s: String): String =
        s.replace(Regex("<br\\s*/?>"), "\n")
            .replace(Regex("<[^>]+>"), "")
            .trim()

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val link = try {
            parseJson<LinkData>(data)
        } catch (e: Exception) {
            return false
        }
        val audio = if (link.variant == "dub") "dub" else "sub"
        val suffix = audio.uppercase()
        val seenUrls = HashSet<String>()
        val seenSubs = HashSet<String>()

        coroutineScope {
            val jobs: List<suspend () -> Unit> = listOf(
                { emitKuroanime(link, audio, seenUrls, seenSubs, subtitleCallback, callback) },
                { emitAllAnime(link, audio, suffix, seenUrls, seenSubs, subtitleCallback, callback) },
                { emitMegaPlay(link, audio, suffix, seenUrls, seenSubs, subtitleCallback, callback) },
                { emitPahe(link, audio, suffix, seenUrls, callback) },
                { emitAnidb(link, audio, suffix, seenUrls, callback) },
                { emitKyren(link, audio, suffix, seenUrls, seenSubs, subtitleCallback, callback) },
                { emitMiruro(link, audio, suffix, seenUrls, seenSubs, subtitleCallback, callback) },
                { emitFlix(link, audio, suffix, seenUrls, seenSubs, subtitleCallback, callback) }
            )
            jobs.amap { job ->
                try {
                    job()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.d(
                        TAG,
                        "source failed: ${e.javaClass.simpleName}: ${(e.message ?: "").take(120)}"
                    )
                }
            }
        }
        return true
    }

    private suspend fun emitKuroanime(
        link: LinkData,
        audio: String,
        seenUrls: HashSet<String>,
        seenSubs: HashSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val eps = KuroAnimeApi.selfHost(link.anilistId)
        val entry = eps.firstOrNull { it.episode == link.ep && it.lang == audio } ?: return
        val url = entry.url ?: return
        if (!Regex("""\.(m3u8|mp4|webm)(\?|$)""").containsMatchIn(url)) return
        val bunny = Regex("""^https://[^/]*bunny[^/]*(/[^?#]+)""", RegexOption.IGNORE_CASE)
            .find(url)?.groupValues?.get(1) ?: return
        val label = "Kuroanime - $audio"
        val headers = KuroAnimeApi.cookieHeaders()
        if (headers.isEmpty()) return

        for (attempt in 1..4) {
            val signed = KuroAnimeApi.streamSign(bunny) ?: continue
            if (Regex("""\.(mp4|webm)(\?|$)""").containsMatchIn(signed)) {
                if (seenUrls.add(signed)) {
                    callback.invoke(
                        newExtractorLink(label, label, signed, type = ExtractorLinkType.VIDEO) {
                            this.headers = headers
                        }
                    )
                }
                return
            }
            val playable = try {
                val resp = app.get(signed, headers = headers)
                resp.isSuccessful && resp.text.startsWith("#EXTM3U")
            } catch (e: Exception) {
                false
            }
            if (!playable) continue
            if (seenUrls.add(signed)) {
                try {
                    generateM3u8(
                        label,
                        signed,
                        mainUrl,
                        headers = headers
                    ).forEach(callback)
                } catch (e: Exception) {
                    callback.invoke(
                        newExtractorLink(label, "$label - auto", signed, type = ExtractorLinkType.M3U8) {
                            this.headers = headers
                        }
                    )
                }
            }
            if (audio == "sub") {
                signSubtitle(bunny, headers, seenSubs, subtitleCallback)
            }
            return
        }
    }

    private suspend fun signSubtitle(
        bunnyPath: String,
        headers: Map<String, String>,
        seenSubs: HashSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        val subPath = bunnyPath.substringBeforeLast('/') + "/subs/eng.vtt"
        for (attempt in 1..3) {
            val signedSub = KuroAnimeApi.streamSign(subPath) ?: continue
            val valid = try {
                val resp = app.get(signedSub, headers = headers)
                resp.isSuccessful && resp.text.startsWith("WEBVTT")
            } catch (e: Exception) {
                false
            }
            if (!valid) continue
            if (seenSubs.add(signedSub)) {
                subtitleCallback(
                    newSubtitleFile("English", signedSub) {
                        this.headers = headers
                    }
                )
            }
            return
        }
    }

    private suspend fun emitAllAnime(
        link: LinkData,
        audio: String,
        suffix: String,
        seenUrls: HashSet<String>,
        seenSubs: HashSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val res = KuroAnimeApi.allAnimeExtract(link.anilistId, link.ep, audio) ?: return
        if (res.ok == false) return
        val stream = res.m3u8 ?: res.m3u8Proxied ?: return
        emitHls("AllAnime - $suffix", stream, emptyMap(), seenUrls, callback)
        res.tracks.orEmpty().forEach { track ->
            val u = track.bestUrl?.takeIf { it.startsWith("http") } ?: return@forEach
            if (track.kind != null && track.kind != "captions" && track.kind != "subtitles") return@forEach
            if (!seenSubs.add(u)) return@forEach
            subtitleCallback(newSubtitleFile(track.label ?: track.lang ?: "Subtitle", u) {})
        }
    }

    private suspend fun emitMegaPlay(
        link: LinkData,
        audio: String,
        suffix: String,
        seenUrls: HashSet<String>,
        seenSubs: HashSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val res = KuroAnimeApi.megaPlayExtract(link.anilistId, link.malId, link.ep, audio) ?: return
        if (res.ok != true) return
        val stream = res.m3u8Proxied ?: res.m3u8 ?: return
        val headers = mapOf("Referer" to "${KuroAnimeApi.MAIN_URL}/")
        emitHls("MegaPlay - $suffix", stream, headers, seenUrls, callback)
        res.tracks.orEmpty().forEach { track ->
            val u = track.bestUrl?.takeIf { it.startsWith("http") } ?: return@forEach
            if (track.kind != null && track.kind != "captions" && track.kind != "subtitles") return@forEach
            if (!seenSubs.add(u)) return@forEach
            subtitleCallback(
                newSubtitleFile(track.label ?: track.lang ?: "Subtitle", u) {
                    this.headers = headers
                }
            )
        }
    }

    private suspend fun emitPahe(
        link: LinkData,
        audio: String,
        suffix: String,
        seenUrls: HashSet<String>,
        callback: (ExtractorLink) -> Unit
    ) {
        val eps = KuroAnimeApi.paheEpisodes(link.anilistId) ?: return
        val list = if (audio == "dub") eps.dub else eps.sub
        val ep = list.orEmpty().firstOrNull { it.number == link.ep } ?: return
        val watchId = ep.id ?: return
        val streams = KuroAnimeApi.paheWatch(watchId)
        emitStreamList("Pahe - $suffix", streams, seenUrls, callback)
    }

    private suspend fun emitAnidb(
        link: LinkData,
        audio: String,
        suffix: String,
        seenUrls: HashSet<String>,
        callback: (ExtractorLink) -> Unit
    ) {
        val eps = KuroAnimeApi.anidbEpisodes(link.anilistId) ?: return
        val list = if (audio == "dub") eps.dub else eps.sub
        val ep = list.orEmpty().firstOrNull { it.number == link.ep } ?: return
        val watchId = ep.id ?: return
        val streams = KuroAnimeApi.anidbWatch(watchId)
        emitStreamList("AniDB HardSub - $suffix", streams, seenUrls, callback)
    }

    private suspend fun emitStreamList(
        label: String,
        streams: List<StreamEntry>,
        seenUrls: HashSet<String>,
        callback: (ExtractorLink) -> Unit
    ) {
        streams.filter { it.type == "hls" && it.isActive != false && it.url != null }
            .sortedByDescending { it.priority ?: 0 }
            .forEach { stream ->
                val u = stream.url ?: return@forEach
                emitHls(label, u, emptyMap(), seenUrls, callback)
            }
    }

    private suspend fun emitKyren(
        link: LinkData,
        audio: String,
        suffix: String,
        seenUrls: HashSet<String>,
        seenSubs: HashSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        for (server in KYREN_SERVERS) {
            val res = KuroAnimeApi.kyrenStream(
                link.anilistId, link.ep, audio, link.title, server, link.year, link.totalEps
            ) ?: continue
            if (res.ok != true) continue
            res.sources.orEmpty().forEach { src ->
                val u = src.url?.takeIf { it.startsWith("http") } ?: return@forEach
                val label = "Kyren ${src.provider ?: server} - $suffix"
                emitHls(label, u, emptyMap(), seenUrls, callback)
            }
            res.subtitles.orEmpty().forEach { sub ->
                val u = sub.url?.takeIf { it.startsWith("http") } ?: return@forEach
                if (!seenSubs.add(u)) return@forEach
                subtitleCallback(newSubtitleFile(sub.label ?: sub.lang ?: "Subtitle", u) {})
            }
            if (res.sources.orEmpty().any { it.url != null }) return
        }
    }

    private suspend fun emitMiruro(
        link: LinkData,
        audio: String,
        suffix: String,
        seenUrls: HashSet<String>,
        seenSubs: HashSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val eps = KuroAnimeApi.miruroEpisodes(link.anilistId) ?: return
        val providers = eps.providers ?: return
        for (key in MIRURO_PROVIDERS) {
            val provider = providers[key] ?: continue
            val list = if (audio == "dub") provider.episodes?.dub else provider.episodes?.sub
            val ep = list.orEmpty().firstOrNull { it.number == link.ep } ?: continue
            val watchId = ep.id ?: continue
            val streams = KuroAnimeApi.miruroWatch(watchId)
            val label = "Miruro ${key.replaceFirstChar { it.uppercase() }} - $suffix"
            streams.filter { it.type == "hls" && it.url != null }.forEach { stream ->
                val u = stream.url ?: return@forEach
                emitHls(label, u, emptyMap(), seenUrls, callback)
                stream.subtitles.orEmpty().forEach { track ->
                    val su = track.bestUrl?.takeIf { it.startsWith("http") } ?: return@forEach
                    if (!seenSubs.add(su)) return@forEach
                    subtitleCallback(newSubtitleFile(track.label ?: track.lang ?: "Subtitle", su) {})
                }
            }
            if (streams.any { it.type == "hls" && it.url != null }) return
        }
    }

    private suspend fun emitFlix(
        link: LinkData,
        audio: String,
        suffix: String,
        seenUrls: HashSet<String>,
        seenSubs: HashSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val page = KuroAnimeApi.flixPlayerPage(link.anilistId, link.ep, audio) ?: return
        val embed = FlixResolver.embedUrlFrom(page) ?: return
        val playerUrl =
            "${KuroAnimeApi.MAIN_URL}/api/media/flix/player/anilist/${link.anilistId}/${link.ep}?audio=$audio"
        val res = FlixResolver.resolve(embed, playerUrl) ?: return
        val headers = mapOf("Referer" to "${KuroAnimeApi.FLIX_EMBED_BASE}/")
        if (seenUrls.add(res.m3u8)) {
            callback.invoke(
                newExtractorLink("Reanime - $suffix", "Reanime - $suffix", res.m3u8, type = ExtractorLinkType.M3U8) {
                    this.headers = headers
                }
            )
        }
        res.subtitles.forEach { sub ->
            val u = sub.url.takeIf { it.startsWith("http") } ?: return@forEach
            if (!seenSubs.add(u)) return@forEach
            val ext = sub.format?.uppercase()
            val name = if (ext != null) "${sub.language ?: "Subtitle"} ($ext)" else (sub.language ?: "Subtitle")
            subtitleCallback(newSubtitleFile(name, u) {})
        }
    }

    private suspend fun emitHls(
        label: String,
        url: String,
        headers: Map<String, String>,
        seenUrls: HashSet<String>,
        callback: (ExtractorLink) -> Unit
    ) {
        if (url.isBlank() || !url.startsWith("http")) return
        if (!seenUrls.add(url)) return
        try {
            generateM3u8(
                label,
                url,
                headers["Referer"] ?: mainUrl,
                headers = headers
            ).forEach(callback)
        } catch (e: Exception) {
            callback.invoke(
                newExtractorLink(label, "$label - auto", url, type = ExtractorLinkType.M3U8) {
                    if (headers.isNotEmpty()) this.headers = headers
                }
            )
        }
    }
}
