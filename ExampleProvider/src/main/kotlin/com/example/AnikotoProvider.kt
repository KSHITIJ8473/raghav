package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder

class AnikotoProvider : MainAPI() {
    override var mainUrl = "https://anikoto.cz"
    override var name = "Anikoto"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    override val mainPage = mainPageOf(
        "$mainUrl/latest-updated" to "Latest Updated",
        "$mainUrl/most-viewed" to "Most Popular",
        "$mainUrl/status/currently-airing" to "Ongoing",
        "$mainUrl/type/movie" to "Movies"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get("${request.data}?page=$page").document
        val items = doc.select("div.ani.items div.item").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val doc = app.get("$mainUrl/filter?keyword=$encodedQuery").document
        return doc.select("div.ani.items div.item").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document
        val title = doc.selectFirst("#w-info h1.title, h1[itemprop=name]")?.text()?.trim() ?: return null
        val poster = doc.selectFirst("#w-info .poster img, img[itemprop=image]")?.attr("src")
        val description = doc.selectFirst("#w-info .synopsis .content, #w-info .synopsis")?.text()
        val genres = doc.select("#w-info a[href*='/genre/']").map { it.text().trim() }
        val isMovie = doc.selectFirst("#w-info a[href*='/type/movie']") != null ||
            doc.selectFirst("#w-info .bmeta")?.text()?.contains("Type: Movie", ignoreCase = true) == true

        val episodes = doc.select("a[href*='/ep-']").mapIndexed { i, el ->
            newEpisode(fixUrl(el.attr("href"))) {
                this.episode = i + 1
                this.name = el.text().ifBlank { "Episode ${i + 1}" }
            }
        }.ifEmpty {
            val episodeNumber = Regex("""/ep-(\d+)""").find(url)?.groupValues?.getOrNull(1)?.toIntOrNull()
            if (episodeNumber != null) {
                listOf(
                    newEpisode(url) {
                        this.episode = episodeNumber
                        this.name = "Episode $episodeNumber"
                    }
                )
            } else {
                emptyList()
            }
        }

        return newAnimeLoadResponse(title, url, if (isMovie) TvType.AnimeMovie else TvType.Anime) {
            this.posterUrl = poster
            this.plot = description
            this.tags = genres
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        doc.selectFirst("iframe#iframe-embed, iframe[src]")?.attr("src")?.let {
            loadExtractor(fixUrl(it), data, subtitleCallback, callback)
        }
        doc.select("li.nav-item a[data-src], ul.nav li a[data-id]").forEach { el ->
            val src = el.attr("data-src").ifBlank { el.attr("data-id") }
            if (src.isNotBlank()) loadExtractor(fixUrl(src), data, subtitleCallback, callback)
        }
        return true
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val titleElement = selectFirst("a.name.d-title") ?: selectFirst("a[title]") ?: return null
        val href = titleElement.attr("href").ifBlank {
            selectFirst("div.poster a, a")?.attr("href").orEmpty()
        }
        val title = titleElement.text().trim().ifBlank {
            titleElement.attr("title").trim()
        }
        if (href.isBlank() || title.isBlank()) return null

        val poster = selectFirst("div.poster img, img")?.let {
            it.attr("data-src").ifBlank { it.attr("src") }
        }
        val type = if (selectFirst(".type, .right")?.text()?.contains("Movie", ignoreCase = true) == true) {
            TvType.AnimeMovie
        } else {
            TvType.Anime
        }

        return newAnimeSearchResponse(title, fixUrl(href), type) {
            this.posterUrl = poster?.let { fixUrl(it) }
        }
    }
}
