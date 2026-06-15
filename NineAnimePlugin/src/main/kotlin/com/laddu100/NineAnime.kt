package com.laddu100

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.network.WebViewResolver
import com.google.gson.JsonParser
import org.jsoup.Jsoup
import android.util.Base64

class NineAnime : MainAPI() {
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
                val typeText = element.selectFirst(".type")?.text() ?: ""
                this.dubStatus = if (title.contains("(Dub)", ignoreCase = true) || typeText.contains("Dub", ignoreCase = true)) {
                    mutableSetOf(DubStatus.Dubbed)
                } else {
                    mutableSetOf(DubStatus.Subbed)
                }
            }
        }
        return newHomePageResponse(request.name, anime, hasNext = anime.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
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
                val typeText = element.selectFirst(".type")?.text() ?: ""
                this.dubStatus = if (title.contains("(Dub)", ignoreCase = true) || typeText.contains("Dub", ignoreCase = true)) {
                    mutableSetOf(DubStatus.Dubbed)
                } else {
                    mutableSetOf(DubStatus.Subbed)
                }
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        var detailUrl = url
        if (!url.contains("/anime/")) {
            // Fetch the episode page to find the parent anime page link
            val doc = app.get(url).document
            val animeLink = doc.selectFirst("a[href*=/anime/]")?.attr("href")
                ?: doc.select("a").firstOrNull { it.attr("href").contains("/anime/") }?.attr("href")
            if (animeLink != null) {
                detailUrl = animeLink
            }
        }

        val doc = app.get(detailUrl).document
        val title = doc.selectFirst("h1.entry-title")?.text() 
            ?: doc.selectFirst("h1")?.text() 
            ?: "Unknown"
        
        // Poster
        val posterUrl = doc.selectFirst(".thumb img")?.attr("src")
            ?: doc.selectFirst(".poster img")?.attr("src")
            ?: doc.selectFirst("img")?.attr("src")

        // Description/Plot
        val plot = doc.selectFirst(".entry-content")?.text()
            ?: doc.selectFirst(".desc")?.text()
            ?: doc.selectFirst(".story")?.text()

        // Metadata
        val tags = doc.select(".info-content a[href*=/genre/]").map { it.text() }
        
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

        // Episodes
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

        // Episodes on 9anime are listed in reverse order (newest first), reverse it
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
    ): Boolean {
        // "data" is the watch page URL, e.g. https://9anime.org.lv/one-piece-episode-1166/
        val res = try {
            app.get(data)
        } catch (e: Exception) {
            return false
        }
        val html = res.text
        val doc = res.document

        var found = false

        // 1. Process base64 mirrors in the <select class="mirror"> tag
        val selectMirror = doc.selectFirst("select.mirror")
        if (selectMirror != null) {
            val options = selectMirror.select("option")
            options.forEach { opt ->
                val b64Value = opt.attr("value")
                if (b64Value.isBlank() || b64Value == "...") return@forEach

                val decodedIframe = try {
                    val decodedBytes = Base64.decode(b64Value, Base64.DEFAULT)
                    String(decodedBytes, Charsets.UTF_8)
                } catch (e: Exception) {
                    null
                } ?: return@forEach

                val iframeUrl = Jsoup.parse(decodedIframe).selectFirst("iframe")?.attr("src") ?: return@forEach

                val resolved = resolveAndExtract(iframeUrl, data, subtitleCallback, callback)
                if (resolved) {
                    found = true
                }
            }
        }

        // 2. Fetch AJAX dynamic kiwi links if present
        val security = Regex("""var security\s*=\s*'([a-f0-9]+)'""").find(html)?.groupValues?.get(1)
        val malId = Regex("""var malId\s*=\s*'(\d+)'""").find(html)?.groupValues?.get(1)
        val ep = Regex("""var ep\s*=\s*'(\d+)'""").find(html)?.groupValues?.get(1)

        if (security != null && malId != null && ep != null) {
            try {
                val ajaxResponse = app.post(
                    "$mainUrl/wp-admin/admin-ajax.php",
                    data = mapOf(
                        "action" to "fetch_kiwik_stream_links",
                        "security" to security,
                        "mal_id" to malId,
                        "ep" to ep
                    ),
                    headers = mapOf(
                        "X-Requested-With" to "XMLHttpRequest",
                        "Referer" to data
                    )
                ).text

                val json = JsonParser.parseString(ajaxResponse).asJsonObject
                if (json.get("success")?.asBoolean == true) {
                    val dataObj = json.getAsJsonObject("data")
                    if (dataObj != null && dataObj.get("status")?.asInt == 200) {
                        val optionsArray = dataObj.getAsJsonArray("options")
                        if (optionsArray != null) {
                            for (opt in optionsArray) {
                                val optObj = opt.asJsonObject
                                val optUrl = optObj.get("url")?.asString ?: continue
                                val resolved = resolveAndExtract(optUrl, data, subtitleCallback, callback)
                                if (resolved) {
                                    found = true
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                // ignore
            }
        }

        return found
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
                embedUrl.contains("vidwish.live") -> {
                    NineAnimeVidWish().getUrl(embedUrl, iframeUrl, subtitleCallback, callback)
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
            false
        }
    }
}
