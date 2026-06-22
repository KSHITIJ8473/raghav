package com.anidoor

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.net.URLEncoder

class AniDoorProvider : MainAPI() {
    override var mainUrl = "https://anidoor.me"
    override var name = "AniDoor"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)
    override var lang = "en"
    override val hasMainPage = true

    private val mapper = ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    override val mainPage = mainPageOf(
        "trending" to "Trending Now",
        "popular" to "All Time Popular",
        "top" to "Top Rated"
    )

    private suspend fun queryAniList(query: String, variables: Map<String, Any>): String? {
        val body = mapOf(
            "query" to query,
            "variables" to variables
        )
        val res = app.post(
            "https://graphql.anilist.co",
            headers = mapOf("Content-Type" to "application/json"),
            json = body
        )
        return if (res.code == 200) res.text else null
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val sort = when (request.data) {
            "trending" -> "TRENDING_DESC"
            "popular" -> "POPULAR_DESC"
            "top" -> "SCORE_DESC"
            else -> "TRENDING_DESC"
        }
        
        val graphQuery = """
            query(${"$"}sort: [MediaSort], ${"$"}page: Int) {
              Page(page: ${"$"}page, perPage: 20) {
                media(type: ANIME, sort: ${"$"}sort, isAdult: false) {
                  id
                  title {
                    romaji
                    english
                  }
                  coverImage {
                    large
                  }
                  format
                }
              }
            }
        """.trimIndent()
        
        val vars = mapOf(
            "sort" to listOf(sort),
            "page" to page
        )
        
        val responseText = queryAniList(graphQuery, vars) ?: return newHomePageResponse(request.name, emptyList())
        val jsonNode = mapper.readTree(responseText)
        val mediaList = jsonNode.get("data")?.get("Page")?.get("media") ?: return newHomePageResponse(request.name, emptyList())
        
        val results = mutableListOf<SearchResponse>()
        if (mediaList.isArray) {
            for (media in mediaList) {
                val id = media.get("id")?.asInt() ?: continue
                val titleNode = media.get("title")
                val title = titleNode?.get("english")?.asText()?.takeIf { it.isNotBlank() }
                    ?: titleNode?.get("romaji")?.asText()
                    ?: "Anime"
                val poster = media.get("coverImage")?.get("large")?.asText()
                val format = media.get("format")?.asText() ?: ""
                val tvType = if (format == "MOVIE") TvType.AnimeMovie else TvType.Anime
                
                val linkUrl = "$mainUrl/watch/?al=$id"
                results.add(
                    newAnimeSearchResponse(title, linkUrl, tvType) {
                        this.posterUrl = poster
                    }
                )
            }
        }
        return newHomePageResponse(request.name, results)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val graphQuery = """
            query(${"$"}search: String, ${"$"}page: Int) {
              Page(page: ${"$"}page, perPage: 20) {
                media(type: ANIME, search: ${"$"}search, isAdult: false) {
                  id
                  title {
                    romaji
                    english
                  }
                  coverImage {
                    large
                  }
                  format
                }
              }
            }
        """.trimIndent()
        
        val vars = mapOf(
            "search" to query,
            "page" to 1
        )
        
        val responseText = queryAniList(graphQuery, vars) ?: return emptyList()
        val jsonNode = mapper.readTree(responseText)
        val mediaList = jsonNode.get("data")?.get("Page")?.get("media") ?: return emptyList()
        
        val results = mutableListOf<SearchResponse>()
        if (mediaList.isArray) {
            for (media in mediaList) {
                val id = media.get("id")?.asInt() ?: continue
                val titleNode = media.get("title")
                val title = titleNode?.get("english")?.asText()?.takeIf { it.isNotBlank() }
                    ?: titleNode?.get("romaji")?.asText()
                    ?: "Anime"
                val poster = media.get("coverImage")?.get("large")?.asText()
                val format = media.get("format")?.asText() ?: ""
                val tvType = if (format == "MOVIE") TvType.AnimeMovie else TvType.Anime
                
                val linkUrl = "$mainUrl/watch/?al=$id"
                results.add(
                    newAnimeSearchResponse(title, linkUrl, tvType) {
                        this.posterUrl = poster
                    }
                )
            }
        }
        return results
    }

    override suspend fun load(url: String): LoadResponse {
        val alId = Regex("""al=(\d+)""").find(url)?.groupValues?.get(1)?.toIntOrNull()
            ?: throw ErrorLoadingException("Invalid AniList ID")

        val graphQuery = """
            query(${"$"}id: Int) {
              Media(id: ${"$"}id, type: ANIME) {
                idMal
                title {
                  romaji
                  english
                }
                description
                coverImage {
                  extraLarge
                  large
                }
                episodes
                status
                genres
                seasonYear
                nextAiringEpisode {
                  episode
                }
              }
            }
        """.trimIndent()

        val vars = mapOf("id" to alId)
        val responseText = queryAniList(graphQuery, vars) ?: throw ErrorLoadingException("Failed to fetch data from AniList")
        val mediaNode = mapper.readTree(responseText).get("data")?.get("Media") ?: throw ErrorLoadingException("Anime not found")
        
        val malId = mediaNode.get("idMal")?.asInt()
        val titleNode = mediaNode.get("title")
        val title = titleNode?.get("english")?.asText()?.takeIf { it.isNotBlank() }
            ?: titleNode?.get("romaji")?.asText()
            ?: "Anime"
        val poster = mediaNode.get("coverImage")?.get("extraLarge")?.asText()
            ?: mediaNode.get("coverImage")?.get("large")?.asText()
        val synopsis = mediaNode.get("description")?.asText()?.replace(Regex("<[^>]*>"), "")
        val year = mediaNode.get("seasonYear")?.asInt()
        val genres = mediaNode.get("genres")?.mapNotNull { it.asText() } ?: emptyList()

        val status = when (mediaNode.get("status")?.asText()?.lowercase()) {
            "finished" -> ShowStatus.Completed
            "releasing" -> ShowStatus.Ongoing
            else -> null
        }

        val format = mediaNode.get("format")?.asText() ?: ""
        val tvType = if (format == "MOVIE") TvType.AnimeMovie else TvType.Anime

        val episodesList = mutableListOf<Episode>()
        
        if (tvType == TvType.AnimeMovie) {
            val subData = EpisodeData("sub", alId.toString(), malId?.toString() ?: "", 1).toDelimited()
            episodesList.add(newEpisode(subData) {
                this.name = title
                this.episode = 1
            })
            val dubData = EpisodeData("dub", alId.toString(), malId?.toString() ?: "", 1).toDelimited()
            episodesList.add(newEpisode(dubData) {
                this.name = title
                this.episode = 1
            })
        } else {
            val totalEps = mediaNode.get("episodes")?.asInt() ?: 1
            val nextAiringEpisodeNode = mediaNode.get("nextAiringEpisode")
            val nextEpNum = nextAiringEpisodeNode?.get("episode")?.asInt()
            val displayCount = if (nextEpNum != null) {
                maxOf(totalEps, nextEpNum - 1)
            } else {
                totalEps
            }

            for (i in 1..displayCount) {
                val subData = EpisodeData("sub", alId.toString(), malId?.toString() ?: "", i).toDelimited()
                episodesList.add(newEpisode(subData) {
                    this.name = "Episode $i"
                    this.episode = i
                })
                val dubData = EpisodeData("dub", alId.toString(), malId?.toString() ?: "", i).toDelimited()
                episodesList.add(newEpisode(dubData) {
                    this.name = "Episode $i"
                    this.episode = i
                })
            }
        }

        val subEpisodes = episodesList.filter { it.data.startsWith("sub|") }
        val dubEpisodes = episodesList.filter { it.data.startsWith("dub|") }

        return newAnimeLoadResponse(title, url, tvType) {
            this.posterUrl = poster
            this.plot = synopsis
            this.year = year
            this.tags = genres
            this.showStatus = status
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
        val episodeData = EpisodeData.fromDelimited(data) ?: return false
        val alId = episodeData.alId
        val malId = episodeData.malId.takeIf { it.isNotBlank() }
        val epNum = episodeData.epNum
        val requestedType = episodeData.type.lowercase()

        val suffix = if (requestedType == "dub") "dub" else "sub"
        var loadedLinks = false

        val wrappedCallback: (ExtractorLink) -> Unit = { link ->
            loadedLinks = true
            callback(link)
        }

        coroutineScope {
            val tasks = mutableListOf(
                // 1. TryEmbed
                async {
                    runCatching {
                        val tryEmbedUrl = "https://tryembed.us.cc/embed/anime/$alId/$epNum/$suffix"
                        TryEmbedExtractor().getUrl(tryEmbedUrl, "$mainUrl/", subtitleCallback, wrappedCallback)
                    }
                },
                // 2. MegaPlay AniList Route
                async {
                    runCatching {
                        val megaPlayAniUrl = "https://megaplay.buzz/stream/ani/$alId/$epNum/$suffix"
                        MegaPlayExtractor("MegaPlay").getUrl(megaPlayAniUrl, "$mainUrl/", subtitleCallback, wrappedCallback)
                    }
                }
            )

            // 3. MegaPlay MAL Route (if MAL ID exists)
            if (malId != null) {
                tasks.add(
                    async {
                        runCatching {
                            val megaPlayMalUrl = "https://megaplay.buzz/stream/mal/$malId/$epNum/$suffix"
                            MegaPlayExtractor("MegaPlay Alt").getUrl(megaPlayMalUrl, "$mainUrl/", subtitleCallback, wrappedCallback)
                        }
                    }
                )
            }

            tasks.awaitAll()
        }

        return loadedLinks
    }

    data class EpisodeData(
        val type: String,
        val alId: String,
        val malId: String,
        val epNum: Int
    ) {
        fun toDelimited(): String = "$type|$alId|$malId|$epNum"

        companion object {
            fun fromDelimited(str: String): EpisodeData? {
                val parts = str.split("|")
                if (parts.size < 4) {
                    if (parts.size >= 3) {
                        return EpisodeData(parts[0], parts[1], "", parts[2].toIntOrNull() ?: 1)
                    }
                    return null
                }
                return EpisodeData(parts[0], parts[1], parts[2], parts[3].toIntOrNull() ?: 1)
            }
        }
    }

    class ErrorLoadingException(message: String) : RuntimeException(message)
}
