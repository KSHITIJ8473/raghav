package com.laddu100

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDubStatus
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class Reanime : MainAPI() {
    override var mainUrl = "https://reanime.to"
    override var name = "Re:ANIME"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA,
        TvType.TvSeries
    )

    override val mainPage = mainPageOf(
        "latest-episodes" to "Latest Episodes",
        "trending" to "Trending",
        "popular" to "Popular",
        "top-airing" to "Top Airing",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val category = request.data
        val doc = withContext(Dispatchers.IO) {
            app.get("$mainUrl/home").document
        }
        val home = mutableListOf<SearchResponse>()

        when (category) {
            "latest-episodes" -> {
                val items = doc.select(".latest-episodes .anime-card, .latest-section .anime-card, .grid .anime-card")
                for (item in items) extractAnimeCard(item, home)
            }
            "trending" -> {
                val items = doc.select(".trending .anime-card, .popular-section .anime-card")
                for (item in items) extractAnimeCard(item, home)
            }
            "popular" -> {
                val items = doc.select(".popular .anime-card")
                for (item in items) extractAnimeCard(item, home)
            }
            "top-airing" -> {
                val items = doc.select(".top-airing .anime-card, .season .anime-card")
                for (item in items) extractAnimeCard(item, home)
            }
            else -> {
                val items = doc.select(".anime-card, .card, [class*=anime]")
                for (item in items) extractAnimeCard(item, home)
            }
        }

        return newHomePageResponse(request.name, home)
    }

    private fun extractAnimeCard(item: Element, out: MutableList<SearchResponse>) {
        val linkEl = item.selectFirst("a[href*=\"/anime/\"]") ?: return
        val href = fixUrl(linkEl.attr("href"))
        val slug = href.substringAfterLast("/")

        val title = item.selectFirst("[class*=title], .name, h3")?.text()
            ?: linkEl.attr("title")
            ?: return

        val img = item.selectFirst("img")?.attr("data-src")
            ?: item.selectFirst("img")?.attr("src")
            ?: ""

        val subEps = item.selectFirst(".sub, [class*=sub]")?.text()?.trim()?.toIntOrNull()
        val dubEps = item.selectFirst(".dub, [class*=dub]")?.text()?.trim()?.toIntOrNull()

        val isMovie = item.selectFirst("[class*=movie], .movie") != null
        val isOva = item.selectFirst("[class*=ova], .ova") != null
        val tvType = when {
            isMovie -> TvType.AnimeMovie
            isOva -> TvType.OVA
            else -> TvType.Anime
        }

        out.add(newAnimeSearchResponse(title, href, tvType) {
            this.posterUrl = fixUrl(img)
            addDubStatus(
                dubExist = dubEps != null && dubEps > 0,
                subExist = subEps != null && subEps > 0,
                dubEpisodes = dubEps,
                subEpisodes = subEps
            )
        })
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/api/search?q=$query"
        return try {
            val response = withContext(Dispatchers.IO) {
                app.get(url).text
            }
            val data = parseJson<ReanimeSearchResponse>(response)
            data.results.mapNotNull { item ->
                val anime = item.anime ?: return@mapNotNull null
                val slug = anime.animeId ?: return@mapNotNull null
                val title = anime.title?.english ?: anime.title?.romaji ?: return@mapNotNull null
                val coverImage = anime.coverImage?.extraLarge ?: anime.coverImage?.large ?: ""
                val subEps = anime.subbed
                val dubEps = anime.dubbed
                val format = anime.format?.lowercase() ?: ""

                val tvType = when (format) {
                    "movie", "film" -> TvType.AnimeMovie
                    "ova", "ona", "special" -> TvType.OVA
                    else -> TvType.Anime
                }

                newAnimeSearchResponse(title, "$mainUrl/watch/$slug", tvType) {
                    this.posterUrl = fixUrl(coverImage)
                    addDubStatus(
                        dubExist = dubEps != null && dubEps > 0,
                        subExist = subEps != null && subEps > 0,
                        dubEpisodes = dubEps,
                        subEpisodes = subEps
                    )
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val slug = url.substringAfterLast("/watch/").substringBefore("?")
        val doc = withContext(Dispatchers.IO) {
            app.get(url).document
        }

        val title = doc.selectFirst("h1, .anime-title, [class*=title]")?.text() ?: return null
        val poster = doc.selectFirst(".poster img, [class*=poster] img, img[class*=cover]")?.attr("data-src")
            ?: doc.selectFirst(".poster img, [class*=poster] img, img[class*=cover]")?.attr("src")
            ?: ""
        val plot = doc.selectFirst(".synopsis, .description, [class*=description], [class*=synopsis]")?.text()
            ?: ""
        val year = doc.selectFirst("[class*=year]")?.text()?.trim()?.toIntOrNull()
        val tags = doc.select(".genre, [class*=genre] a, .tags a").map { it.text().trim() }.filter { it.isNotEmpty() }
        val typeStr = doc.selectFirst("[class*=type], .type")?.text()?.trim() ?: ""
        val status = doc.selectFirst("[class*=status], .status")?.text()?.trim()
        val showStatus = when (status?.lowercase()) {
            "ongoing", "currently airing", "releasing" -> ShowStatus.Ongoing
            "completed", "finished" -> ShowStatus.Completed
            else -> null
        }

        val tvType = when (typeStr.lowercase()) {
            "movie", "film" -> TvType.AnimeMovie
            "ova", "ona", "special" -> TvType.OVA
            else -> TvType.Anime
        }

        val episodes = fetchEpisodes(slug, url)

        return newAnimeLoadResponse(title, url, tvType) {
            this.posterUrl = fixUrl(poster)
            this.year = year
            this.plot = plot
            this.tags = tags
            this.showStatus = showStatus
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    private suspend fun fetchEpisodes(slug: String, referer: String): List<Episode> {
        return try {
            val response = withContext(Dispatchers.IO) {
                app.get("$mainUrl/api/episodes/$slug").text
            }
            val data = parseJson<ReanimeEpisodesResponse>(response)
            val episodesList = data.data ?: emptyList()
            
            episodesList.mapNotNull { ep ->
                val epNum = ep.episodeNumber ?: return@mapNotNull null
                val title = ep.title?.takeIf { it.isNotBlank() } ?: "Episode $epNum"
                
                newEpisode("$slug|$epNum") {
                    this.name = title
                    this.episode = epNum
                    this.posterUrl = fixUrl(ep.thumbnail)
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = coroutineScope {
        val parts = data.split("|")
        if (parts.size < 2) return@coroutineScope false

        val slug = parts[0]
        val epNum = parts[1]
        val watchUrl = "$mainUrl/watch/$slug?ep=$epNum"

        val response = withContext(Dispatchers.IO) {
            app.get("$mainUrl/api/watch/$slug/$epNum", headers = mapOf("Referer" to watchUrl)).text
        }

        val apiData = try {
            parseJson<ReanimeWatchResponse>(response)
        } catch (e: Exception) {
            null
        } ?: return@coroutineScope false
        
        if (apiData.success != true) return@coroutineScope false

        val sources = apiData.episodeSources ?: emptyList()
        var foundAny = false

        for (sourceGroup in sources) {
            val releaseType = sourceGroup.releaseType?.lowercase() ?: "sub"
            val epSources = sourceGroup.episodeSources ?: continue

            for (src in epSources) {
                val url = src.url ?: src.embedUrl ?: continue
                val name = src.name ?: src.provider ?: "Server"
                val qualityStr = src.quality ?: src.resolution ?: ""

                val quality = when {
                    qualityStr.contains("4K", true) || qualityStr.contains("2160") -> 2160
                    qualityStr.contains("1080", true) -> 1080
                    qualityStr.contains("720", true) -> 720
                    qualityStr.contains("480", true) -> 480
                    qualityStr.contains("360", true) -> 360
                    else -> -1
                }

                foundAny = true
                callback.invoke(
                    newExtractorLink(
                        source = "Re:ANIME",
                        name = "$name ($releaseType)",
                        url = url,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.quality = quality
                        this.headers = mapOf("Referer" to watchUrl)
                    }
                )
            }
        }

        if (!foundAny) {
            // Fallback: try scraping embed URLs from watch page HTML
            val html = withContext(Dispatchers.IO) {
                app.get(watchUrl).text
            }
            val embedRegex = Regex("""(https?://[^"'\\s]+\.(?:m3u8|mp4)(?:\?[^"'\\s]*)?)""")
            for (match in embedRegex.findAll(html)) {
                val streamUrl = match.groupValues[1]
                foundAny = true
                callback.invoke(
                    newExtractorLink(
                        source = "Re:ANIME",
                        name = "Direct",
                        url = streamUrl,
                        type = if (streamUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.headers = mapOf("Referer" to watchUrl)
                    }
                )
            }

            // Also try to extract player iframe/src
            val iframeRegex = Regex("""(?i)<iframe[^>]+src=["'](https?://[^"']+)["']""")
            for (match in iframeRegex.findAll(html)) {
                val embedUrl = match.groupValues[1]
                if (embedUrl.contains("reanime.to")) continue
                val loaded = loadExtractor(embedUrl, watchUrl, subtitleCallback) { link ->
                    foundAny = true
                    callback.invoke(link)
                }
                if (loaded) foundAny = true
            }
        }

        return@coroutineScope foundAny
    }

    // ─── Data Models ──────────────────────────────────────────────────────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ReanimeSearchResponse(
        val results: List<ReanimeSearchResult>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ReanimeSearchResult(
        val anime: ReanimeSearchAnime? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ReanimeSearchAnime(
        val animeId: String? = null,
        val id: String? = null,
        val title: ReanimeAnimeTitle? = null,
        val coverImage: ReanimeCoverImage? = null,
        val cover_image: ReanimeCoverImage? = null,
        val subbed: Int? = 0,
        val dubbed: Int? = 0,
        val format: String? = null,
        val type: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ReanimeAnimeTitle(
        val english: String? = null,
        val romaji: String? = null,
        val native: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ReanimeCoverImage(
        val extraLarge: String? = null,
        val large: String? = null,
        val medium: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ReanimeEpisodesResponse(
        val data: List<ReanimeEpisodeData>? = null,
        val total: Int? = 0
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ReanimeEpisodeData(
        val episodeId: String? = null,
        val episode_number: Int? = 0,
        val title: String? = null,
        val thumbnail: String? = null,
        val description: String? = null,
        val is_filler: Boolean? = false,
        val aired: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ReanimeWatchResponse(
        val success: Boolean? = false,
        val anime: ReanimeWatchAnimeInfo? = null,
        val episodeSources: List<ReanimeSourceGroup>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ReanimeWatchAnimeInfo(
        val animeId: String? = null,
        val title: ReanimeWatchTitle? = null,
        val coverImage: ReanimeWatchCover? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ReanimeWatchTitle(
        val english: String? = null,
        val romaji: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ReanimeWatchCover(
        val extraLarge: String? = null,
        val large: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ReanimeSourceGroup(
        val releaseType: String? = null,
        val episodeSources: List<ReanimeEpisodeSourceItem>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ReanimeEpisodeSourceItem(
        val url: String? = null,
        val embedUrl: String? = null,
        val name: String? = null,
        val provider: String? = null,
        val server: String? = null,
        val quality: String? = null,
        val resolution: String? = null,
        val type: String? = null,
        val isActive: Boolean? = true
    )
}
