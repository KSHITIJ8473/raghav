package com.csksy.anichan

import com.lagradost.api.Log
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDate
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean
import com.raghav.donation.DonationManager

class AniChanProvider : MainAPI() {

    override var mainUrl = AniChanApi.url()
    override var name = "AniChan"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    override val mainPage = mainPageOf(
        "trending" to "Trending Now",
        "airing" to "Airing This Season"
    )

    private data class EpisodeRef(
        val anilistId: Int,
        val ep: Int,
        val isDub: Boolean
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? {
        DonationManager.checkAndShow()
        AniChanApi.refreshDomain()
        mainUrl = AniChanApi.url()
        val items = when (request.data) {
            "trending" -> AniChanApi.trending(page)
            "airing" -> AniChanApi.airing(page)
            else -> emptyList()
        }
        return newHomePageResponse(
            request.name,
            items.mapNotNull { it.toSearchResponse() },
            hasNext = items.size >= 20
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        AniChanApi.refreshDomain()
        mainUrl = AniChanApi.url()
        return AniChanApi.suggest(query).mapNotNull { it.toSearchResponse() }
    }

    private fun CatalogItem.toSearchResponse(): SearchResponse? {
        val id = this.id ?: return null
        val displayTitle = title ?: titleRomaji ?: return null
        val tvType = if (format == "MOVIE") TvType.AnimeMovie else TvType.Anime
        return newAnimeSearchResponse(displayTitle, "$mainUrl/anime/$id", tvType) {
            this.posterUrl = poster
            this.year = startDate?.year
            this.otherName = titleRomaji
            score?.let { score = Score.from100(it.toDouble()) }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        AniChanApi.refreshDomain()
        mainUrl = AniChanApi.url()
        val anilistId = url.substringAfterLast("/").toIntOrNull() ?: return null
        val anime = AniChanApi.animeDetail(anilistId) ?: return null
        val title = anime.title ?: anime.titleRomaji ?: return null

        val info = AniChanApi.watchInfo(anilistId)
        val epMeta = anime.selfhost?.epMeta
        val epNumbers = collectEpisodeNumbers(anime, info)
        if (epNumbers.isEmpty()) return null

        // the watch endpoint is the dub source of truth but can lie on a cold cache
        val dubAvailable = info?.dubAvailable == true ||
            anime.selfhost?.cachedDub?.isNotEmpty() == true

        val subEps = epNumbers.map { it.toEpisode(anilistId, false, epMeta) }
        val dubEps = if (dubAvailable) epNumbers.map { it.toEpisode(anilistId, true, epMeta) } else emptyList()

        // dual audio movies are typed as anime so the sub/dub switcher stays reachable
        val tvType = when {
            anime.format == "MOVIE" && dubEps.isNotEmpty() -> TvType.Anime
            anime.format == "MOVIE" -> TvType.AnimeMovie
            else -> TvType.Anime
        }
        val showStatus = when (anime.status) {
            "RELEASING" -> ShowStatus.Ongoing
            "FINISHED" -> ShowStatus.Completed
            else -> null
        }

        return newAnimeLoadResponse(title, url, tvType) {
            this.posterUrl = anime.poster
            this.backgroundPosterUrl = anime.banner
            this.plot = anime.description?.let(::stripHtml)
            this.tags = anime.genres ?: emptyList()
            this.year = anime.startDate?.year
            this.duration = anime.duration
            this.showStatus = showStatus
            this.score = anime.score?.let { Score.from100(it.toDouble()) }
            addEpisodes(DubStatus.Subbed, subEps)
            if (dubEps.isNotEmpty()) addEpisodes(DubStatus.Dubbed, dubEps)
        }
    }

    // the player cannot load unaired episodes, only list the ones already aired
    private fun collectEpisodeNumbers(anime: CatalogItem, info: WatchInfo?): List<Int> {
        val today = todayUtc()
        val eps = sortedSetOf<Int>()
        val selfhost = anime.selfhost
        selfhost?.cachedEps?.forEach { if (it > 0) eps.add(it) }
        selfhost?.epMeta?.forEach { (key, meta) ->
            val num = key.toIntOrNull() ?: return@forEach
            if (num <= 0) return@forEach
            val air = meta.airDate
            if (air != null && air <= today) eps.add(num)
        }
        if (eps.isEmpty()) {
            val count = info?.episodes ?: anime.episodes ?: selfhost?.count ?: 0
            if (count > 0) eps.addAll(1..count)
        }
        return eps.toList()
    }

    private fun Int.toEpisode(
        anilistId: Int,
        isDub: Boolean,
        epMeta: Map<String, EpMeta>?
    ): Episode {
        val meta = epMeta?.get(toString())
        val ref = EpisodeRef(anilistId, this, isDub)
        return newEpisode(ref.toJson()) {
            this.episode = this@toEpisode
            this.name = meta?.title?.takeIf { it.isNotBlank() }
            this.description = meta?.overview?.takeIf { it.isNotBlank() }
            this.posterUrl = meta?.image?.takeIf { it.startsWith("http") }
            parseAirDate(meta?.airDate)?.let { addDate(it) }
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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val ref = try {
            parseJson<EpisodeRef>(data)
        } catch (e: Exception) {
            Log.e("AniChan", "bad episode data: ${e.message}")
            null
        } ?: return false
        val category = if (ref.isDub) "dub" else "sub"

        val raw = AniChanWebView.fetchServers(ref.anilistId, ref.ep, category) ?: return false
        val servers = try {
            parseJson<ServersEnvelope>(raw).servers ?: emptyList()
        } catch (e: Exception) {
            Log.e("AniChan", "bad servers payload: ${e.message}")
            emptyList()
        }
        if (servers.isEmpty()) return false

        val linkHeaders = mapOf(
            "User-Agent" to AniChanApi.USER_AGENT,
            "Referer" to "$mainUrl/"
        )
        val seenSubs = HashSet<String>()
        val found = AtomicBoolean(false)

        // direct streams go out first, embeds in parallel so a dead host cannot stall the list
        val embeds = ArrayList<Server>()
        for (server in servers) {
            if (server.type.equals("embed", true) && !server.embed.isNullOrBlank()) {
                embeds.add(server)
                continue
            }
            val stream = server.stream?.takeIf { it.startsWith("http") }
                ?: server.stream?.takeIf { it.startsWith("/") }?.let { "$mainUrl$it" }
                ?: continue
            callback.invoke(
                newExtractorLink(name, serverLabel(server), stream, type = ExtractorLinkType.M3U8) {
                    this.headers = linkHeaders
                }
            )
            found.set(true)

            for (sub in server.subtitles.orEmpty()) {
                val url = sub.url?.takeIf { it.startsWith("http") } ?: continue
                val lang = sub.lang ?: "English"
                if (seenSubs.add(lang)) {
                    subtitleCallback.invoke(SubtitleFile(lang, url))
                }
            }
        }

        if (embeds.isNotEmpty()) {
            coroutineScope {
                for (server in embeds) {
                    launch {
                        if (resolveEmbed(server, ref, category, subtitleCallback, callback)) {
                            found.set(true)
                        }
                    }
                }
            }
        }
        return found.get()
    }

    private suspend fun resolveEmbed(
        server: Server,
        ref: EpisodeRef,
        category: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val embed = server.embed ?: return false
        val label = serverLabel(server)

        if (embed.contains("vidhawk")) {
            val vidServer = Regex("[?&]server=([^&]+)").find(embed)?.groupValues?.get(1)
                ?: "kari"
            val embedAudio = Regex("/embed/ani/\\d+/\\d+/([a-z]+)/").find(embed)?.groupValues?.get(1)
                ?: category
            var any = false
            for (link in VidhawkResolver.resolveAll(ref.anilistId, ref.ep, embedAudio, vidServer)) {
                callback.invoke(
                    newExtractorLink(name, link.label, link.src, type = ExtractorLinkType.M3U8) {
                        this.headers = mapOf(
                            "User-Agent" to AniChanApi.USER_AGENT,
                            "Referer" to "$mainUrl/"
                        )
                    }
                )
                any = true
            }
            return any
        }

        if (server.name == "anihq" || embed.substringAfter("//").substringBefore("/").startsWith("voe.")) {
            return emitVoeMaster(embed, label, subtitleCallback, callback)
        }

        if (server.name == "kiwi") {
            val (file, kwikPage) = KiwiDownloadResolver.resolve(embed) ?: return false
            callback.invoke(
                newExtractorLink(
                    name, label, file,
                    if (file.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.headers = mapOf(
                        "User-Agent" to AniChanApi.USER_AGENT,
                        "Referer" to kwikPage
                    )
                }
            )
            return true
        }

        return loadExtractor(embed, "$mainUrl/", subtitleCallback, callback)
    }

    // voe hands out one master plus per quality variants, the master alone is enough
    private suspend fun emitVoeMaster(
        embed: String,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val captured = ArrayList<ExtractorLink>()
        loadExtractor(embed, "$mainUrl/", subtitleCallback) { captured.add(it) }
        val master = captured.firstOrNull { it.url.substringBefore('?').endsWith("master.m3u8") }
            ?: captured.firstOrNull { it.url.contains(".m3u8") }
            ?: return false
        callback.invoke(
            newExtractorLink(
                name, label, master.url,
                if (master.url.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.referer = master.referer
                this.headers = master.headers
            }
        )
        return true
    }

    private fun serverLabel(server: Server): String {
        val raw = (server.label ?: server.name ?: "AniChan")
            .replace("★", "")
            .replace(Regex("\\s*⧉\\s*\\(ads\\)"), "")
            .trim()
        val hardsub = server.subType.equals("hard", true)
        return if (hardsub) "$raw (Hardsub)" else raw
    }

    private fun todayUtc(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date())
    }

    private fun stripHtml(html: String): String =
        html.replace(Regex("<br\\s*/?>"), "\n")
            .replace(Regex("<[^>]+>"), "")
            .trim()
}
