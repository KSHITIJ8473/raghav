package com.laddu100.senshi

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
import com.lagradost.cloudstream3.addDubStatus
import com.lagradost.cloudstream3.addEpisodes
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
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.raghav.donation.DonationManager
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

class SenshiProvider : MainAPI() {
    override var mainUrl = "https://senshi.to"
    override var name = "Senshi"
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private val apiHeaders get() = mapOf(
        "User-Agent" to SenshiVhost.browserUa(),
        "Accept" to "application/json, text/plain, */*",
        "Referer" to "$mainUrl/"
    )

    private val postHeaders get() = mapOf(
        "User-Agent" to SenshiVhost.browserUa(),
        "Accept" to "application/json, text/plain, */*",
        "Content-Type" to "application/json",
        "Origin" to mainUrl,
        "Referer" to "$mainUrl/browse"
    )

    private val cdnHeaders get() = mapOf(
        "User-Agent" to SenshiVhost.browserUa(),
        "Accept" to "*/*",
        "Accept-Language" to "en-US,en;q=0.9",
        "Origin" to mainUrl,
        "Referer" to "$mainUrl/",
        "sec-fetch-dest" to "empty",
        "sec-fetch-mode" to "cors",
        "sec-fetch-site" to "cross-site"
    )

    private val streamHeaders get() = mapOf(
        "User-Agent" to SenshiVhost.browserUa(),
        "Accept" to "*/*",
        "Origin" to mainUrl,
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "latest" to "Latest Episodes",
        "recent" to "Recently Added",
        "trending" to "Trending",
        "upcoming" to "Upcoming",
        "all" to "All Anime"
    )

    private suspend fun getJson(url: String, timeout: Long = 20_000L): String? {
        return try {
            val res = cfGet(url, headers = apiHeaders, timeout = timeout)
            if (res.code == 200) res.text else null
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun postFilter(body: SenshiFilterBody, timeout: Long = 20_000L): SenshiFilterResponse? {
        return try {
            val res = cfPost("$mainUrl/anime/filter", body = body.toJson(), headers = postHeaders, timeout = timeout)
            if (res.code == 200 || res.code == 201) parseJson<SenshiFilterResponse>(res.text) else null
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        DonationManager.checkAndShow()
        mainUrl = FirebaseDomainHelper.getDomain("senshi") ?: mainUrl
        SenshiVhost.refreshDomain()
        return try {
            when (request.data) {
                "latest" -> {
                    val text = getJson("$mainUrl/episode-embeds/latest") ?: return emptyPage(request)
                    val resp = parseJson<List<SenshiLatestEmbed>>(text)
                    val seen = mutableSetOf<Int>()
                    val home = resp.asSequence()
                        .mapNotNull { it.anime }
                        .filter { it.id != null && seen.add(it.id!!) }
                        .mapNotNull { it.toSearchResponse() }
                        .toList()
                    newHomePageResponse(request.name, home, hasNext = false)
                }

                "recent" -> {
                    val text = getJson("$mainUrl/anime/recently-added") ?: return emptyPage(request)
                    val home = parseJson<List<SenshiAnime>>(text).mapNotNull { it.toSearchResponse() }
                    newHomePageResponse(request.name, home, hasNext = false)
                }

                "trending" -> {
                    val text = getJson("$mainUrl/anime/trending/day") ?: return emptyPage(request)
                    val home = parseJson<List<SenshiAnime>>(text).mapNotNull { it.toSearchResponse() }
                    newHomePageResponse(request.name, home, hasNext = false)
                }

                "upcoming" -> {
                    val text = getJson("$mainUrl/anime/upcoming") ?: return emptyPage(request)
                    val home = parseJson<List<SenshiAnime>>(text).mapNotNull { it.toSearchResponse() }
                    newHomePageResponse(request.name, home, hasNext = false)
                }

                "all" -> {
                    val resp = postFilter(SenshiFilterBody(page = page, limit = 40, sortBy = "recent"))
                        ?: return emptyPage(request)
                    val home = resp.data.mapNotNull { it.toSearchResponse() }
                    newHomePageResponse(request.name, home, hasNext = page * 40 < (resp.total ?: 0))
                }

                else -> emptyPage(request)
            }
        } catch (_: Exception) {
            emptyPage(request)
        }
    }

    private fun emptyPage(request: MainPageRequest): HomePageResponse =
        newHomePageResponse(request.name, emptyList(), hasNext = false)

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        mainUrl = FirebaseDomainHelper.getDomain("senshi") ?: mainUrl
        val resp = postFilter(SenshiFilterBody(searchTerm = query, page = 1, limit = 30)) ?: return emptyList()
        return resp.data.mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse? {
        mainUrl = FirebaseDomainHelper.getDomain("senshi") ?: mainUrl
        val publicId = url.substringBefore("?").substringAfterLast("/")
        if (publicId.isBlank()) {
            return null
        }

        val animeText = getJson("$mainUrl/anime/$publicId") ?: run {
            return null
        }
        val anime = try {
            parseJson<SenshiAnime>(animeText)
        } catch (_: Exception) {
            return null
        }
        val malId = anime.id ?: return null

        val episodesText = getJson("$mainUrl/episodes/$malId") ?: run {
            return null
        }
        val episodes = try {
            parseJson<List<SenshiEpisode>>(episodesText).filter { it.ep_id != null }
        } catch (_: Exception) {
            return null
        }
        val sorted = episodes.sortedBy { it.ep_id }

        var hasSub = (anime.sub_count ?: 0) > 0
        var hasDub = (anime.dub_count ?: 0) > 0
        if (!hasSub && !hasDub && sorted.isNotEmpty()) {
            hasSub = true
            probeEmbeds(malId, sorted.first().ep_id!!)?.let { statuses ->
                hasSub = statuses.any { it.isSub() }
                hasDub = statuses.any { it.isDub() }
                if (!hasSub && !hasDub) hasSub = true
            }
        }

        val subEpisodes = if (hasSub) sorted.map { it.toEpisode(malId, "sub") } else emptyList()
        val dubEpisodes = if (hasDub && sorted.isNotEmpty()) buildDubEpisodes(malId, sorted, anime.dub_count ?: 0) else emptyList()

        val displayTitle = anime.title ?: anime.title_english ?: "Anime $malId"

        return newAnimeLoadResponse(displayTitle, url, anime.tvType(hasSub && hasDub)) {
            this.posterUrl = anime.posterUrl(mainUrl)
            this.plot = anime.ani_description
            this.year = anime.ani_year
            this.tags = anime.genreList()
            this.duration = anime.durationMinutes()
            this.showStatus = anime.showStatus()
            this.score = anime.score?.let { Score.from10(it.toString()) }
            addMalId(malId)
            anime.anilist_id?.let { addAniListId(it) }
            if (subEpisodes.isNotEmpty()) addEpisodes(DubStatus.Subbed, subEpisodes)
            if (dubEpisodes.isNotEmpty()) addEpisodes(DubStatus.Dubbed, dubEpisodes)
        }
    }

    private suspend fun buildDubEpisodes(
        malId: Int,
        episodes: List<SenshiEpisode>,
        dubCount: Int
    ): List<Episode> {
        val base = if (dubCount in 1..episodes.size) episodes.take(dubCount) else episodes
        val trailing = if (dubCount in 1..episodes.size) episodes.drop(dubCount).takeLast(6) else emptyList()
        val extra = if (trailing.isEmpty()) emptyList() else coroutineScope {
            trailing.map { ep ->
                async {
                    val hasDub = probeEmbeds(malId, ep.ep_id!!)?.any { it.isDub() } ?: false
                    if (hasDub) ep else null
                }
            }.awaitAll().filterNotNull()
        }
        return (base + extra).sortedBy { it.ep_id }.map { it.toEpisode(malId, "dub") }
    }

    private suspend fun probeEmbeds(malId: Int, epId: Int): List<SenshiEmbed>? {
        val text = getJson("$mainUrl/episode-embeds/$malId/$epId") ?: return null
        return try {
            parseJson<List<SenshiEmbed>>(text)
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val epData = try {
            parseJson<SenshiEpData>(data)
        } catch (_: Exception) {
            return false
        }
        mainUrl = FirebaseDomainHelper.getDomain("senshi") ?: mainUrl
        SenshiVhost.refreshDomain()
        val wantDub = epData.type == "dub"
        val modeLabel = if (wantDub) "Dub" else "Sub"

        val embeds = probeEmbeds(epData.malId, epData.ep) ?: run {
            return false
        }
        if (embeds.isEmpty()) {
            return false
        }

        val matching = embeds.filter { if (wantDub) it.isDub() else it.isSub() }
            .ifEmpty { embeds }

        val sourceIds = matching.mapNotNull { it.remote_source_id }.distinct()
        if (sourceIds.isEmpty()) {
            return false
        }

        var found = false
        for (sourceId in sourceIds) {
            val sources = fetchVidcloud(sourceId) ?: continue
            for (source in sources) {
                val files = source.filesFor(wantDub)
                if (files.isEmpty()) {
                    continue
                }

                for (track in source.subtitlesFor(wantDub)) {
                    val url = track.vtt_url?.takeIf { it.isNotBlank() } ?: track.url ?: continue
                    if (url.isBlank()) continue
                    subtitleCallback.invoke(
                        newSubtitleFile(track.label ?: "English", url) {
                            this.headers = streamHeaders
                        }
                    )
                }

                for (file in files) {
                    val master = file.src ?: continue
                    if (emitStreamLinks(master, modeLabel, wantDub, callback)) {
                        found = true
                    }
                }
            }
        }
        return found
    }

    private suspend fun emitStreamLinks(
        master: String,
        modeLabel: String,
        wantDub: Boolean,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var masterText: String? = null
        for (attempt in 0..2) {
            if (attempt > 0) {
                delay(1200L)
            }
            masterText = try {
                val res = cfGet(master, headers = cdnHeaders, timeout = 20_000L)
                if (res.code == 200) res.text else null
            } catch (_: Exception) {
                null
            }
            if (masterText != null) break
        }

        var playlist = masterText
        if (playlist != null && SenshiCrypt.isEncrypted(playlist)) {
            playlist = SenshiCrypt.decrypt(playlist)
        }

        if (playlist != null && playlist.startsWith("#EXTM3U")) {
            val proxyBase = SenshiProxy.register(master, playlist, senshiHeaders(cdnHeaders, master))
            if (proxyBase != null) {
                val mode = if (wantDub) "dub" else "sub"
                val variants = parseVariantQualities(playlist)
                if (variants.isEmpty()) {
                    callback.invoke(
                        newExtractorLink(source = name, name = "Senshi $modeLabel", url = "$proxyBase/m/$mode/0/master.m3u8", type = ExtractorLinkType.M3U8)
                    )
                } else {
                    variants.forEach { q ->
                        val label = "Senshi $modeLabel ${q.first}"
                        callback.invoke(
                            newExtractorLink(source = name, name = label, url = "$proxyBase/m/$mode/${q.second}/master.m3u8", type = ExtractorLinkType.M3U8) {
                                this.quality = getQualityFromName(q.first)
                            }
                        )
                    }
                }
                return true
            }
        }

        callback.invoke(
            newExtractorLink(source = name, name = "Senshi $modeLabel", url = master, type = ExtractorLinkType.M3U8) {
                this.referer = "$mainUrl/"
                this.headers = streamHeaders
            }
        )
        return true
    }

    private val resolutionTag = Regex("""RESOLUTION=(\d+)x(\d+)""")

    private fun parseVariantQualities(master: String): List<Pair<String, Int>> {
        val lines = master.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        val qualities = mutableListOf<Pair<String, Int>>()
        var currentRes: String? = null
        var pending = false
        var ordinal = 0
        for (line in lines) {
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                pending = true
                currentRes = resolutionTag.find(line)?.groupValues?.get(2)?.toIntOrNull()
                    ?.takeIf { it > 0 }?.let { "${it}p" }
            } else if (pending && !line.startsWith("#")) {
                currentRes?.let { qualities.add(it to ordinal) }
                ordinal++
                pending = false
            }
        }
        return qualities.sortedByDescending { it.first.dropLast(1).toIntOrNull() ?: 0 }
    }

    private suspend fun fetchVidcloud(sourceId: Int): List<VidcloudSource>? {
        for (attempt in 0..1) {
            if (attempt > 0) {
                delay(1500L)
            }
            val result = try {
                SenshiVhost.fetchSources(sourceId)
            } catch (_: Exception) {
                null
            }
            if (result != null) return result
        }
        return null
    }

    private fun SenshiAnime.toSearchResponse(): SearchResponse? {
        val id = this.public_id ?: return null
        val title = this.title ?: this.title_english ?: return null
        return newAnimeSearchResponse(title, "$mainUrl/anime/$id", tvType()) {
            this.posterUrl = posterUrl(mainUrl)
            this.year = ani_year
            addDubStatus(
                dubExist = (dub_count ?: 0) > 0,
                subExist = (sub_count ?: 0) > 0 || (dub_count ?: 0) == 0
            )
        }
    }

    private fun SenshiEpisode.toEpisode(malId: Int, type: String): Episode {
        val num = ep_id ?: 1
        val baseTitle = ep_title?.takeIf { it.isNotBlank() } ?: "Episode $num"
        val title = when {
            ep_filler == true -> "$baseTitle (Filler)"
            ep_recap == true -> "$baseTitle (Recap)"
            else -> baseTitle
        }
        val payload = SenshiEpData(malId = malId, ep = num, type = type).toJson()
        return newEpisode(payload) {
            this.episode = num
            this.name = title
            this.posterUrl = ep_thumbnail
        }
    }

    private fun SenshiAnime.tvType(dualAudio: Boolean = false): TvType = when (type?.uppercase()) {
        "MOVIE" -> if (dualAudio) TvType.Anime else TvType.AnimeMovie
        "OVA", "ONA", "SPECIAL", "MUSIC" -> TvType.OVA
        else -> TvType.Anime
    }

    private fun SenshiAnime.posterUrl(baseUrl: String): String? {
        val pic = anime_picture ?: return null
        return if (pic.startsWith("http")) pic else "$baseUrl$pic"
    }

    private fun SenshiAnime.genreList(): List<String>? {
        val g = genres ?: return null
        return g.split(",").map { it.trim() }.filter { it.isNotBlank() }.ifEmpty { null }
    }

    private fun SenshiAnime.durationMinutes(): Int? =
        duration?.substringBefore(" ")?.toIntOrNull()

    private fun SenshiAnime.showStatus(): ShowStatus? = when (ani_status?.lowercase()) {
        "currently airing" -> ShowStatus.Ongoing
        "finished airing" -> ShowStatus.Completed
        else -> null
    }

    private fun SenshiEmbed.isDub(): Boolean = status?.lowercase() == "dub"

    private fun SenshiEmbed.isSub(): Boolean {
        val st = status?.lowercase() ?: return false
        return st == "sub" || st == "hardsub"
    }

    private fun VidcloudSource.filesFor(wantDub: Boolean): List<VidcloudFile> {
        val labeled = source.filter { !it.label.isNullOrBlank() }
        if (labeled.isEmpty()) return source
        val wanted = labeled.filter {
            val tag = it.label!!.lowercase()
            tag == "both" || tag == if (wantDub) "dub" else "sub"
        }
        return wanted
    }

    private fun VidcloudTrack.isDubTrack(): Boolean {
        val label = (label ?: html ?: "").lowercase()
        return label.contains("dub") || (url ?: "").contains("ai_dub")
    }

    private fun VidcloudTrack.trackLabel(): String? {
        (label ?: html)?.let { if (it.isNotBlank() && it.lowercase() != "chapter") return it }
        return null
    }

    private fun VidcloudSource.subtitlesFor(wantDub: Boolean): List<VidcloudTrack> {
        val usable = tracks.filter { it.trackLabel() != null }
        val dub = usable.filter { it.isDubTrack() }
        val sub = usable.filter { !it.isDubTrack() }
        return if (wantDub) dub.ifEmpty { sub } else sub.ifEmpty { dub }
    }

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    data class SenshiFilterBody(
        val searchTerm: String? = null,
        val page: Int = 1,
        val limit: Int = 30,
        val sortBy: String? = null
    )

    data class SenshiEpData(
        val malId: Int,
        val ep: Int,
        val type: String
    )
}
