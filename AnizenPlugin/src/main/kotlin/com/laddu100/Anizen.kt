package com.laddu100

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
            ?: document.title().let { Regex("""Watch\s+(.+?)\s+Anime Online""").find(it)?.groupValues?.get(1) ?: it.substringBefore(" | AniZen").removePrefix("Watch ") }
            ?: html.findJsonString("title")
            ?: Regex("""<title>Watch\s+(.+?)\s+Anime Online""").find(document.toString())?.groupValues?.get(1)
            ?: throw ErrorLoadingException("Unable to find title")

        val poster = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))
            ?: html.findJsonString("cover")
        val description = document.selectFirst("meta[name=description]")?.attr("content")
            ?: html.findJsonString("description")
        val genres = document.select("span")
            .firstOrNull { it.text().trim() == "Genres:" }
            ?.nextElementSibling()?.text()
            ?.split(",")?.map { it.trim() }
            ?: emptyList()
        val year = document.select("span")
            .firstOrNull { it.text().trim() in listOf("Premiered:", "Date aired:") }
            ?.nextElementSibling()?.text()
            ?.let { Regex("""\d{4}""").find(it)?.value?.toIntOrNull() }
        val dataId = document.selectFirst("div[data-data-id]")?.attr("data-data-id")?.takeIf { it.isNotBlank() }
            ?: url.substringBefore("?").substringBefore("#").removeSuffix("/").substringAfterLast("/")
        val totalEpisodes = document.select("span")
            .firstOrNull { it.text().trim() == "Episodes:" }
            ?.nextElementSibling()?.text()?.trim()?.toIntOrNull()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")
                ?.let { Regex("""\((\d+)\s+episodes""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
            ?: 1
        val tvType = if (totalEpisodes <= 1 || url.contains("movie", ignoreCase = true)) TvType.AnimeMovie else TvType.Anime

        val recommendations = emptyList<SearchResponse>()

        val episodes = if (tvType == TvType.AnimeMovie) {
            listOf(
                newEpisode(EpisodeData("movie", dataId, 1).toString()) {
                    this.name = title
                    this.episode = 1
                }
            )
        } else {
            fetchEpisodes(dataId, totalEpisodes)
        }

        val subEpisodes = episodes.map { ep ->
            val oldData = EpisodeData.fromString(ep.data)
            val newData = EpisodeData("sub", oldData?.dataId ?: dataId, ep.episode ?: 1).toString()
            newEpisode(newData) {
                this.name = ep.name
                this.episode = ep.episode
                this.description = ep.description
                this.posterUrl = ep.posterUrl
            }
        }

        val dubEpisodes = episodes.map { ep ->
            val oldData = EpisodeData.fromString(ep.data)
            val newData = EpisodeData("dub", oldData?.dataId ?: dataId, ep.episode ?: 1).toString()
            newEpisode(newData) {
                this.name = ep.name
                this.episode = ep.episode
                this.description = ep.description
                this.posterUrl = ep.posterUrl
            }
        }

        return newAnimeLoadResponse(title, url, tvType) {
            this.posterUrl = poster
            this.plot = description
            this.year = year
            this.tags = genres
            this.recommendations = recommendations
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
        val episodeData = EpisodeData.fromString(data) ?: return false
        val response = app.get(
            "$mainUrl/ajax/servers/${episodeData.dataId}?ep=${episodeData.episode}",
            headers = headers
        ).parsedSafe<ServerResponse>() ?: return false

        val requestedType = episodeData.type.lowercase()
        val filteredServers = response.servers.filter { server ->
            val serverType = server.type.lowercase()
            when (requestedType) {
                "sub" -> serverType == "sub" || serverType == "hindi"
                "dub" -> serverType == "dub" || serverType == "hindi"
                else -> true
            }
        }

        var loadedLinks = false
        val wrappedCallback: (ExtractorLink) -> Unit = { link ->
            loadedLinks = true
            callback(link)
        }

        coroutineScope {
            filteredServers.sortedBy { it.priority() }.map { server ->
                async {
                    runCatching {
                        val embed = server.embed?.takeIf { it.isNotBlank() } ?: server.iframeUrl?.takeIf { it.isNotBlank() }
                        if (embed != null) {
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
                                embed.contains("vidtube.site") -> AnizenVidTube(sourceName).getUrl(embed, mainUrl, subtitleCallback, wrappedCallback)
                                else -> {
                                    val loaded = loadExtractor(embed, mainUrl, subtitleCallback, wrappedCallback)
                                    if (!loaded) {
                                        AnizenWebView(sourceName, embed.baseUrl()).getUrl(embed, mainUrl, subtitleCallback, wrappedCallback)
                                    }
                                }
                            }
                        }
                    }
                }
            }.awaitAll()
        }
        return loadedLinks
    }

    private suspend fun fetchEpisodes(dataId: String, fallbackCount: Int): List<Episode> {
        val response = app.get("$mainUrl/ajax/episodes/$dataId", headers = headers).parsedSafe<EpisodeResponse>()
        val episodes = response?.episodes?.mapNotNull { ep ->
            val no = ep.no ?: return@mapNotNull null
            newEpisode(EpisodeData("all", dataId, no).toString()) {
                this.name = ep.title ?: "Episode $no"
                this.episode = no
            }
        }.orEmpty()
        return episodes.ifEmpty {
            (1..fallbackCount).map { no ->
                newEpisode(EpisodeData("all", dataId, no).toString()) {
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

    private data class EpisodeData(val type: String, val dataId: String, val episode: Int) {
        override fun toString(): String = "$type|$dataId|$episode"

        companion object {
            fun fromString(data: String): EpisodeData? {
                val split = data.split("|")
                if (split.size < 3) {
                    val rawDataId = split.getOrNull(0)?.trim()?.takeIf { it.isNotBlank() } ?: return null
                    val cleanDataId = rawDataId
                        .substringBefore("?")
                        .substringBefore("#")
                        .removeSuffix("/")
                        .substringAfterLast("/")
                        .takeIf { it.isNotBlank() }
                        ?: return null
                    return EpisodeData("all", cleanDataId, split.getOrNull(1)?.toIntOrNull() ?: 1)
                }
                val type = split[0]
                val rawDataId = split[1]
                val cleanDataId = rawDataId
                    .substringBefore("?")
                    .substringBefore("#")
                    .removeSuffix("/")
                    .substringAfterLast("/")
                    .takeIf { it.isNotBlank() }
                    ?: return null
                return EpisodeData(type, cleanDataId, split[2].toIntOrNull() ?: 1)
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
        if (type.lowercase() == "hindi") {
            return 10
        }
        val key = "${serverName.lowercase()} ${embed.orEmpty().lowercase()} ${iframeUrl.orEmpty().lowercase()}"
        return when {
            key.contains("megaplay") || key.contains("vidstream") -> 0
            key.contains("vidwish") || key.contains("vidcloud") -> 1
            key.contains("vidtube") || key.contains("vidplay") -> 2
            key.contains("ryzex") || key.contains("abyss") -> 3
            key.contains("gdmirror") || key.contains("mirror") || key.contains("default") -> 4
            key.contains("boosterx") || key.contains("playerx") -> 5
            key.contains("vidstack") || key.contains("playerp2p") || key.contains("streamp2p") -> 6
            else -> 7
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
