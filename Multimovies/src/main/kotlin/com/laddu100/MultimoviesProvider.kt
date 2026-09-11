package com.laddu100

import com.lagradost.api.Log
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

private const val TAG = "Multimovies"

class MultimoviesProvider : MainAPI() {
    override var mainUrl = "https://multimovies.casa"
    override var name = "Multimovies"
    override var lang = "en"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    override val mainPage = mainPageOf(
        "/trending/" to "Trending",
        "/movies/" to "Latest Movies",
        "/tvshows/" to "Latest TV Shows",
        "/genre/anime-series/" to "Anime Series",
        "/genre/anime-movies/" to "Anime Movies",
        "/genre/bollywood-movies/" to "Bollywood",
    )

    private val headers = mapOf(
        "User-Agent" to MMNet.UA,
        "Accept-Language" to "en-US,en;q=0.9",
    )

    private var validatedDomain: String? = null

    // probe remote domains before switching - the firebase list can lag behind rotations
    private suspend fun refreshDomain() {
        try {
            val remote = FirebaseDomainHelper.getDomain("multimovies") ?: return
            if (remote == mainUrl || remote == validatedDomain) return
            validatedDomain = remote
            val ok = try {
                app.get("$remote/", headers = headers, timeout = 10_000L).isSuccessful
            } catch (e: Exception) {
                false
            }
            if (ok) mainUrl = remote
        } catch (e: Exception) {
            Log.d(TAG, "refreshDomain: ${e.message}")
        }
    }

    private fun firstImg(el: Element): String {
        val img = el.selectFirst("img") ?: return ""
        return img.attr("src").ifBlank { img.attr("data-src") }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        refreshDomain()
        val base = request.data.trimEnd('/')
        val url = if (page <= 1) "$mainUrl$base/" else "$mainUrl$base/page/$page/"
        return try {
            val doc = mmGet(url, headers = headers).document
            val items = doc.select("article.item, .items article").mapNotNull { it.toSearchResult() }
                .distinctBy { it.url }
            val hasNext = doc.selectFirst("a[href*='/page/${page + 1}/'], a.next.page-numbers") != null
            newHomePageResponse(request.name, items, hasNext = hasNext && items.isNotEmpty())
        } catch (e: Exception) {
            Log.d(TAG, "getMainPage: ${e.message}")
            newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        refreshDomain()
        return try {
            val doc = mmGet("$mainUrl/?s=${query.trim().replace(" ", "+")}", headers = headers).document
            doc.select(".result-item article").mapNotNull { it.toSearchResult() }
                .distinctBy { it.url }
        } catch (e: Exception) {
            Log.d(TAG, "search: ${e.message}")
            emptyList()
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleA = selectFirst(".details .title a, .data h3 a")
        val a = titleA
            ?: selectFirst(".thumbnail a, a[href*='/movies/'], a[href*='/tvshows/']")
            ?: return null
        val href = a.attr("href").trim()
        if (href.isBlank()) return null
        var title = titleA?.text()?.trim() ?: ""
        if (title.isBlank()) {
            title = selectFirst("img")?.attr("alt")?.trim() ?: ""
        }
        if (title.isBlank()) return null

        val poster = firstImg(this)
        val isTv = href.contains("/tvshows/") || selectFirst(".tvshows, .item.tvshows, span.tvshows") != null
        val year = Regex("(19|20)\\d{2}").find(text())?.value?.toIntOrNull()

        return newMovieSearchResponse(title, href, if (isTv) TvType.TvSeries else TvType.Movie) {
            this.posterUrl = poster
            this.year = year
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        refreshDomain()
        return try {
            val doc = mmGet(url, headers = headers).document
            val title = doc.selectFirst("h1")?.text()?.trim() ?: return null
            val poster = doc.selectFirst(".poster img")?.let {
                it.attr("src").ifBlank { it.attr("data-src") }
            } ?: ""
            val background = doc.selectFirst("meta[property=og:image]")?.attr("content") ?: poster
            val genres = doc.select("a[href*='/genre/']").map { it.text().trim() }
                .filter { it.isNotBlank() }.distinct()
            val plot = doc.selectFirst(".wp-content p, .wp-content, [itemprop=description]")?.text()
                ?.trim()?.take(1000)
            val year = Regex("\\b(19|20)\\d{2}\\b").find(doc.selectFirst("span.date")?.text() ?: "")?.value?.toIntOrNull()
            val rating = doc.selectFirst(".dt_rating_vgs, .rating")?.text()?.trim()?.toDoubleOrNull()
            val duration = doc.selectFirst(".runtime")?.text()?.trim()?.let {
                Regex("(\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull()
            }

            val isTv = url.contains("/tvshows/") || url.contains("/episodes/") || doc.selectFirst("div.se-c") != null
            val tvType = if (url.contains("anime") || genres.any { it.contains("anime", true) }) TvType.Anime else TvType.TvSeries

            if (isTv) {
                val episodes = parseEpisodes(doc)
                return newTvSeriesLoadResponse(title, url, tvType, episodes) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = background
                    this.plot = plot
                    this.tags = genres
                    this.year = year
                    this.score = rating?.let { Score.from10(it) }
                }
            } else {
                return newMovieLoadResponse(title, url, if (url.contains("anime")) TvType.Anime else TvType.Movie, url) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = background
                    this.plot = plot
                    this.tags = genres
                    this.year = year
                    this.score = rating?.let { Score.from10(it) }
                    this.duration = duration
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "load: ${e.message}")
            null
        }
    }

    private fun parseEpisodes(doc: Document): List<Episode> {
        val episodes = mutableListOf<Episode>()
        for (block in doc.select("div.se-c")) {
            val season = block.selectFirst(".se-t")?.text()?.trim()?.toIntOrNull() ?: continue
            for (li in block.select("ul.episodios li")) {
                val a = li.selectFirst(".episodiotitle a") ?: continue
                val href = a.attr("href").trim()
                val name = a.text().trim()
                if (href.isBlank()) continue
                val numerando = li.selectFirst(".numerando")?.text() ?: ""
                val epNum = Regex("(\\d+)\\s*-\\s*(\\d+)").find(numerando)?.groupValues?.get(2)?.toIntOrNull()
                    ?: Regex("(?:\\d+)x(\\d+)").find(href)?.groupValues?.get(1)?.toIntOrNull()
                    ?: continue
                episodes.add(newEpisode(href) {
                    this.name = name
                    this.season = season
                    this.episode = epNum
                    this.posterUrl = firstImg(li)
                })
            }
        }
        return episodes.sortedWith(compareBy({ it.season }, { it.episode }))
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        refreshDomain()
        var any = false
        try {
            val doc = mmGet(data, headers = headers).document
            val options = doc.select("li.dooplay_player_option")
                .filter { !it.attr("id").contains("trailer") }
            if (options.isEmpty()) return false

            data class Opt(val postId: String, val type: String, val nume: String, val label: String)

            val opts = options.mapNotNull { opt ->
                val postId = opt.attr("data-post").trim()
                val type = opt.attr("data-type").trim().ifBlank { "movie" }
                val nume = opt.attr("data-nume").trim()
                val label = opt.selectFirst(".title")?.text()?.trim()
                    ?.replace(" - Recommended", "", ignoreCase = true)
                    ?.replace(" Recommended", "", ignoreCase = true)
                    ?.replace("GDMIRROR", "GD Mirror", ignoreCase = true)
                    ?.trim() ?: "Source"
                if (postId.isBlank() || nume.isBlank()) null else Opt(postId, type, nume, label)
            }

            val embeds = coroutineScope {
                opts.map { opt ->
                    async(Dispatchers.IO) {
                        try {
                            val body = mmPost(
                                "$mainUrl/wp-admin/admin-ajax.php",
                                data = mapOf(
                                    "action" to "doo_player_ajax",
                                    "post" to opt.postId,
                                    "nume" to opt.nume,
                                    "type" to opt.type,
                                ),
                                headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
                                referer = data,
                            ).text
                            val embed = Regex("\"embed_url\"\\s*:\\s*\"([^\"]+)\"").find(body)
                                ?.groupValues?.get(1)?.let { MMNet.deEsc(it) } ?: ""
                            embed to opt.label
                        } catch (e: Exception) {
                            "" to opt.label
                        }
                    }
                }.map { it.await() }
            }.filter { it.first.isNotBlank() }

            // modiplay first: its origin is needed by the gdmirror proxy fallback
            val sorted = embeds.sortedByDescending { MMNet.hostOf(it.first).contains("modiplay") }

            // several sources share the same cdn files - dedupe links and subs by url
            val seenLinks = java.util.Collections.newSetFromMap(
                java.util.concurrent.ConcurrentHashMap<String, Boolean>()
            )
            val seenSubs = java.util.Collections.newSetFromMap(
                java.util.concurrent.ConcurrentHashMap<String, Boolean>()
            )
            val linkCb: (ExtractorLink) -> Unit = { link ->
                if (seenLinks.add(link.url)) callback(link)
            }
            val subCb: (SubtitleFile) -> Unit = { sub ->
                if (seenSubs.add(sub.url)) subtitleCallback(sub)
            }

            var modiplayBase: String? = null
            for ((embed, label) in sorted) {
                try {
                    val host = MMNet.hostOf(embed)
                    val handled = when {
                        host.contains("modiplay") -> {
                            val base = MMModiplay.resolve(embed, label, subCb, linkCb)
                            if (base != null) modiplayBase = base
                            base != null
                        }
                        host.contains("iqsmartgames") ->
                            MMGdmirror.resolve(embed, label, modiplayBase, subCb, linkCb)
                        host.contains("filesforever") ->
                            MMGdmirror.resolve(embed, label, modiplayBase, subCb, linkCb)
                        host.contains("nxsha.") ->
                            MMNxsha.resolve(embed, label, subCb, linkCb)
                        host.contains("vidout") ->
                            MMVidout.resolve(embed, label, subCb, linkCb)
                        else -> {
                            // screenscape and any future embed - best-effort m3u8 grep
                            val html = MMNet.getText(embed, referer = "$mainUrl/")
                            if (html != null) {
                                extractM3u8Links(html, MMNet.originOf(embed).ifBlank { embed }, label, linkCb)
                            } else false
                        }
                    }
                    any = any || handled
                } catch (e: Exception) {
                    // one broken source must not kill the rest
                    Log.d(TAG, "loadLinks $label: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "loadLinks: ${e.message}")
            return any
        }
        return any
    }

    private suspend fun extractM3u8Links(
        html: String,
        base: String,
        linkLabel: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val m3u8 = Regex("https?://[^\"'<>\\s\\\\]+\\.m3u8[^\"'<>\\s\\\\]*")
            .findAll(html).map { it.value.replace("\\/", "/") }.toList()
        val relM3u8 = Regex("[\"']([^\"']+\\.m3u8[^\"']*)[\"']").findAll(html)
            .map { it.groupValues[1].replace("\\/", "/") }.toList()
        val all = (m3u8 + relM3u8.map { MMNet.abs(base, it) }).distinct()
        for (u in all) {
            callback(
                newExtractorLink(linkLabel, linkLabel, u, type = ExtractorLinkType.M3U8) {
                    this.headers = mapOf("Referer" to base)
                }
            )
        }
        return all.isNotEmpty()
    }
}
