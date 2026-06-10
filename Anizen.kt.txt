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
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

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
            ?: html.findJsonString("title")
            ?: Regex("""<title>Watch\s+(.+?)\s+Anime Online""").find(document.toString())?.groupValues?.get(1)
            ?: throw ErrorLoadingException("Unable to find title")

        val poster = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))
            ?: html.findJsonString("cover")
        val description = document.selectFirst("meta[name=description]")?.attr("content")
            ?: html.findJsonString("description")
        val genres = Regex("""\\"genres\\":\[(.*?)]""").find(html)?.groupValues?.get(1)
            ?.let { Regex("""\\"([^"\\]+)\\"""").findAll(it).map { tag -> tag.groupValues[1].unescape() }.toList() }
            ?: emptyList()
        val year = html.findJsonString("premiered")?.let { Regex("""\d{4}""").find(it)?.value?.toIntOrNull() }
        val dataId = html.findJsonString("dataId")
            ?: url.substringAfterLast("-")
        val totalEpisodes = html.findJsonInt("totalEpisodes")
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")
                ?.let { Regex("""\((\d+)\s+episodes""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
            ?: 1
        val tvType = if (totalEpisodes <= 1 || url.contains("movie", ignoreCase = true)) TvType.AnimeMovie else TvType.Anime

        val recommendations = emptyList<SearchResponse>()

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

        var loadedLinks = false
        val wrappedCallback: (ExtractorLink) -> Unit = { link ->
            loadedLinks = true
            callback(link)
        }

        response.servers.sortedBy { it.priority() }.forEach { server ->
            val embed = server.embed?.takeIf { it.isNotBlank() } ?: server.iframeUrl?.takeIf { it.isNotBlank() }
            if (embed != null) {
                if (loadedLinks && server.priority() >= 5) return@forEach
                val sourceName = listOf(server.serverName, server.type.uppercase())
                    .filter { it.isNotBlank() }
                    .distinct()
                    .joinToString(" ")
                when {
                    embed.contains("ryzex.top") -> {
                        AnizenRyzex().apply { name = sourceName }.getUrl(embed, mainUrl, subtitleCallback, wrappedCallback)
                    }
                    embed.contains("abyssplayer.com") || embed.contains("abyss.to") -> {
                        AnizenAbyss().apply { name = sourceName }.getUrl(embed, mainUrl, subtitleCallback, wrappedCallback)
                    }
                    embed.contains("megaplay.buzz") -> AnizenMegaPlay(sourceName).getUrl(embed, mainUrl, subtitleCallback, wrappedCallback)
                    embed.contains("vidwish.live") -> AnizenVidWish(sourceName).getUrl(embed, mainUrl, subtitleCallback, wrappedCallback)
                    embed.contains("playerp2p.live") || embed.contains("gdmirrorbot.") || embed.contains("boosterx.") -> {
                        AnizenWebView(sourceName, embed.baseUrl()).getUrl(embed, mainUrl, subtitleCallback, wrappedCallback)
                    }
                    else -> loadExtractor(embed, mainUrl, subtitleCallback, wrappedCallback)
                }
            }
        }
        return loadedLinks
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
                val rawDataId = split.getOrNull(0)?.trim()?.takeIf { it.isNotBlank() } ?: return null
                val cleanDataId = rawDataId
                    .substringBefore("?")
                    .substringBefore("#")
                    .substringAfterLast("/")
                    .let { path ->
                        if (rawDataId.contains("/watch/")) path.substringAfterLast("-") else path
                    }
                    .takeIf { it.isNotBlank() }
                    ?: return null
                return EpisodeData(cleanDataId, split.getOrNull(1)?.toIntOrNull() ?: 1)
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

    private fun AniServer.priority(): Int {
        val key = "${serverName.lowercase()} ${type.lowercase()} ${embed.orEmpty().lowercase()} ${iframeUrl.orEmpty().lowercase()}"
        return when {
            key.contains("vidstack") -> 0
            key.contains("playerp2p") || key.contains("streamp2p") -> 0
            key.contains("megaplay") || key.contains("vidstream") -> 1
            key.contains("vidwish") || key.contains("vidcloud") -> 2
            key.contains("ryzex") || key.contains("abyss") -> 3
            key.contains("boosterx") || key.contains("playerx") -> 4
            key.contains("gdmirror") || key.contains("mirror") || key.contains("default") -> 5
            else -> 6
        }
    }

    private fun String.baseUrl(): String {
        return Regex("""https?://[^/]+""").find(this)?.value ?: mainUrl
    }

    private fun String.unescape(): String {
        return replace("\\\"", "\"")
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&quot;", "\"")
            .replace("&amp;", "&")
    }

    private fun String.findJsonString(key: String): String? {
        return Regex("""\\"$key\\":\\"([^"\\]*)"""").find(this)?.groupValues?.get(1)?.unescape()
            ?: Regex(""""$key"\s*:\s*"([^"]*)"""").find(this)?.groupValues?.get(1)?.unescape()
    }

    private fun String.findJsonInt(key: String): Int? {
        return Regex("""\\"$key\\":(\d+)""").find(this)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("""\\"$key\\":\\"(\d+)"""").find(this)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex(""""$key"\s*:\s*(\d+)""").find(this)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex(""""$key"\s*:\s*"(\d+)"""").find(this)?.groupValues?.get(1)?.toIntOrNull()
    }

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Referer" to "$mainUrl/"
    )

    class ErrorLoadingException(message: String) : RuntimeException(message)
}
