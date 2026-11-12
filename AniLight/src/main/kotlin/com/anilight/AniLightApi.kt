package com.anilight

import com.lagradost.api.Log
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import java.net.URLEncoder

object AniLightApi {

    private const val TAG = "AniLight"

    private const val DEFAULT_SITE = "https://anilight.live"
    private const val DEFAULT_API = "https://api.anilight.live/api"

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/157.0.0.0 Safari/537.36"

    @Volatile
    private var site = DEFAULT_SITE

    @Volatile
    private var api = DEFAULT_API

    fun site(): String = site

    fun urlEncode(s: String): String = URLEncoder.encode(s, "UTF-8")

    // the api lives on an api. subdomain of the site so both follow one stored domain
    suspend fun refreshDomain() {
        val domain = FirebaseDomainHelper.getDomain("anilight")?.removeSuffix("/") ?: return
        if (domain == site) return
        val host = domain.substringAfter("://").substringBefore('/')
        site = domain
        api = "${domain.substringBefore("://")}://api.$host/api"
        // a session minted against the old host is worthless after a domain swap
        sessionToken = null
        sessionExpiresAt = 0L
    }

    private fun browserHeaders(): Map<String, String> = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "application/json, text/plain, */*",
        "Accept-Language" to "en-US,en;q=0.9",
        "Referer" to "$site/"
    )

    private suspend fun getJson(url: String, timeout: Long = 20L): String? {
        return try {
            val res = app.get(url, headers = browserHeaders(), timeout = timeout)
            if (res.isSuccessful) res.text else null
        } catch (e: Exception) {
            Log.d(TAG, "GET $url: ${e.message}")
            null
        }
    }

    // the site falls back to a direct verify token when the sources/session handshake is refused, every sources call carries the header the api demands
    @Volatile
    private var sessionToken: String? = null
    @Volatile
    private var sessionExpiresAt: Long = 0L

    suspend fun session(force: Boolean = false): String? {
        val now = System.currentTimeMillis()
        if (!force && sessionToken != null && now < sessionExpiresAt - 60_000L) {
            return sessionToken
        }
        return try {
            val res = app.post(
                "$api/sources/verify",
                headers = browserHeaders(),
                json = mapOf("token" to "direct"),
                timeout = 15L
            )
            if (!res.isSuccessful) return sessionToken
            val parsed = parseJson<SessionResponse>(res.text)
            val token = parsed.sessionToken?.takeIf { it.isNotBlank() } ?: return sessionToken
            sessionToken = token
            sessionExpiresAt = parsed.expiresAt ?: (now + 24 * 60 * 60 * 1000L)
            token
        } catch (e: Exception) {
            Log.d(TAG, "session: ${e.message}")
            sessionToken
        }
    }

    suspend fun sources(id: Int, episode: Int, language: String, backup: Boolean): SourcesResponse? {
        val url = "$api/sources?id=$id&episode=$episode&language=$language" +
            if (backup) "&backup=true" else ""
        for (attempt in 0..1) {
            val token = session(attempt == 1) ?: return null
            try {
                val res = app.get(
                    url,
                    headers = browserHeaders() + mapOf("x-source-session" to token),
                    timeout = 20L
                )
                if (res.code == 401 || res.code == 403) {
                    if (attempt == 0) continue
                    return null
                }
                if (res.code == 404) return null
                if (!res.isSuccessful) return null
                return parseJson<SourcesResponse>(res.text)
            } catch (e: Exception) {
                Log.d(TAG, "sources: ${e.message}")
                if (attempt == 1) return null
            }
        }
        return null
    }

    // every stream token resolves through the load balancer, the cdn link behind the redirect is what the player gets
    suspend fun resolveStream(token: String): String? {
        return try {
            val res = app.get(
                "$api/lb/e/$token",
                headers = browserHeaders(),
                allowRedirects = false,
                timeout = 15L
            )
            val location = res.headers["location"]?.trim().orEmpty()
            if (location.startsWith("http")) {
                location
            } else if (res.code in 300..399) {
                "$api/lb/e/$token"
            } else {
                null
            }
        } catch (e: Exception) {
            Log.d(TAG, "lb resolve: ${e.message}")
            null
        }
    }

    // backup mirrors sometimes answer forbidden for a title the scraper never captured, the playlist is fetched up front so dead mirrors are dropped
    suspend fun hlsMaster(url: String): Pair<String, Int?>? {
        return try {
            val res = app.get(url, headers = browserHeaders(), timeout = 20L)
            if (!res.isSuccessful) return null
            val body = res.text
            if (!body.startsWith("#EXTM3U")) return null
            val maxHeight = Regex("RESOLUTION=(\\d+)x(\\d+)")
                .findAll(body)
                .maxOfOrNull { it.groupValues[2].toIntOrNull() ?: 0 }
            url to maxHeight?.takeIf { it > 0 }
        } catch (e: Exception) {
            Log.d(TAG, "hls master: ${e.message}")
            null
        }
    }

    fun captionsUrl(token: String): String = "$api/proxy/captions?token=${urlEncode(token)}"

    suspend fun search(query: String): List<AnimeMedia> {
        val body = getJson("$api/search?q=${urlEncode(query)}&hideAdult=true") ?: return emptyList()
        return try {
            parseJson<List<AnimeMedia>>(body).filter { it.isAdult != true }
        } catch (e: Exception) {
            Log.d(TAG, "search parse: ${e.message}")
            emptyList()
        }
    }

    @Volatile
    private var homepageCache: HomepageResponse? = null
    @Volatile
    private var homepageCheckedAt: Long = 0L

    // one homepage document feeds seven mainpage rows, the cache keeps them from re downloading it
    suspend fun homepage(): HomepageResponse? {
        val cached = homepageCache
        if (cached != null && System.currentTimeMillis() - homepageCheckedAt < 5 * 60_000L) {
            return cached
        }
        val body = getJson("$api/homepage") ?: return cached
        val parsed = try {
            parseJson<HomepageResponse>(body)
        } catch (e: Exception) {
            Log.d(TAG, "homepage parse: ${e.message}")
            return cached
        }
        homepageCache = parsed
        homepageCheckedAt = System.currentTimeMillis()
        return parsed
    }

    suspend fun filter(page: Int, sort: String? = null, genre: String? = null): FilterResponse? {
        val params = buildString {
            append("page=").append(page)
            append("&perPage=30")
            append("&sort=").append(sort ?: "POPULARITY_DESC")
            genre?.let { append("&genres=").append(urlEncode(it)) }
            append("&hideAdult=true")
        }
        val body = getJson("$api/filter?$params") ?: return null
        return try {
            parseJson<FilterResponse>(body)
        } catch (e: Exception) {
            Log.d(TAG, "filter parse: ${e.message}")
            null
        }
    }

    suspend fun animeDetail(slug: String): AnimeMedia? {
        val body = getJson("$api/anime/$slug") ?: return null
        return try {
            parseJson<AnimeMedia>(body)
        } catch (e: Exception) {
            Log.d(TAG, "detail parse: ${e.message}")
            null
        }
    }

    suspend fun watch(slug: String): WatchResponse? {
        val body = getJson("$api/watch/$slug") ?: return null
        return try {
            parseJson<WatchResponse>(body)
        } catch (e: Exception) {
            Log.d(TAG, "watch parse: ${e.message}")
            null
        }
    }
}
