package com.laddu100

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.addDubStatus
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLEncoder

class Anivexa : MainAPI() {
    override var mainUrl = "https://anivexa.vercel.app"
    override var name = "Anivexa"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    override val mainPage = mainPageOf(
        "index.html" to "Latest Updates"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl/${request.data}"
        val doc = app.get(url).document
        
        val items = doc.select(".ani.items .item, .items .item, .card, div[class*='item']")
        val animeList = items.mapNotNull { element ->
            val aTag = element.selectFirst("a") ?: return@mapNotNull null
            val href = aTag.attr("href")
            val link = if (href.startsWith("http")) href else "$mainUrl/${href.removePrefix("/")}"
            
            val title = element.selectFirst(".name, .title, h3")?.text() 
                ?: element.selectFirst("img")?.attr("alt") 
                ?: aTag.text()
                ?: return@mapNotNull null
                
            val posterUrl = element.selectFirst("img")?.attr("src")

            newAnimeSearchResponse(title, link, TvType.Anime) {
                this.posterUrl = posterUrl
                addDubStatus(dubExist = true, subExist = true)
            }
        }
        // Fixed: Removed the invalid 'hasNext' parameter from the simple string-name constructor
        return newHomePageResponse(request.name, animeList)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val searchUrl = "$mainUrl/index.html?search=$encoded"
        val doc = app.get(searchUrl).document
        
        return doc.select(".ani.items .item, .items .item, .card").mapNotNull { element ->
            val aTag = element.selectFirst("a") ?: return@mapNotNull null
            val href = aTag.attr("href")
            val link = if (href.startsWith("http")) href else "$mainUrl/${href.removePrefix("/")}"
            val title = element.selectFirst(".name, .title, h3")?.text() 
                ?: element.selectFirst("img")?.attr("alt") 
                ?: return@mapNotNull null
            val posterUrl = element.selectFirst("img")?.attr("src")

            newAnimeSearchResponse(title, link, TvType.Anime) {
                this.posterUrl = posterUrl
                addDubStatus(dubExist = true, subExist = true)
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document
        
        val title = doc.selectFirst("h1.title, h1")?.text() ?: "Anivexa Show"
        val posterUrl = doc.selectFirst(".poster img, img")?.attr("src")
        val plot = doc.selectFirst(".synopsis, .description, p")?.text()
        
        val animeId = Regex("""id=(\d+)""").find(url)?.groupValues?.get(1) 
            ?: url.substringAfter("id=").substringBefore("&")

        val subEpisodes = mutableListOf<Episode>()
        val dubEpisodes = mutableListOf<Episode>()

        val episodeElements = doc.select(".episodes a, li a")
        if (episodeElements.isNotEmpty()) {
            episodeElements.forEach { el ->
                val epNum = el.text().replace(Regex("[^0-9]"), "").toIntOrNull() ?: 1
                
                subEpisodes.add(newEpisode("$animeId|$epNum|sub") {
                    this.episode = epNum
                    this.name = "Episode $epNum (Sub)"
                })
                dubEpisodes.add(newEpisode("$animeId|$epNum|dub") {
                    this.episode = epNum
                    this.name = "Episode $epNum (Dub)"
                })
            }
        } else {
            for (i in 1..24) {
                subEpisodes.add(newEpisode("$animeId|$i|sub") {
                    this.episode = i
                    this.name = "Episode $i (Sub)"
                })
                dubEpisodes.add(newEpisode("$animeId|$i|dub") {
                    this.episode = i
                    this.name = "Episode $i (Dub)"
                })
            }
        }

        val typeText = doc.selectFirst(".meta, .type")?.text() ?: ""
        val tvType = if (typeText.contains("Movie", ignoreCase = true)) TvType.AnimeMovie else TvType.Anime

        return newAnimeLoadResponse(title, url, tvType) {
            this.posterUrl = posterUrl
            this.plot = plot
            if (subEpisodes.isNotEmpty()) addEpisodes(DubStatus.Subbed, subEpisodes)
            if (dubEpisodes.isNotEmpty()) addEpisodes(DubStatus.Dubbed, dubEpisodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split("|")
        if (parts.size < 3) return false
        
        val animeId = parts[0]
        val epNum = parts[1]
        val audioType = parts[2]

        val watchTargetUrl = "$mainUrl/anime.html?id=$animeId&audio=$audioType&ep=$epNum"
        val response = app.get(watchTargetUrl)
        val doc = response.document
        val html = response.text

        var linksHarvested = false

        val playerIframe = doc.selectFirst("iframe")?.attr("src")
        if (!playerIframe.isNullOrBlank()) {
            val iframeUrl = if (playerIframe.startsWith("http")) playerIframe 
                            else if (playerIframe.startsWith("//")) "https:$playerIframe" 
                            else "$mainUrl/${playerIframe.removePrefix("/")}"
            loadExtractor(iframeUrl, watchTargetUrl, subtitleCallback, callback)
            linksHarvested = true
        }

        val m3u8Regex = Regex("""(?:file|src|url)\s*[:=]\s*["'](https?://[^"']*\.m3u8[^"']*)["']""")
        m3u8Regex.findAll(html).forEach { match ->
            val rawStreamUrl = match.groupValues[1]
            callback(
                newExtractorLink(
                    source = "Anivexa Engine",
                    name = "Anivexa Server ($audioType)",
                    url = rawStreamUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = "$mainUrl/"
                    this.headers = mapOf("Origin" to mainUrl)
                }
            )
            linksHarvested = true
        }

        return linksHarvested
    }
}
