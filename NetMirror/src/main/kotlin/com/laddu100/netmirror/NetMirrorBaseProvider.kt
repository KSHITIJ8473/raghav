package com.laddu100.netmirror

import android.content.Context
import com.laddu100.netmirror.entities.EpisodesData
import com.laddu100.netmirror.entities.PostData
import com.laddu100.netmirror.entities.SearchData
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.APIHolder.unixTime
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Base provider for all four netmirror OTT sections (Netflix, PrimeVideo,
 * Hotstar, Disney). Each concrete provider just picks an `ott` cookie value
 * and a display name.
 *
 * Site structure (all on https://net52.cc):
 *   - Home:  /mobile/home?app=1            (HTML, parsed with Jsoup)
 *   - Search:/mobile/search.php?s=<q>&t=<unixTime>   (JSON → SearchData)
 *   - Post:  /mobile/post.php?id=<id>&t=<unixTime>   (JSON → PostData)
 *   - Eps:   /mobile/episodes.php?s=<sid>&series=<eid>&t=<unixTime>&page=<n>  (JSON → EpisodesData)
 *   - Stream:/mobile/<ott>/hls/<id>.m3u8?in=<t_hash_t cookie>   (direct M3U8)
 *
 * The t_hash_t cookie is acquired once via [bypass] and cached for 15 hours.
 */
abstract class NetMirrorBaseProvider(
    val ott: String,
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

    private val headers = baseBrowseHeaders

    /** Cookies sent with every browse/search/load request. */
    private fun buildCookies(): Map<String, String> {
        val map = mutableMapOf(
            "t_hash_t" to cookie_value,
            "ott" to ott,
            "hd" to "on"
        )
        if (studio != null) map["studio"] = studio
        return map
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
        if (!ensureCookie()) {
            Log.e(name, "getMainPage: no cookie, aborting")
            return null
        }
        val document = app.get(
            "$mainUrl/mobile/home?app=1",
            cookies = buildCookies(),
            headers = headers,
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
        val url = "$mainUrl/mobile/search.php?s=$query&t=${unixTime}"
        val data = try {
            app.get(url, referer = "$mainUrl/home", cookies = buildCookies(), headers = headers)
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
            "$mainUrl/mobile/post.php?id=$id&t=${unixTime}",
            headers = headers,
            referer = "$mainUrl/home",
            cookies = buildCookies()
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
                    "$mainUrl/mobile/episodes.php?s=$sid&series=$eid&t=${unixTime}&page=$pg",
                    headers = headers,
                    referer = "$mainUrl/home",
                    cookies = buildCookies()
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
        val id = parseJson<LoadData>(data).id
        Log.d(name, "loadLinks: id=$id ott=$ott")
        if (!ensureCookie()) return false

        // Primary approach: direct m3u8 URL (the upstream mobile plugin does this).
        val m3u8Url = buildM3u8Url(mainUrl, ott, id, cookie_value)
        Log.d(name, "loadLinks: m3u8Url=$m3u8Url")
        callback.invoke(
            ExtractorLink(
                source = name,
                name = name,
                url = m3u8Url,
                referer = "$mainUrl/",
                quality = Qualities.Unknown.value,
                type = ExtractorLinkType.M3U8,
                headers = mapOf(
                    "Cookie" to buildCookies().entries.joinToString("; ") { "${it.key}=${it.value}" },
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 13; Pixel 5 Build/TQ3A.230901.001; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/144.0.7559.132 Safari/537.36 /OS.Gatu v3.0"
                )
            )
        )
        return true
    }

    /**
     * Intercepts m3u8 (and segment) requests so they carry the `hd=on` cookie —
     * matches the upstream mobile plugin's getVideoInterceptor().
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
