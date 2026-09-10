package com.laddu100

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import org.jsoup.Jsoup
import android.util.Base64
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import com.lagradost.api.Log

class NineAnime : MainAPI() {
    private val TAG = "NineAnime"

    override var mainUrl = "https://9anime.org.lv"
    override var name = "9anime"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    override val mainPage = mainPageOf(
        "" to "Latest Releases"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        mainUrl = FirebaseDomainHelper.getDomain("nineanime") ?: mainUrl
        val url = if (page > 1) "$mainUrl/page/$page/" else "$mainUrl/"
        val doc = app.get(url).document
        val items = doc.select("article.bs")

        val anime = items.mapNotNull { element ->
            val a = element.selectFirst("a") ?: return@mapNotNull null
            val href = a.attr("href")
            val title = element.selectFirst(".entry-title")?.text()
                ?: element.selectFirst("h2")?.text()
                ?: a.text()
            val posterUrl = element.selectFirst("img")?.attr("src")

            newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = posterUrl
            }
        }
        return newHomePageResponse(request.name, anime, hasNext = anime.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        mainUrl = FirebaseDomainHelper.getDomain("nineanime") ?: mainUrl
        val searchUrl = "$mainUrl/?s=${query.replace(" ", "+")}"
        val doc = app.get(searchUrl).document
        val items = doc.select("article.bs")

        return items.mapNotNull { element ->
            val a = element.selectFirst("a") ?: return@mapNotNull null
            val href = a.attr("href")
            val title = element.selectFirst(".entry-title")?.text()
                ?: element.selectFirst("h2")?.text()
                ?: a.text()
            val posterUrl = element.selectFirst("img")?.attr("src")

            newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        mainUrl = FirebaseDomainHelper.getDomain("nineanime") ?: mainUrl
        var detailUrl = url
        if (!url.contains("/anime/")) {
            val doc = app.get(url).document
            val animeLink = doc.select("a[href*=/anime/]").map { it.attr("href") }.firstOrNull { href ->
                val path = href.substringAfter("/anime/").trim('/')
                path.isNotEmpty() && !path.contains('/') && !path.contains('?') && !path.contains("page")
            }
            if (animeLink != null) {
                detailUrl = animeLink
            }
        }

        val doc = app.get(detailUrl).document
        val title = doc.selectFirst("h1.entry-title")?.text()
            ?: doc.selectFirst("h1")?.text()
            ?: "Unknown"

        val posterUrl = doc.selectFirst(".thumb img")?.attr("src")
            ?: doc.selectFirst(".poster img")?.attr("src")
            ?: doc.selectFirst("img")?.attr("src")

        val plot = doc.selectFirst(".entry-content")?.text()
            ?: doc.selectFirst(".desc")?.text()
            ?: doc.selectFirst(".story")?.text()

        val tags = doc.select(".genxed a").map { it.text() }

        val statusText = doc.selectFirst(".info-content")?.text() ?: ""
        val showStatus = when {
            statusText.contains("Ongoing", ignoreCase = true) -> ShowStatus.Ongoing
            statusText.contains("Completed", ignoreCase = true) -> ShowStatus.Completed
            else -> null
        }

        val typeText = doc.selectFirst(".info-content")?.text() ?: ""
        val tvType = when {
            typeText.contains("Movie", ignoreCase = true) -> TvType.AnimeMovie
            typeText.contains("OVA", ignoreCase = true) || typeText.contains("ONA", ignoreCase = true) -> TvType.OVA
            else -> TvType.Anime
        }

        val episodesList = mutableListOf<Episode>()
        val eplister = doc.select(".eplister li")
        if (eplister.isNotEmpty()) {
            eplister.forEach { li ->
                val a = li.selectFirst("a") ?: return@forEach
                val epHref = a.attr("href")
                val epNum = li.selectFirst(".epl-num")?.text()?.toIntOrNull()
                    ?: Regex("""\d+""").find(li.selectFirst(".epl-num")?.text() ?: "")?.value?.toIntOrNull()
                    ?: 1
                val epTitle = li.selectFirst(".epl-title")?.text() ?: "Episode $epNum"
                val epDate = li.selectFirst(".epl-date")?.text()

                episodesList.add(newEpisode(epHref) {
                    this.name = epTitle
                    this.episode = epNum
                    this.description = epDate
                })
            }
        } else {
            // Fallback for single episode movie / OVA
            episodesList.add(newEpisode(detailUrl) {
                this.name = title
                this.episode = 1
            })
        }

        // episodes on 9anime are listed newest first, cloudstream expects oldest first
        episodesList.reverse()

        val isDub = title.contains("(Dub)", ignoreCase = true) || detailUrl.contains("-dub", ignoreCase = true)

        return newAnimeLoadResponse(title, detailUrl, tvType) {
            this.posterUrl = posterUrl
            this.plot = plot
            this.tags = tags
            this.showStatus = showStatus

            if (isDub) {
                addEpisodes(DubStatus.Dubbed, episodesList)
            } else {
                addEpisodes(DubStatus.Subbed, episodesList)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = coroutineScope {
        val res = try {
            app.get(data)
        } catch (e: Exception) {
            Log.w(TAG, "watch page load failed: ${e.message}")
            return@coroutineScope false
        }
        val doc = res.document

        // gogo mirrors wrap the real embeds one level deeper, one iframe per server variant
        val embedUrls = doc.select("select.mirror option").mapNotNull { opt ->
            val b64Value = opt.attr("value")
            if (b64Value.isBlank() || b64Value == "...") return@mapNotNull null
            val decodedIframe = try {
                String(Base64.decode(b64Value, Base64.DEFAULT), Charsets.UTF_8)
            } catch (e: Exception) {
                Log.w(TAG, "mirror decode failed: ${e.message}")
                null
            } ?: return@mapNotNull null
            Jsoup.parse(decodedIframe).selectFirst("iframe")?.attr("src")
        }.flatMap { iframeUrl ->
            if (iframeUrl.contains("gogoanime.me.uk/newplayer.php")) {
                val playerHtml = try {
                    app.get(iframeUrl, headers = mapOf("Referer" to data)).text
                } catch (e: Exception) {
                    Log.w(TAG, "gogo player fetch failed: ${e.message}")
                    ""
                }
                val innerSrcs = Jsoup.parse(playerHtml).select("iframe")
                    .map { it.attr("src") }.filter { it.isNotBlank() }
                if (innerSrcs.isNotEmpty()) innerSrcs else listOf(iframeUrl)
            } else {
                listOf(iframeUrl)
            }
        }.distinct()

        val results = embedUrls.map { embedUrl ->
            async {
                try {
                    resolveAndExtract(embedUrl, data, subtitleCallback, callback)
                } catch (e: Exception) {
                    Log.w(TAG, "extract failed for $embedUrl: ${e.message}")
                    false
                }
            }
        }
        results.awaitAll().any { it }
    }

    private suspend fun resolveAndExtract(
        iframeUrl: String,
        refererUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var embedUrl = iframeUrl
        if (iframeUrl.contains("gogoanime.me.uk/newplayer.php")) {
            val playerPage = try {
                app.get(iframeUrl, headers = mapOf("Referer" to refererUrl)).text
            } catch (e: Exception) {
                Log.w(TAG, "gogo player page failed: ${e.message}")
                return false
            }
            embedUrl = Jsoup.parse(playerPage).selectFirst("iframe")?.attr("src") ?: return false
        }

        return try {
            when {
                embedUrl.contains("plyr.php#") -> {
                    val b64 = embedUrl.substringAfter("#").substringBefore("#")
                    val decodedUrl = String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8)
                    if (decodedUrl.isNotBlank()) {
                        callback(
                            newExtractorLink(
                                "KiwiK",
                                "KiwiK",
                                decodedUrl,
                                if (decodedUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            ) {
                                this.referer = "https://gogoanime.me.uk/"
                            }
                        )
                        true
                    } else {
                        false
                    }
                }
                embedUrl.contains("megaplay.buzz") -> {
                    NineAnimeMegaPlay().getUrl(embedUrl, iframeUrl, subtitleCallback, callback)
                    true
                }
                embedUrl.contains("vidmoly.biz") -> {
                    NineAnimeVidmoly().getUrl(embedUrl, iframeUrl, subtitleCallback, callback)
                    true
                }
                embedUrl.contains("bysesayeveum.com") -> {
                    NineAnimeMoon().getUrl(embedUrl, iframeUrl, subtitleCallback, callback)
                    true
                }
                else -> {
                    loadExtractor(embedUrl, refererUrl, subtitleCallback, callback)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "extraction failed: ${e.message}")
            false
        }
    }
}
