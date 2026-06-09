package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class AnikotoProvider : MainAPI() {
    override var mainUrl = "https://anikoto.cz"
    override var name = "Anikoto"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    override val mainPage = mainPageOf(
        "$mainUrl/latest-updated?page=" to "Latest Updated",
        "$mainUrl/most-viewed?page=" to "Most Popular",
        "$mainUrl/status/currently-airing?page=" to "Ongoing",
        "$mainUrl/type/movie?page=" to "Movies"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data + page).document
        val items = doc.select("div.flw-item").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/search?keyword=${query.replace(" ", "+")}").document
        return doc.select("div.flw-item").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document
        val title = doc.selectFirst("h2.film-name, h1.film-name")?.text() ?: return null
        val poster = doc.selectFirst("img.film-poster-img")?.attr("src")
        val description = doc.selectFirst("div.film-description")?.text()
        val genres = doc.select("a[href*='/genre/']").map { it.text().trim() }
        val isMovie = doc.selectFirst("a[href*='/type/movie']") != null

        val episodes = doc.select("a[href*='/ep-']").mapIndexed { i, el ->
            newEpisode(fixUrl(el.attr("href"))) {
                this.episode = i + 1
                this.name = el.text().ifBlank { "Episode ${i + 1}" }
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
        val href = selectFirst("a")?.attr("href") ?: return null
        val title = selectFirst("h3.film-name, .dynamic-name")?.text()?.trim() ?: return null
        val poster = selectFirst("img")?.attr("data-src") ?: selectFirst("img")?.attr("src")
        return newAnimeSearchResponse(title, fixUrl(href), TvType.Anime) {
            this.posterUrl = poster
        }
    }
}
