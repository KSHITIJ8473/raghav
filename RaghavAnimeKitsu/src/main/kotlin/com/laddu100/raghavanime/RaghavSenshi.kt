package com.laddu100.raghavanime

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.lagradost.api.Log
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDubStatus
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
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

class RaghavSenshi : MainAPI() {
    override var mainUrl = "https://senshi.to"
    override var name = "Senshi"
    override var lang = "en"

    private val TAG = "Senshi"

    private val vidcloudApi = "https://s.vidcloud.se/_v1/sources?id="

    private val ua =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    private val apiHeaders = mapOf(
        "User-Agent" to ua,
        "Accept" to "application/json, text/plain, */*",
        "Referer" to "$mainUrl/"
    )

    private val postHeaders = mapOf(
        "User-Agent" to ua,
        "Accept" to "application/json, text/plain, */*",
        "Content-Type" to "application/json",
        "Origin" to mainUrl,
        "Referer" to "$mainUrl/browse"
    )

    // mirrors the header set the site player sends on cross-origin XHRs to the
    // stream cdn, the waf there rejects plain requests without them
    private val cdnHeaders = mapOf(
        "User-Agent" to ua,
        "Accept" to "*/*",
        "Accept-Language" to "en-US,en;q=0.9",
        "Origin" to mainUrl,
        "Referer" to "$mainUrl/",
        "sec-ch-ua" to "\"Google Chrome\";v=\"131\", \"Chromium\";v=\"131\", \"Not_A Brand\";v=\"24\"",
        "sec-ch-ua-mobile" to "?0",
        "sec-ch-ua-platform" to "\"Windows\"",
        "sec-fetch-dest" to "empty",
        "sec-fetch-mode" to "cors",
        "sec-fetch-site" to "cross-site"
    )

    private val streamHeaders = mapOf(
        "User-Agent" to ua,
        "Accept" to "*/*",
        "Origin" to mainUrl,
        "Referer" to "$mainUrl/"
    )

    private suspend fun getJson(url: String, timeout: Long = 20_000L): String? {
        return try {
            val res = cfGet(url, headers = apiHeaders, timeout = timeout)
            if (res.code == 200) res.text else null
        } catch (e: Exception) {
            Log.d(TAG, "GET $url failed: ${e.message}")
            null
        }
    }

    private suspend fun postFilter(body: SenshiFilterBody, timeout: Long = 20_000L): SenshiFilterResponse? {
        return try {
            val res = cfPost("$mainUrl/anime/filter", body = body.toJson(), headers = postHeaders, timeout = timeout)
            if (res.code == 200 || res.code == 201) parseJson<SenshiFilterResponse>(res.text) else null
        } catch (e: Exception) {
            Log.d(TAG, "filter request failed: ${e.message}")
            null
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val resp = postFilter(SenshiFilterBody(searchTerm = query, page = 1, limit = 30)) ?: return emptyList()
        return resp.data.mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val publicId = url.substringBefore("?").substringAfterLast("/")
        if (publicId.isBlank()) {
            Log.e(TAG, "load: no id in $url")
            return null
        }

        val animeText = getJson("$mainUrl/anime/$publicId") ?: run {
            Log.e(TAG, "load: anime request failed for $publicId")
            return null
        }
        val anime = try {
            parseJson<SenshiAnime>(animeText)
        } catch (e: Exception) {
            Log.e(TAG, "load: anime parse failed: ${e.message}")
            return null
        }
        val malId = anime.id ?: return null

        val episodesText = getJson("$mainUrl/episodes/$malId") ?: run {
            Log.e(TAG, "load: episodes request failed for malId=$malId")
            return null
        }
        val episodes = try {
            parseJson<List<SenshiEpisode>>(episodesText).filter { it.ep_id != null }
        } catch (e: Exception) {
            Log.e(TAG, "load: episodes parse failed: ${e.message}")
            return null
        }
        val sorted = episodes.sortedBy { it.ep_id }

        var hasSub = (anime.sub_count ?: 0) > 0
        var hasDub = (anime.dub_count ?: 0) > 0
        if (!hasSub && !hasDub && sorted.isNotEmpty()) {
            // counts can be stale on freshly uploaded entries, fall back to the
            // first episode's embed list
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

    // dub episodes normally run from episode 1 up to dub_count, but on ongoing
    // shows the count can lag behind the episode list, so the last few trailing
    // episodes are checked for dub embeds before cutting the list short
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
        } catch (e: Exception) {
            Log.d(TAG, "probeEmbeds($malId, $epId) parse failed: ${e.message}")
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
        } catch (e: Exception) {
            Log.e(TAG, "loadLinks: bad episode data: ${e.message}")
            return false
        }
        val wantDub = epData.type == "dub"
        val modeLabel = if (wantDub) "Dub" else "Sub"

        val embeds = probeEmbeds(epData.malId, epData.ep) ?: run {
            Log.e(TAG, "loadLinks: no embeds for malId=${epData.malId} ep=${epData.ep}")
            return false
        }
        if (embeds.isEmpty()) {
            Log.e(TAG, "loadLinks: empty embed list for malId=${epData.malId} ep=${epData.ep}")
            return false
        }

        val matching = embeds.filter { if (wantDub) it.isDub() else it.isSub() }
            .ifEmpty { embeds }

        // sub and dub entries usually point at the same multi-audio stream, so
        // the source api is only hit once per unique id
        val sourceIds = matching.mapNotNull { it.remote_source_id }.distinct()
        if (sourceIds.isEmpty()) {
            Log.e(TAG, "loadLinks: embeds carry no source ids")
            return false
        }

        var found = false
        for (sourceId in sourceIds) {
            val source = fetchVidcloud(sourceId) ?: continue
            val file = source.source ?: continue
            val master = file.src ?: continue
            val apiQuality = file.quality?.takeIf { it.isNotBlank() && it != "Unknown" }

            for (track in source.subtitlesFor(wantDub)) {
                val url = track.vtt_url?.takeIf { it.isNotBlank() } ?: track.url ?: continue
                if (url.isBlank()) continue
                subtitleCallback.invoke(
                    newSubtitleFile(track.label ?: "English", url) {
                        this.headers = streamHeaders
                    }
                )
            }

            if (emitStreamLinks(master, modeLabel, wantDub, apiQuality, callback)) {
                found = true
            }
        }
        return found
    }

    // the cdn hands out aes-gcm encrypted playlists prefixed with EM3U8v1:, they
    // are decrypted locally and served through a rewriting proxy so every link
    // can pin one resolution and one audio track
    private suspend fun emitStreamLinks(
        master: String,
        modeLabel: String,
        wantDub: Boolean,
        apiQuality: String?,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val masterText: String? = try {
            val res = cfGet(master, headers = cdnHeaders, timeout = 20_000L)
            if (res.code == 200) res.text else null
        } catch (e: Exception) {
            Log.e(TAG, "master fetch failed: ${e.message}")
            null
        }

        var playlist = masterText
        if (playlist != null && RaghavSenshiCrypt.isEncrypted(playlist)) {
            playlist = RaghavSenshiCrypt.decrypt(playlist)
        }

        if (playlist != null && playlist.startsWith("#EXTM3U")) {
            val proxyBase = RaghavSenshiProxy.register(master, playlist, senshiHeaders(cdnHeaders, master))
            if (proxyBase != null) {
                val mode = if (wantDub) "dub" else "sub"
                val variants = parseVariantQualities(playlist)
                if (variants.isEmpty()) {
                    val label = "Senshi $modeLabel${apiQuality?.let { " $it" } ?: ""}"
                    callback.invoke(
                        newExtractorLink(source = name, name = label, url = "$proxyBase/m/$mode/0/master.m3u8", type = ExtractorLinkType.M3U8) {
                            this.quality = getQualityFromName(apiQuality)
                        }
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
            Log.e(TAG, "proxy register failed, falling back to raw url")
        } else if (masterText != null) {
            Log.e(TAG, "master is not a usable playlist")
        }

        val label = "Senshi $modeLabel${apiQuality?.let { " $it" } ?: ""}"
        callback.invoke(
            newExtractorLink(source = name, name = label, url = master, type = ExtractorLinkType.M3U8) {
                this.referer = "$mainUrl/"
                this.headers = streamHeaders
                this.quality = getQualityFromName(apiQuality)
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

    private suspend fun fetchVidcloud(sourceId: Int): VidcloudSource? {
        var text: String? = null
        for (attempt in 0..2) {
            if (attempt > 0) {
                delay(2500L * attempt)
            }
            text = try {
                val res = app.get("$vidcloudApi$sourceId", headers = apiHeaders, timeout = 20_000L)
                if (res.code == 200) res.text else null
            } catch (e: Exception) {
                Log.d(TAG, "vidcloud $sourceId request failed: ${e.message}")
                null
            }
            if (text != null && !text.contains("too_many_requests")) break
            text = null
        }
        if (text == null) {
            Log.e(TAG, "vidcloud source $sourceId unavailable after retries")
            return null
        }
        return try {
            parseJson<List<VidcloudSource>>(text).firstOrNull()
        } catch (e: Exception) {
            Log.e(TAG, "vidcloud source $sourceId parse failed: ${e.message}")
            null
        }
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

    // movie types hide the sub/dub switcher in the app, so dual-audio movies are
    // typed as regular anime to keep both tracks reachable
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

    private fun VidcloudTrack.isDubTrack(): Boolean {
        val label = (label ?: html ?: "").lowercase()
        return label.contains("dub") || (url ?: "").contains("ai_dub")
    }

    private fun VidcloudTrack.trackLabel(): String? {
        (label ?: html)?.let { if (it.isNotBlank() && it.lowercase() != "chapter") return it }
        return null
    }

    // dub mode keeps the dub captions (they match the english audio), sub mode
    // keeps the regular translation tracks; each falls back to the other set
    // when the stream only carries one kind
    private fun VidcloudSource.subtitlesFor(wantDub: Boolean): List<VidcloudTrack> {
        val usable = tracks.filter { it.trackLabel() != null }
        val dub = usable.filter { it.isDubTrack() }
        val sub = usable.filter { !it.isDubTrack() }
        return if (wantDub) dub.ifEmpty { sub } else sub.ifEmpty { dub }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
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

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SenshiAnime(
        val id: Int? = null,
        val public_id: String? = null,
        val anime_picture: String? = null,
        val trailer: String? = null,
        val title: String? = null,
        val title_english: String? = null,
        val synonyms: String? = null,
        val type: String? = null,
        val ani_source: String? = null,
        val ani_episodes: String? = null,
        val ani_status: String? = null,
        val airing_date: String? = null,
        val duration: String? = null,
        val rating: String? = null,
        val score: Double? = null,
        val scored_by: Int? = null,
        val ani_description: String? = null,
        val ani_season: String? = null,
        val ani_year: Int? = null,
        val genres: String? = null,
        val producers: String? = null,
        val studios: String? = null,
        val anilist_id: Int? = null,
        val sub_count: Int? = null,
        val dub_count: Int? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SenshiEpisode(
        val id: Int? = null,
        val ep_id: Int? = null,
        val mal_id: Int? = null,
        val ep_title: String? = null,
        val ep_filler: Boolean? = null,
        val ep_recap: Boolean? = null,
        val ep_thumbnail: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SenshiEmbed(
        val id: Int? = null,
        val public_id: String? = null,
        val remote_source_id: Int? = null,
        val url: String? = null,
        val status: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SenshiFilterResponse(
        val data: List<SenshiAnime> = emptyList(),
        val total: Int? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class VidcloudFile(
        val src: String? = null,
        val quality: String? = null,
        val audio: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class VidcloudTrack(
        val url: String? = null,
        val vtt_url: String? = null,
        val label: String? = null,
        val html: String? = null,
        val default: Boolean? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class VidcloudSource(
        val source: VidcloudFile? = null,
        val tracks: List<VidcloudTrack> = emptyList()
    )
}
