package com.laddu100

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.newSubtitleFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URLEncoder
import kotlin.coroutines.resume

class Animo : MainAPI() {
    override var mainUrl = "https://4animo.xyz"
    override var name = "Animo"
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private val apiUrl = "https://api.kryzox.xyz"
    private val cdnUrl = "https://cdn.4animo.xyz"

    private val ua = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    private val apiHeaders = mapOf(
        "User-Agent" to ua,
        "Accept" to "application/json, text/plain, */*",
        "Accept-Language" to "en-US,en;q=0.5",
        "Referer" to "$mainUrl/",
        "Origin" to mainUrl
    )

    override val mainPage = mainPageOf(
        Pair("trending", "Trending"),
        Pair("recently-updated", "Recently Updated"),
        Pair("recently-added", "Recently Added"),
        Pair("top", "Top Rated"),
        Pair("movie", "Movies"),
        Pair("tv", "TV Series"),
        Pair("ova", "OVA"),
        Pair("ona", "ONA"),
        Pair("special", "Specials"),
        Pair("completed", "Completed")
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$apiUrl/anime/${request.data}?page=$page&limit=20"
        return try {
            val items = parseAnimeList(app.get(url, headers = apiHeaders).text)
            val home = items.mapNotNull { it.toSearchResponse() }
            newHomePageResponse(request.name, home, hasNext = home.size == 20)
        } catch (e: Exception) {
            newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
    }

    private fun parseAnimeList(text: String): List<AnimeSearchItem> {
        return try {
            val trimmed = text.trim()
            if (trimmed.startsWith("[")) {
                parseJson<List<AnimeSearchItem>>(text)
            } else {
                parseJson<SearchResponseData>(text).data ?: emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "$apiUrl/anime/search?keyword=$encoded&page=1&limit=20"
            val resp = parseJson<SearchResponseData>(app.get(url, headers = apiHeaders).text)
            resp.data?.mapNotNull { it.toSearchResponse() } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val animeId = url.substringAfterLast("/").toIntOrNull() ?: return null

        val anime = try {
            parseJson<AnimeDetails>(app.get("$apiUrl/anime/$animeId", headers = apiHeaders).text)
        } catch (e: Exception) {
            return null
        }
        val title = anime.titles?.english ?: anime.titles?.romaji ?: return null

        val episodes = try {
            parseJson<EpisodesResponse>(app.get("$apiUrl/anime/$animeId/episodes", headers = apiHeaders).text).data ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }

        val subEps = mutableListOf<Episode>()
        val dubEps = mutableListOf<Episode>()

        episodes.forEach { ep ->
            val num = ep.number ?: return@forEach
            val epId = ep.id ?: return@forEach
            val epName = ep.titles?.en ?: ep.titles?.romaji ?: "Episode $num"
            if (ep.sub == true) {
                subEps.add(newEpisode(EpisodeData(animeId, epId, ep.embed_id, num, anime.slug ?: "", "sub").toJson()) {
                    this.episode = num
                    this.name = epName
                    this.posterUrl = ep.thumbnail
                })
            }
            if (ep.dub == true) {
                dubEps.add(newEpisode(EpisodeData(animeId, epId, ep.embed_id, num, anime.slug ?: "", "dub").toJson()) {
                    this.episode = num
                    this.name = epName
                    this.posterUrl = ep.thumbnail
                })
            }
        }

        val tvType = when (anime.type?.uppercase()) {
            "MOVIE" -> TvType.AnimeMovie
            "OVA", "ONA", "SPECIAL" -> TvType.OVA
            else -> TvType.Anime
        }
        val year = anime.air?.start?.substringBefore("-")?.toIntOrNull()
        val finalType = if (tvType == TvType.AnimeMovie && dubEps.isNotEmpty()) TvType.Anime else tvType

        return newAnimeLoadResponse(title, url, finalType) {
            this.posterUrl = anime.images?.poster
            this.plot = anime.synopsis
            this.year = year
            this.tags = anime.genres
            if (anime.score != null) this.score = Score.from10(anime.score.toString())
            if (subEps.isNotEmpty()) addEpisodes(DubStatus.Subbed, subEps)
            if (dubEps.isNotEmpty()) addEpisodes(DubStatus.Dubbed, dubEps)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val epData = try {
            parseJson<EpisodeData>(data)
        } catch (e: Exception) {
            return false
        }

        val playHeaders = mapOf(
            "Referer" to "$mainUrl/",
            "Origin" to mainUrl,
            "User-Agent" to ua
        )

        var found = false
        val typesToTry = if (epData.streamType == "dub") listOf("dub", "sub") else listOf("sub", "dub")
        for (type in typesToTry) {
            var subsPassedForType = false
            for (hd in 1..4) {
                val sourcesUrl = "$cdnUrl/stream/getSources?hd=$hd&id=${epData.animeId}&episode=${epData.episodeNum}&type=$type"
                try {
                    val response = app.get(
                        sourcesUrl,
                        headers = mapOf(
                            "User-Agent" to ua,
                            "Accept" to "application/json, text/plain, */*",
                            "Referer" to "$cdnUrl/embed/hd-$hd/${epData.animeId}/${epData.episodeNum}/$type",
                            "Origin" to cdnUrl
                        ),
                        timeout = 30_000L
                    )
                    if (response.code != 200) continue
                    val text = response.text
                    if (text.contains("Just a moment") || text.contains("cloudflare")) continue

                    val sources = parseJson<GetSourcesResponse>(text)

                    sources.sources?.forEach { s ->
                        val file = s.file ?: return@forEach
                        val streamUrl = if (file.startsWith("http")) file else "$cdnUrl/${file.removePrefix("/")}"
                        val label = "$name HD$hd ($type)"
                        if (s.type == "hls" || streamUrl.contains(".m3u8")) {
                            try {
                                M3u8Helper.generateM3u8(label, streamUrl, "$cdnUrl/", headers = playHeaders).forEach(callback)
                            } catch (e: Exception) {
                                callback.invoke(
                                    newExtractorLink(label, label, streamUrl, type = ExtractorLinkType.M3U8) {
                                        this.referer = "$cdnUrl/"
                                        this.headers = playHeaders
                                    }
                                )
                            }
                            found = true
                        } else {
                            callback.invoke(
                                newExtractorLink(label, label, streamUrl, type = INFER_TYPE) {
                                    this.referer = "$cdnUrl/"
                                    this.headers = playHeaders
                                }
                            )
                            found = true
                        }
                    }

                    if (!subsPassedForType && !sources.tracks.isNullOrEmpty()) {
                        sources.tracks.forEach { t ->
                            val file = t.file ?: return@forEach
                            val subUrl = if (file.startsWith("http")) file else "$cdnUrl/${file.removePrefix("/")}"
                            subtitleCallback.invoke(newSubtitleFile(t.label ?: "English", subUrl) {
                                this.headers = playHeaders
                            })
                        }
                        subsPassedForType = true
                    }
                } catch (e: Exception) {
                }
            }
            if (found) break
        }

        if (!found) {
            val watchUrl = if (epData.slug.isNotBlank()) {
                "$mainUrl/watch/${epData.slug}?ep=${epData.episodeNum}"
            } else {
                "$mainUrl/embed/${epData.embedId}"
            }
            val streamUrl = withTimeoutOrNull(30_000L) {
                extractStreamFromWebView(watchUrl)
            }
            if (streamUrl != null && streamUrl.isNotEmpty()) {
                if (streamUrl.contains(".m3u8")) {
                    try {
                        M3u8Helper.generateM3u8(name, streamUrl, "$cdnUrl/", headers = playHeaders).forEach(callback)
                    } catch (e: Exception) {
                        callback.invoke(
                            newExtractorLink(name, name, streamUrl, type = ExtractorLinkType.M3U8) {
                                this.referer = "$cdnUrl/"
                                this.headers = playHeaders
                            }
                        )
                    }
                    found = true
                } else {
                    callback.invoke(
                        newExtractorLink(name, name, streamUrl, type = INFER_TYPE) {
                            this.referer = "$cdnUrl/"
                            this.headers = playHeaders
                        }
                    )
                    found = true
                }
            }
        }

        return found
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun extractStreamFromWebView(url: String): String? = withContext(Dispatchers.Main) {
        val context = com.lagradost.cloudstream3.CommonActivity.activity ?: return@withContext null
        suspendCancellableCoroutine { cont ->
            var foundUrl: String? = null
            val webView = WebView(context)
            try {
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
                webView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    userAgentString = ua
                }
                webView.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = false

                    override fun onLoadResource(view: WebView?, resourceUrl: String?) {
                        super.onLoadResource(view, resourceUrl)
                        if (resourceUrl != null && foundUrl == null) {
                            if (resourceUrl.contains(".m3u8") || resourceUrl.contains(".mp4")) {
                                foundUrl = resourceUrl
                                if (cont.isActive) cont.resume(resourceUrl)
                            }
                        }
                    }
                }
                webView.loadUrl(url)
            } catch (e: Exception) {
                if (cont.isActive) cont.resume(null)
            }
            cont.invokeOnCancellation {
                try { webView.destroy() } catch (_: Exception) {}
            }
        }
    }

    private fun AnimeSearchItem.toSearchResponse(): SearchResponse? {
        val id = id ?: return null
        val title = titles?.english ?: titles?.romaji ?: return null
        return newAnimeSearchResponse(title, "$mainUrl/anime/$id", TvType.Anime) {
            this.posterUrl = images?.poster
            addDubStatus(dubExist = (dub_count ?: 0) > 0, subExist = (sub_count ?: 0) > 0)
        }
    }

    data class EpisodeData(
        val animeId: Int,
        val episodeId: Int,
        val embedId: String?,
        val episodeNum: Int,
        val slug: String,
        val streamType: String
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AnimeSearchItem(
        val id: Int? = null,
        val slug: String? = null,
        val titles: Titles? = null,
        val images: Images? = null,
        val type: String? = null,
        val status: String? = null,
        val episodes_count: Int? = null,
        val sub_count: Int? = null,
        val dub_count: Int? = null,
        val score: Double? = null,
        val season_year: Int? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SearchResponseData(
        val success: Boolean? = null,
        val data: List<AnimeSearchItem>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Images(
        val poster: String? = null,
        val banner: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Titles(
        val romaji: String? = null,
        val english: String? = null,
        val native: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AnimeDetails(
        val id: Int? = null,
        val slug: String? = null,
        val titles: Titles? = null,
        val synopsis: String? = null,
        val images: Images? = null,
        val type: String? = null,
        val status: String? = null,
        val score: Double? = null,
        val rating: String? = null,
        val air: Air? = null,
        val genres: List<String>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Air(
        val start: String? = null,
        val end: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EpisodesResponse(
        val anime_id: Int? = null,
        val total: Int? = null,
        val sub_count: String? = null,
        val dub_count: String? = null,
        val data: List<EpisodeItem>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EpisodeItem(
        val id: Int? = null,
        val number: Int? = null,
        val titles: EpisodeTitles? = null,
        val filler: Boolean? = null,
        val rating: String? = null,
        val thumbnail: String? = null,
        val sub: Boolean? = null,
        val dub: Boolean? = null,
        @JsonProperty("embed_id") val embed_id: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EpisodeTitles(
        val en: String? = null,
        val ja: String? = null,
        val romaji: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class GetSourcesResponse(
        val sources: List<MegaSource>? = null,
        val tracks: List<MegaTrack>? = null,
        val encrypted: Boolean? = null,
        val server: Int? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MegaSource(
        val file: String? = null,
        val type: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MegaTrack(
        val file: String? = null,
        val label: String? = null,
        val kind: String? = null,
        val default: Boolean? = null
    )
}
