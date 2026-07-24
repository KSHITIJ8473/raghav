package com.laddu100.netmirror

import android.content.Context
import com.laddu100.netmirror.entities.EpisodesData
import com.laddu100.netmirror.entities.PlayList
import com.laddu100.netmirror.entities.PostData
import com.laddu100.netmirror.entities.SearchData
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.APIHolder.unixTime
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Base provider for all four netmirror OTT sections (Netflix, PrimeVideo,
 * Hotstar, Disney). Each concrete provider picks:
 *   - browseOtt:     the `ott` cookie value for browse/search/load (nf, pv, hs, dp)
 *   - loadLinksOtt:  the `ott` cookie value for playlist.php (nf, pv, hs, hs-for-disney)
 *   - urlPrefix:     the URL path prefix for all endpoints ("" for Netflix, "/hs" for
 *                    Hotstar/Disney, "/pv" for PrimeVideo)
 *   - studio:        optional `studio` cookie for browse (disney only)
 *
 * Site structure (all on https://net52.cc):
 *   - Home:    /mobile/home?app=1                                    (HTML)
 *   - Search:  /mobile<urlPrefix>/search.php?s=<q>&t=<unixTime>      (JSON)
 *   - Post:    /mobile<urlPrefix>/post.php?id=<id>&t=<unixTime>      (JSON)
 *   - Episodes:/mobile<urlPrefix>/episodes.php?s=<sid>&series=<eid>&t=<unixTime>&page=<n>  (JSON)
 *   - Playlist:/mobile<urlPrefix>/playlist.php?id=<id>&t=<title>&tm=<unixTime>  (JSON → PlayList)
 *
 * The t_hash_t cookie is acquired once via [bypass] and cached for 15 hours.
 */
abstract class NetMirrorBaseProvider(
    private val browseOtt: String,
    private val loadLinksOtt: String,
    private val urlPrefix: String,
    displayName: String,
    private val studio: String? = null
) : MainAPI() {

    companion object {
        var context: Context? = null
    }

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama
    )
    override var lang = "ta"

    override var mainUrl = "https://net52.cc"
    override var name = displayName

    override val hasMainPage = true

    @Volatile
    private var cookie_value: String = ""

    private val browseHeaders = baseBrowseHeaders

    /** Headers for the playlist.php request (different from browse headers). */
    private val playlistHeaders: Map<String, String> by lazy {
        val cookieStr = buildCookieString(browseOtt)
        mapOf(
            "Accept" to "*/*",
            "Accept-Language" to "en-IN,en-US;q=0.9,en;q=0.8",
            "Connection" to "keep-alive",
            "Cookie" to cookieStr,
            "Referer" to "$mainUrl/mobile/home?app=1",
            "sec-ch-ua" to "\"Android WebView\";v=\"149\", \"Chromium\";v=\"149\", \"Not)A;Brand\";v=\"24\"",
            "sec-ch-ua-mobile" to "?0",
            "sec-ch-ua-platform" to "\"Android\"",
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "same-origin",
            "User-Agent" to "Mozilla/5.0 (Linux; Android 13; Pixel 5 Build/TQ3A.230901.001; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/149.0.7827.91 Safari/537.36 /OS.Gatu v3.0",
            "X-Requested-With" to "app.netmirror.netmirrornew"
        )
    }

    /** Cookies sent with browse/search/load requests. */
    private fun buildBrowseCookies(): Map<String, String> {
        val map = mutableMapOf(
            "t_hash_t" to cookie_value,
            "ott" to browseOtt,
            "hd" to "on"
        )
        if (studio != null) map["studio"] = studio
        return map
    }

    /** Cookie string for the playlist.php request (uses loadLinksOtt, no studio). */
    private fun buildCookieString(ott: String): String {
        val parts = mutableListOf(
            "t_hash_t=$cookie_value",
            "ott=$ott",
            "hd=on"
        )
        return parts.joinToString("; ")
    }

    /** Ensure we have a valid t_hash_t cookie, fetching one if needed. */
    private suspend fun ensureCookie(): Boolean {
        if (cookie_value.isEmpty()) {
            cookie_value = bypass(mainUrl)
        }
        return cookie_value.isNotEmpty()
    }

    // ---------- Home page ----------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        if (!ensureCookie()) return null
        val document = app.get(
            "$mainUrl/mobile/home?app=1",
            cookies = buildBrowseCookies(),
            headers = browseHeaders,
            referer = "$mainUrl/mobile/home?app=1"
        ).document
        val items = document.select(".tray-container, #top10").map { it.toHomePageList() }
        return newHomePageResponse(items, false)
    }

    private fun Element.toHomePageList(): HomePageList {
        val listName = select("h2, span").text().ifBlank { "Home" }
        val items = select("article, .top10-post").mapNotNull { it.toSearchResult() }
        return HomePageList(listName, items, isHorizontalImages = false)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val id = selectFirst("a")?.attr("data-post") ?: attr("data-post")
        if (id.isBlank()) return null
        return newAnimeSearchResponse("", Id(id).toJson()) {
            posterUrl = "https://imgcdn.kim/poster/v/$id.jpg"
            posterHeaders = mapOf("Referer" to "$mainUrl/home")
        }
    }

    // ---------- Search ----------

    override suspend fun search(query: String): List<SearchResponse> {
        if (!ensureCookie()) return emptyList()
        val url = "$mainUrl/mobile$urlPrefix/search.php?s=$query&t=${unixTime}"
        val data = try {
            app.get(url, referer = "$mainUrl/home", cookies = buildBrowseCookies(), headers = browseHeaders)
                .parsed<SearchData>()
        } catch (e: Exception) {
            Log.e(name, "search: failed: ${e.message}")
            return emptyList()
        }
        return data.searchResult.map {
            newAnimeSearchResponse(it.t, Id(it.id).toJson()) {
                posterUrl = "https://imgcdn.kim/poster/v/${it.id}.jpg"
                posterHeaders = mapOf("Referer" to "$mainUrl/home")
            }
        }
    }

    // ---------- Load (detail page) ----------

    override suspend fun load(url: String): LoadResponse? {
        if (!ensureCookie()) return null
        val id = parseJson<Id>(url).id
        val data = app.get(
            "$mainUrl/mobile$urlPrefix/post.php?id=$id&t=${unixTime}",
            headers = browseHeaders,
            referer = "$mainUrl/home",
            cookies = buildBrowseCookies()
        ).parsed<PostData>()

        val episodes = arrayListOf<Episode>()
        val title = data.title
        val castList = data.cast?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        val cast = castList.map { ActorData(Actor(it)) }
        val genre = data.genre?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
        val rating = data.match?.replace("IMDb ", "")
        val runTime = convertRuntimeToMinutes(data.runtime.toString())

        val suggest = data.suggest?.map {
            newAnimeSearchResponse("", Id(it.id).toJson()) {
                posterUrl = "https://imgcdn.kim/poster/v/${it.id}.jpg"
                posterHeaders = mapOf("Referer" to "$mainUrl/home")
            }
        }

        if (data.episodes.firstOrNull() == null) {
            episodes.add(newEpisode(LoadData(title, id)) { name = data.title })
        } else {
            data.episodes.filterNotNull().mapTo(episodes) {
                newEpisode(LoadData(title, it.id)) {
                    this.name = it.t
                    this.episode = it.ep.replace("E", "").toIntOrNull()
                    this.season = it.s.replace("S", "").toIntOrNull()
                    this.posterUrl = "https://imgcdn.kim/poster/v/150/${it.id}.jpg"
                    this.runTime = it.time.replace("m", "").toIntOrNull()
                }
            }
            if (data.nextPageShow == 1) {
                episodes.addAll(getEpisodes(title, id, data.nextPageSeason!!, 2))
            }
            data.season?.dropLast(1)?.amap {
                episodes.addAll(getEpisodes(title, id, it.id, 1))
            }
        }

        val type = if (data.episodes.firstOrNull() == null) TvType.Movie else TvType.TvSeries
        return newTvSeriesLoadResponse(title, url, type, episodes) {
            posterUrl = "https://imgcdn.kim/poster/v/$id.jpg"
            backgroundPosterUrl = "https://imgcdn.kim/poster/v/$id.jpg"
            posterHeaders = mapOf("Referer" to "$mainUrl/home")
            plot = data.desc
            year = data.year.toIntOrNull()
            tags = genre
            actors = cast
            this.score = Score.from10(rating)
            this.duration = runTime
            this.contentRating = data.ua
            this.recommendations = suggest
        }
    }

    private suspend fun getEpisodes(title: String, eid: String, sid: String, page: Int): List<Episode> {
        val episodes = arrayListOf<Episode>()
        var pg = page
        while (true) {
            val data = try {
                app.get(
                    "$mainUrl/mobile$urlPrefix/episodes.php?s=$sid&series=$eid&t=${unixTime}&page=$pg",
                    headers = browseHeaders,
                    referer = "$mainUrl/home",
                    cookies = buildBrowseCookies()
                ).parsed<EpisodesData>()
            } catch (e: Exception) {
                break
            }
            data.episodes?.filterNotNull()?.mapTo(episodes) {
                newEpisode(LoadData(title, it.id)) {
                    name = it.t
                    episode = it.ep.replace("E", "").toIntOrNull()
                    season = it.s.replace("S", "").toIntOrNull()
                    this.posterUrl = "https://imgcdn.kim/epimg/150/${it.id}.jpg"
                    this.runTime = it.time.replace("m", "").toIntOrNull()
                }
            }
            if (data.nextPageShow == 0) break
            pg++
        }
        return episodes
    }

    // ---------- loadLinks ----------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val loadData = parseJson<LoadData>(data)
        val id = loadData.id
        val title = loadData.title
        Log.d(name, "loadLinks: id=$id title=$title ott=$loadLinksOtt prefix=$urlPrefix")
        if (!ensureCookie()) return false

        // Build playlist.php URL: /mobile<urlPrefix>/playlist.php?id=<id>&t=<title>&tm=<unixTime>
        val playlistUrl = "$mainUrl/mobile$urlPrefix/playlist.php?id=$id&t=$title&tm=${unixTime}"
        Log.d(name, "loadLinks: playlistUrl=$playlistUrl")

        // Fetch with playlist-specific headers (Cookie is in the headers, not the cookies param).
        // The cookie string uses loadLinksOtt (for Disney, this is "hs" not "dp").
        val cookieStr = buildCookieString(loadLinksOtt)
        val headers = playlistHeaders.toMutableMap().apply {
            this["Cookie"] = cookieStr
        }

        val playlist = try {
            app.get(playlistUrl, headers = headers).parsed<PlayList>()
        } catch (e: Exception) {
            Log.e(name, "loadLinks: playlist.php failed: ${e.message}")
            return false
        }

        Log.d(name, "loadLinks: playlist items=${playlist.size}")

        for (item in playlist) {
            // Extract sources (stream URLs)
            for (source in item.sources) {
                val streamUrl = if (source.file.startsWith("http")) {
                    source.file
                } else {
                    "$mainUrl${source.file}"
                }
                val quality = try {
                    getQualityFromName(source.file.substringAfter("q=", ""))
                } catch (_: Exception) {
                    Qualities.Unknown.value
                }
                Log.d(name, "loadLinks: source label=${source.label} type=${source.type} url=$streamUrl")

                callback.invoke(
                    ExtractorLink(
                        source = name,
                        name = source.label.ifBlank { name },
                        url = streamUrl,
                        referer = "$mainUrl/mobile/home?app=1",
                        quality = quality,
                        type = ExtractorLinkType.M3U8,
                        headers = headers
                    )
                )
            }

            // Extract subtitles (tracks with kind="captions")
            item.tracks?.forEach { track ->
                if (track.kind == "captions" && !track.file.isNullOrEmpty()) {
                    val subUrl = if (track.file.startsWith("http")) {
                        track.file
                    } else {
                        "$mainUrl${track.file}"
                    }
                    subtitleCallback.invoke(
                        newSubtitleFile(track.label ?: "Subtitles", subUrl) {}
                    )
                }
            }
        }
        return true
    }

    /**
     * Intercepts m3u8 requests so they carry the `hd=on` cookie.
     */
    @Suppress("ObjectLiteralToLambda")
    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? {
        return object : Interceptor {
            override fun intercept(chain: Interceptor.Chain): Response {
                val request = chain.request()
                if (request.url.toString().contains(".m3u8")) {
                    val newRequest = request.newBuilder()
                        .header("Cookie", "hd=on")
                        .build()
                    return chain.proceed(newRequest)
                }
                return chain.proceed(request)
            }
        }
    }

    data class Id(val id: String)
    data class LoadData(val title: String, val id: String)
}
