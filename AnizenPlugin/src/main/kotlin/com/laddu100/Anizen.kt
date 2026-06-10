package com.laddu100

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.toNewSearchResponseList
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import java.net.URLEncoder

class Anizen : MainAPI() {
    override var mainUrl = "https://anizen.tr"
    override var name = "AniZen"
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    override val mainPage = mainPageOf(
        "home" to "Home",
        "az-page/all" to "A-Z Anime",
        "search?keyword=one%20piece" to "Popular",
        "search?keyword=hindi" to "Hindi",
        "search?keyword=dub" to "Dubbed"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val data = request.data
        val url = when {
            data.startsWith("search?") -> "$mainUrl/$data&page=$page"
            data.startsWith("az-page/") -> "$mainUrl/$data?page=$page"
            else -> "$mainUrl/$data"
        }
        val results = app.get(url, headers = headers).document.select("a[data-anime-id][data-data-id]")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
        return newHomePageResponse(request.name, results)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val document = app.get("$mainUrl/search?keyword=$encoded&page=$page", headers = headers).document
        val results = document.select("a[data-anime-id][data-data-id]")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
        return results.toNewSearchResponseList()
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = headers).document
        val html = document.html()
        val title = document.selectFirst("meta[property=og:title]")?.attr("content")
            ?.let { Regex("""Watch\s+(.+?)\s+Anime Online""").find(it)?.groupValues?.get(1) }
            ?: Regex("""\\"title\\":\\"([^"\\]+)"""").find(html)?.groupValues?.get(1)?.unescape()
            ?: Regex("""<title>Watch\s+(.+?)\s+Anime Online""").find(document.toString())?.groupValues?.get(1)
            ?: throw ErrorLoadingException("Unable to find title")

        val poster = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))
            ?: Regex("""\\"cover\\":\\"([^"\\]+)"""").find(html)?.groupValues?.get(1)?.unescape()
        val description = document.selectFirst("meta[name=description]")?.attr("content")
            ?: Regex("""\\"description\\":\\"([^"\\]*)"""").find(html)?.groupValues?.get(1)?.unescape()
        val genres = Regex("""\\"genres\\":\[(.*?)]""").find(html)?.groupValues?.get(1)
            ?.let { Regex("""\\"([^"\\]+)\\"""").findAll(it).map { tag -> tag.groupValues[1].unescape() }.toList() }
            ?: emptyList()
        val year = Regex("""\\"premiered\\":\\"[^0-9]*(\d{4})""").find(html)?.groupValues?.get(1)?.toIntOrNull()
        val dataId = Regex("""\\"dataId\\":\\"([^"\\]+)"""").find(html)?.groupValues?.get(1)?.unescape()
            ?: url.substringAfterLast("-")
        val totalEpisodes = Regex("""\\"totalEpisodes\\":(\d+)""").find(html)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("""\\"totalEpisodes\\":\\"(\d+)"""").find(html)?.groupValues?.get(1)?.toIntOrNull()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")
                ?.let { Regex("""\((\d+)\s+episodes""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
            ?: 1
        val tvType = if (totalEpisodes <= 1 || url.contains("movie", ignoreCase = true)) TvType.AnimeMovie else TvType.Anime

        val recommendations = Regex("""\\"recommended\\":\[(.*?)]}""").find(html)?.groupValues?.get(1)
            ?.let { Regex("""\{\\"id\\":\\"([^"\\]+).*?\\"title\\":\\"([^"\\]+).*?\\"cover\\":\\"([^"\\]+)""")
                .findAll(it)
                .map { match ->
                    newMovieSearchResponse(
                        match.groupValues[2].unescape(),
                        fixUrl("/watch/${match.groupValues[1].unescape()}"),
                        TvType.Anime
                    ) {
                        posterUrl = match.groupValues[3].unescape()
                    }
                }.toList()
            } ?: emptyList()

        return if (tvType == TvType.AnimeMovie) {
            newMovieLoadResponse(title, url, TvType.AnimeMovie, EpisodeData(dataId, 1).toString()) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.tags = genres
                this.recommendations = recommendations
            }
        } else {
            val episodes = fetchEpisodes(dataId, totalEpisodes)
            newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.tags = genres
                this.recommendations = recommendations
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val episodeData = EpisodeData.fromString(data) ?: return false
        val response = app.get(
            "$mainUrl/ajax/servers/${episodeData.dataId}?ep=${episodeData.episode}",
            headers = headers
        ).parsedSafe<ServerResponse>() ?: return false

        response.servers.forEach { server ->
            val embed = server.embed?.takeIf { it.isNotBlank() } ?: server.iframeUrl?.takeIf { it.isNotBlank() }
            if (embed != null) {
                val prefix = server.type.uppercase()
                loadExtractor(embed, mainUrl, subtitleCallback) { link ->
                    callback(link.copy(name = "[$prefix] ${server.serverName} - ${link.name}"))
                }
            }
        }
        return true
    }

    private suspend fun fetchEpisodes(dataId: String, fallbackCount: Int): List<Episode> {
        val response = app.get("$mainUrl/ajax/episodes/$dataId", headers = headers).parsedSafe<EpisodeResponse>()
        val episodes = response?.episodes?.mapNotNull { ep ->
            val no = ep.no ?: return@mapNotNull null
            newEpisode(EpisodeData(dataId, no).toString()) {
                this.name = ep.title ?: "Episode $no"
                this.episode = no
            }
        }.orEmpty()
        return episodes.ifEmpty {
            (1..fallbackCount).map { no ->
                newEpisode(EpisodeData(dataId, no).toString()) {
                    this.name = "Episode $no"
                    this.episode = no
                }
            }
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = fixUrl(attr("href").takeIf { it.isNotBlank() } ?: return null)
        val title = selectFirst("img[alt]")?.attr("alt")?.trim()
            ?: selectFirst("h3")?.text()?.trim()
            ?: return null
        val poster = fixUrlNull(selectFirst("img")?.attr("src"))
        val typeText = selectFirst("p span")?.text()?.lowercase().orEmpty()
        val tvType = if (typeText.contains("movie")) TvType.AnimeMovie else TvType.Anime
        return newMovieSearchResponse(title, href, tvType) {
            posterUrl = poster
        }
    }

    private data class EpisodeData(val dataId: String, val episode: Int) {
        override fun toString(): String = "$dataId|$episode"

        companion object {
            fun fromString(data: String): EpisodeData? {
                val split = data.split("|")
                return EpisodeData(split.getOrNull(0) ?: return null, split.getOrNull(1)?.toIntOrNull() ?: 1)
            }
        }
    }

    data class EpisodeResponse(
        val ok: Boolean? = null,
        val episodes: List<AniEpisode> = emptyList()
    )

    data class AniEpisode(
        val no: Int? = null,
        val title: String? = null,
        val episodeId: String? = null
    )

    data class ServerResponse(
        val ok: Boolean? = null,
        val servers: List<AniServer> = emptyList()
    )

    data class AniServer(
        val type: String = "sub",
        val serverName: String = "Server",
        val embed: String? = null,
        val iframeUrl: String? = null,
        @JsonProperty("streamKey") val streamKey: String? = null
    )

    private fun String.unescape(): String {
        return replace("\\\"", "\"")
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&quot;", "\"")
            .replace("&amp;", "&")
    }

    private fun String.toTitleCase(): String {
        return split(" ").joinToString(" ") { word ->
            word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }
    }

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Referer" to "$mainUrl/"
    )

    class ErrorLoadingException(message: String) : RuntimeException(message)
}
