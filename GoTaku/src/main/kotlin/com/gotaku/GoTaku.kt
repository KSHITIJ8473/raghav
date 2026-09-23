package com.gotaku

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

class GoTaku : MainAPI() {
    override var mainUrl = "https://gotaku.to"
    override var name = "GoTaku"
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    companion object {
        private const val TAG = "GoTaku"
    }

    override val mainPage = mainPageOf(
        "trending_day" to "Trending Today",
        "trending_week" to "Trending This Week",
        "latest" to "Recently Updated",
        "track:sub" to "Latest Sub",
        "track:dub" to "Latest Dub",
        "type:MOVIE" to "Anime Movies",
        "type:ONA" to "ONA"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val params = mutableMapOf(
            "sort" to "latest",
            "limit" to "28",
            "page" to page.toString()
        )
        when {
            request.data == "trending_day" || request.data == "trending_week" -> params["sort"] = request.data
            request.data.startsWith("track:") -> params["track"] = request.data.removePrefix("track:")
            request.data.startsWith("type:") -> params["type"] = request.data.removePrefix("type:")
        }
        val (titles, hasMore) = GoTakuApi.fetchTitles(params)
        val items = titles.mapNotNull { it.toSearchResponse() }
        return newHomePageResponse(request.name, items, hasNext = hasMore)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val (titles, _) = GoTakuApi.fetchTitles(mapOf("q" to query, "limit" to "28"))
        return titles.mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val titleId = url.substringAfter("title/").substringBefore("?").takeIf { it.isNotBlank() } ?: return null
        val detail = GoTakuApi.fetchTitleDetail(titleId) ?: return null
        val title = detail.name ?: return null
        val episodes = GoTakuApi.fetchEpisodes(titleId)
        if (episodes.isEmpty()) return null

        val poster = detail.poster_url
        val backdrop = detail.backdrop_url
        val plot = detail.synopsis?.takeIf { it.isNotBlank() }
        val year = detail.year
        val tags = detail.genres.orEmpty().mapNotNull { it.name }
        val duration = detail.duration_minutes?.takeIf { it > 0 }
        val rating = detail.age_rating

        // movie types hide the sub dub selector in the player
        val tvType = when (detail.format) {
            "OVA", "ONA", "SPECIAL" -> TvType.OVA
            else -> TvType.Anime
        }

        val subEpisodes = mutableListOf<Episode>()
        val dubEpisodes = mutableListOf<Episode>()
        for (entry in episodes) {
            val id = entry.id ?: continue
            val builder: (Episode).() -> Unit = {
                this.name = episodeDisplayName(entry)
                this.episode = entry.number
                this.posterUrl = entry.thumbnail_url
                this.description = buildString {
                    entry.is_filler?.let { if (it) append("Filler episode") }
                    entry.aired_at?.let {
                        if (isNotEmpty()) append(" | ")
                        append("Aired ${it.substringBefore("T")}")
                    }
                }.takeIf { it.isNotBlank() }
            }
            if (entry.sub == true) {
                subEpisodes.add(newEpisode(GoTakuEpisodeData(id, "hard_sub").toJson(), builder))
            }
            if (entry.dub == true) {
                dubEpisodes.add(newEpisode(GoTakuEpisodeData(id, "dub").toJson(), builder))
            }
        }
        if (subEpisodes.isEmpty() && dubEpisodes.isEmpty()) return null

        return newAnimeLoadResponse(title, url, tvType) {
            this.posterUrl = poster ?: backdrop
            this.backgroundPosterUrl = backdrop
            this.plot = plot
            this.tags = tags
            this.year = year
            this.duration = duration
            this.contentRating = rating
            if (subEpisodes.isNotEmpty()) addEpisodes(DubStatus.Subbed, subEpisodes)
            if (dubEpisodes.isNotEmpty()) addEpisodes(DubStatus.Dubbed, dubEpisodes)
        }
    }

    // a non numeric label like CAM marks a different cut of the episode
    private fun episodeDisplayName(entry: GoTakuApi.EpisodeEntry): String {
        val base = entry.name?.takeIf { it.isNotBlank() }
            ?: return "Episode ${entry.number ?: entry.label ?: ""}".trim()
        val label = entry.label ?: return base
        return if (base != label && !label.matches(Regex("""\d+"""))) "$base ($label)" else base
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val epData = try {
            parseJson<GoTakuEpisodeData>(data)
        } catch (e: Exception) {
            Log.d(TAG, "episode data parse failed: ${e.message}")
            return false
        }
        if (epData.episodeId.isBlank()) return false

        // the sub side of this site is hardsub only
        val trackLabel = if (epData.track == "dub") "Dub" else "Hardsub"
        val embedUrl = GoTakuApi.fetchEmbed(epData.episodeId, epData.track) ?: return false
        val stream = resolveStream(embedUrl) ?: return false

        val qualities = parseQualities(stream.masterPlaylist)
        if (qualities.isEmpty()) {
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = trackLabel,
                    url = "${stream.proxyUrl}/m/0/master.m3u8",
                    type = ExtractorLinkType.M3U8
                )
            )
            return true
        }
        qualities.forEach { (label, quality, index) ->
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "$trackLabel $label",
                    url = "${stream.proxyUrl}/m/$index/master.m3u8",
                    type = ExtractorLinkType.M3U8
                ) {
                    this.quality = quality
                }
            )
        }
        return true
    }

    class ResolvedStream(
        val proxyUrl: String,
        val masterPlaylist: String
    )

    private suspend fun resolveStream(embedUrl: String): ResolvedStream? {
        return try {
            val html = com.lagradost.cloudstream3.app.get(
                GoTakuApi.SITE + embedUrl,
                headers = GoTakuApi.browserHeaders + mapOf("Referer" to "${GoTakuApi.SITE}/")
            ).text

            val base = Regex("""data-manifest-base="([^"]+)"""").find(html)?.groupValues?.get(1) ?: return null
            val stamp = Regex("""data-manifest-stamp="([^"]+)"""").find(html)?.groupValues?.get(1) ?: return null

            // the cdn refuses the first manifest at times, a retry gets a good token
            var master: String? = null
            var manifest: GoTakuApi.ManifestInfo? = null
            for (attempt in 0 until 3) {
                manifest = GoTakuApi.fetchManifest(base, stamp) ?: continue
                val key = GoTakuCrypto.playlistKey(manifest.keySeed, manifest.token)
                val masterBytes = try {
                    val response = com.lagradost.cloudstream3.app.get(
                        manifest.source,
                        headers = GoTakuApi.browserHeaders + mapOf(
                            "Referer" to "${GoTakuApi.SITE}/",
                            "Origin" to GoTakuApi.SITE
                        )
                    )
                    if (response.isSuccessful) response.body.bytes() else null
                } catch (e: Exception) {
                    Log.d(TAG, "master attempt ${attempt + 1} failed: ${e.message}")
                    null
                } ?: continue

                master = GoTakuCrypto.decryptPlaylist(key, masterBytes)
                if (master != null && master.startsWith("#EXTM3U")) break
                master = null
            }
            if (master == null || manifest == null) return null

            val proxyBase = GoTakuProxy.register(
                manifestBase = base,
                stamp = manifest.stamp,
                token = manifest.token,
                expiresAt = manifest.expiresAt,
                keySeed = manifest.keySeed,
                segmentBytes = manifest.segmentBytes,
                masterUrl = manifest.source
            ) ?: return null

            ResolvedStream(proxyBase, master)
        } catch (e: Exception) {
            Log.d(TAG, "stream resolve failed: ${e.message}")
            null
        }
    }

    // each link pins one variant so the label matches what plays
    private fun parseQualities(master: String): List<Triple<String, Int, Int>> {
        val lines = master.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        val out = mutableListOf<Triple<String, Int, Int>>()
        var index = -1
        for (i in lines.indices) {
            if (!lines[i].startsWith("#EXT-X-STREAM-INF")) continue
            val res = Regex("""RESOLUTION=(\d+)x(\d+)""").find(lines[i])?.groupValues?.get(2)?.toIntOrNull()
            val height = res ?: continue
            index++
            val label = when {
                height >= 2160 -> "2160p"
                height >= 1080 -> "1080p"
                height >= 720 -> "720p"
                height >= 480 -> "480p"
                height >= 360 -> "360p"
                else -> "${height}p"
            }
            val quality = when {
                height >= 2160 -> Qualities.P2160.value
                height >= 1080 -> Qualities.P1080.value
                height >= 720 -> Qualities.P720.value
                height >= 480 -> Qualities.P480.value
                else -> Qualities.P360.value
            }
            out.add(Triple(label, quality, index))
        }
        return out
    }

    private fun GoTakuApi.TitleEntry.toSearchResponse(): SearchResponse? {
        val name = this.name ?: return null
        val id = this.id ?: return null
        val poster = this.poster_url ?: this.backdrop_url
        val tvType = when (this.format) {
            "OVA", "ONA", "SPECIAL" -> TvType.OVA
            else -> TvType.Anime
        }
        return newAnimeSearchResponse(name, "$mainUrl/title/$id", tvType) {
            this.posterUrl = poster
            this.year = this@toSearchResponse.year
            val subCount = this@toSearchResponse.episodes?.latest_sub
            val dubCount = this@toSearchResponse.episodes?.latest_dub
            addDubStatus(
                dubExist = (dubCount ?: 0) > 0,
                subExist = (subCount ?: 0) > 0,
                dubEpisodes = dubCount,
                subEpisodes = subCount
            )
        }
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class GoTakuEpisodeData(
    val episodeId: String,
    val track: String
)
