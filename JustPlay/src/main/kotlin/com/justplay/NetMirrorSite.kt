package com.justplay

import android.util.Log
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.httpsify
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.FormBody
import org.json.JSONArray
import org.json.JSONObject

internal object NetMirrorSite {

    private const val TAG = "NM"
    private const val BASE = "https://net52.cc"

    // the api only answers the gatu wrapper user agents, plain browser ones
    // get an empty verify hash back
    private const val SOLVE_UA =
        "Mozilla/5.0 (Linux; Android 12; RMX2117 Build/SP1A.210812.016; wv) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/147.0.7727.55 " +
            "Mobile Safari/537.36 /OS.Gatu v3.0"
    private const val PAGE_UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 5 Build/TQ3A.230901.001; wv) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/144.0.7559.132 " +
            "Safari/537.36 /OS.Gatu v3.0"
    private const val PLAYER_UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 5 Build/TQ3A.230901.001; wv) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/149.0.7827.91 " +
            "Safari/537.36 /OS.Gatu v3.0"
    private const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36"
    private const val XRW_APP = "app.netmirror.netmirrornew"

    private const val KEY_COOKIE = "JUSTPLAY_NETMIRROR_COOKIE"
    private const val KEY_COOKIE_AT = "JUSTPLAY_NETMIRROR_COOKIE_AT"

    // the wrapper app sends this exact set on the catalog pages
    private val pageHeaders = mapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif," +
            "image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
        "Accept-Language" to "en-IN,en-US;q=0.9,en;q=0.8",
        "Cache-Control" to "max-age=0",
        "Connection" to "keep-alive",
        "sec-ch-ua" to "\"Not(A:Brand\";v=\"8\", \"Chromium\";v=\"144\", \"Android WebView\";v=\"144\"",
        "sec-ch-ua-mobile" to "?0",
        "sec-ch-ua-platform" to "\"Android\"",
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "same-origin",
        "Sec-Fetch-User" to "?1",
        "Upgrade-Insecure-Requests" to "1",
        "User-Agent" to PAGE_UA,
        "X-Requested-With" to "XMLHttpRequest"
    )

    private class Ott(val code: String, val path: String, val site: String)

    // netflix answers on the bare mobile path, hotstar and prime video sit
    // behind their own prefix
    private val catalogs = listOf(
        Ott("nf", "/mobile", "netmirror_nf"),
        Ott("hs", "/mobile/hs", "netmirror_hs"),
        Ott("pv", "/mobile/pv", "netmirror_pv")
    )

    private data class MirrorHit(val id: String, val title: String)

    private data class MirrorEp(val id: String, val season: Int?, val episode: Int?)

    private data class MirrorSeason(val id: String)

    private class MirrorPost(
        val title: String,
        val year: String?,
        val movie: Boolean,
        val episodes: List<MirrorEp>,
        val seasons: List<MirrorSeason>,
        val morePages: Boolean,
        val pageCursor: String?
    )

    @Volatile
    private var cookieValue: String? = null

    @Volatile
    private var cookieAt = 0L

    private val cookieLock = Mutex()

    private fun apiCookies(cookie: String, ott: String): Map<String, String> =
        mapOf("t_hash_t" to cookie, "ott" to ott, "hd" to "on")

    private fun playlistHeaders(cookie: String, ott: String): Map<String, String> {
        val cookieLine = apiCookies(cookie, ott)
            .entries.joinToString("; ") { "${it.key}=${it.value}" }
        return mapOf(
            "Accept" to "*/*",
            "Accept-Language" to "en-IN,en-US;q=0.9,en;q=0.8",
            "Connection" to "keep-alive",
            "Cookie" to cookieLine,
            "Referer" to "$BASE/mobile/home?app=1",
            "sec-ch-ua" to "\"Android WebView\";v=\"149\", \"Chromium\";v=\"149\", \"Not)A;Brand\";v=\"24\"",
            "sec-ch-ua-mobile" to "?0",
            "sec-ch-ua-platform" to "\"Android\"",
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "same-origin",
            "User-Agent" to PLAYER_UA,
            "X-Requested-With" to XRW_APP
        )
    }

    private fun unixTime(): Long = System.currentTimeMillis() / 1000

    // the cookie stays valid for around fifteen hours, a fresh solve blocks
    // for up to a minute so the stored one is worth keeping around
    private fun cookieFresh(at: Long): Boolean =
        !cookieValue.isNullOrEmpty() && System.currentTimeMillis() - at < 54_000_000L

    private fun readStoredCookie(): String? {
        return try {
            val saved = CloudStreamApp.getKey<String>(KEY_COOKIE)
            val at = CloudStreamApp.getKey<String>(KEY_COOKIE_AT)?.toLongOrNull() ?: 0L
            if (saved.isNullOrBlank() || System.currentTimeMillis() - at >= 54_000_000L) {
                null
            } else {
                saved
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun storeCookie(cookie: String) {
        try {
            CloudStreamApp.setKey(KEY_COOKIE, cookie)
            CloudStreamApp.setKey(KEY_COOKIE_AT, System.currentTimeMillis().toString())
        } catch (_: Exception) {
        }
    }

    private suspend fun solvedCookie(): String? {
        if (cookieFresh(cookieAt)) return cookieValue
        readStoredCookie()?.let { stored ->
            cookieValue = stored
            cookieAt = System.currentTimeMillis()
            return stored
        }
        return cookieLock.withLock {
            if (cookieFresh(cookieAt)) return cookieValue
            val fresh = solveCookie()
            cookieValue = fresh
            cookieAt = if (fresh == null) 0L else System.currentTimeMillis()
            fresh?.let { storeCookie(it) }
            fresh
        }
    }

    private fun addhashOf(response: com.lagradost.nicehttp.NiceResponse): String = try {
        response.document.select("body").attr("data-addhash").trim()
    } catch (_: Exception) {
        ""
    }

    // the home page parks a verify hash, a worker server cracks it in the
    // background and the verify endpoint trades it for the real cookie
    private suspend fun solveCookie(): String? {
        val headers = mapOf("User-Agent" to SOLVE_UA, "X-Requested-With" to XRW_APP)
        var addhash = try {
            addhashOf(app.get("$BASE/mobile/home?app=1", headers = headers, timeout = 20L))
        } catch (_: Exception) {
            ""
        }
        if (addhash.isEmpty()) {
            // some networks get a cloudflare wall on the home page, the
            // webview killer clears it and the same request goes through
            addhash = try {
                addhashOf(
                    app.get(
                        "$BASE/mobile/home?app=1",
                        headers = headers,
                        interceptor = PlayNet.cfKiller,
                        timeout = 60L
                    )
                )
            } catch (_: Exception) {
                ""
            }
        }
        if (addhash.isEmpty()) {
            return null
        }

        // the hash goes out raw on both calls, encoding it breaks the worker
        try {
            app.get(
                "https://userver.net52.cc/?hee5=$addhash&a=y&t=${Math.random()}",
                timeout = 15L
            )
        } catch (_: Exception) {
        }

        // the wrapper sends the hash as an already encoded form value, a
        // plain form post would percent escape it and the solve never lands
        val form = FormBody.Builder().addEncoded("verify", addhash).build()
        repeat(7) {
            delay(10_000L)
            val res = try {
                app.post(
                    "$BASE/mobile/verify2.php",
                    headers = mapOf(
                        "User-Agent" to SOLVE_UA,
                        "X-Requested-With" to "XMLHttpRequest"
                    ),
                    requestBody = form,
                    timeout = 30L
                )
            } catch (_: Exception) {
                null
            } ?: return@repeat
            if (res.text.contains("\"statusup\":\"All Done\"")) {
                val solved = res.cookies["t_hash_t"].orEmpty()
                if (solved.isNotEmpty()) {
                    Log.d(TAG, "solve ok")
                    return solved
                }
            }
        }
        Log.d(TAG, "solve failed")
        return null
    }

    private suspend fun search(cookie: String, ott: Ott, query: String): List<MirrorHit> {
        return try {
            val text = app.get(
                "$BASE${ott.path}/search.php?s=$query&t=${unixTime()}",
                headers = mapOf("User-Agent" to DESKTOP_UA),
                referer = "$BASE/home",
                cookies = apiCookies(cookie, ott.code),
                timeout = 20L
            ).text
            val arr = JSONObject(text).optJSONArray("searchResult") ?: return emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id")
                val t = o.optString("t")
                if (id.isBlank() || t.isBlank()) return@mapNotNull null
                MirrorHit(id, t)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseEpisodes(arr: JSONArray?): List<MirrorEp> {
        if (arr == null) return emptyList()
        val out = mutableListOf<MirrorEp>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (id.isBlank()) continue
            out.add(
                MirrorEp(
                    id,
                    o.optString("s").removePrefix("S").toIntOrNull(),
                    o.optString("ep").removePrefix("E").toIntOrNull()
                )
            )
        }
        return out
    }

    private fun parseSeasons(arr: JSONArray?): List<MirrorSeason> {
        if (arr == null) return emptyList()
        val out = mutableListOf<MirrorSeason>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (id.isNotBlank()) out.add(MirrorSeason(id))
        }
        return out
    }

    private suspend fun fetchPost(cookie: String, ott: Ott, id: String): MirrorPost? {
        return try {
            val text = app.get(
                "$BASE${ott.path}/post.php?id=$id&t=${unixTime()}",
                headers = pageHeaders,
                referer = "$BASE/home",
                cookies = apiCookies(cookie, ott.code),
                timeout = 20L
            ).text
            val root = JSONObject(text)
            val title = root.optString("title")
            val arr = root.optJSONArray("episodes") ?: return null
            if (title.isBlank() || arr.length() == 0) return null
            // a movie post leads its episode list with an empty slot, series
            // posts carry a real episode right at the front
            MirrorPost(
                title,
                root.optString("year").takeIf { it.isNotBlank() },
                arr.optJSONObject(0) == null,
                parseEpisodes(arr),
                parseSeasons(root.optJSONArray("season")),
                root.optInt("nextPageShow", 0) == 1,
                root.optString("nextPageSeason").takeIf { it.isNotBlank() }
            )
        } catch (_: Exception) {
            null
        }
    }

    // the wrapper keeps the whole id json in the series argument, the season
    // id rides in the s one and pages run until the reply says otherwise
    private suspend fun fetchEpisodePages(
        cookie: String,
        ott: Ott,
        idJson: String,
        seasonId: String,
        startPage: Int
    ): List<MirrorEp> {
        val out = mutableListOf<MirrorEp>()
        var page = startPage
        var more = true
        while (more && page < 15) {
            val next = try {
                JSONObject(
                    app.get(
                        "$BASE${ott.path}/episodes.php?s=$seasonId&series=$idJson" +
                            "&t=${unixTime()}&page=$page",
                        headers = pageHeaders,
                        referer = "$BASE/home",
                        cookies = apiCookies(cookie, ott.code),
                        timeout = 20L
                    ).text
                )
            } catch (_: Exception) {
                break
            }
            out.addAll(parseEpisodes(next.optJSONArray("episodes")))
            more = next.optInt("nextPageShow", 0) == 1
            page++
        }
        return out
    }

    private suspend fun emitPlaylist(
        site: String,
        cookie: String,
        ott: Ott,
        id: String,
        title: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val headers = playlistHeaders(cookie, ott.code)
        val text = try {
            app.get(
                "$BASE${ott.path}/playlist.php?id=$id&t=$title&tm=${unixTime()}",
                headers = headers,
                referer = "$BASE/mobile/home?app=1",
                cookies = apiCookies(cookie, ott.code),
                timeout = 20L
            ).text
        } catch (_: Exception) {
            return false
        }
        val arr = try {
            JSONArray(text)
        } catch (_: Exception) {
            null
        } ?: return false

        var emitted = 0
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val sources = item.optJSONArray("sources") ?: continue
            for (j in 0 until sources.length()) {
                val src = sources.optJSONObject(j) ?: continue
                val file = src.optString("file")
                if (!file.startsWith("/")) continue
                callback(
                    newExtractorLink(
                        "[${PlayLabels.siteName(site)}]",
                        PlayLabels.buildLabel(site, src.optString("label"), ""),
                        BASE + file,
                        ExtractorLinkType.M3U8
                    ) {
                        this.referer = "$BASE/mobile/home?app=1"
                        this.quality = getQualityFromName(file.substringAfter("q=", ""))
                        this.headers = headers
                    }
                )
                emitted++
            }
            val tracks = item.optJSONArray("tracks") ?: continue
            for (j in 0 until tracks.length()) {
                val track = tracks.optJSONObject(j) ?: continue
                if (track.optString("kind") != "captions") continue
                val file = track.optString("file").replace("\\", "")
                if (!file.startsWith("http")) continue
                subtitleCallback(
                    newSubtitleFile(track.optString("label"), httpsify(file)) {
                        this.headers = mapOf("Referer" to "$BASE/")
                    }
                )
            }
        }
        if (emitted > 0) Log.d(TAG, "links $emitted ${ott.code}")
        return emitted > 0
    }

    // a movie plays off its own post id, a series episode plays off the
    // episode id its season list carries
    private suspend fun resolvePost(
        cookie: String,
        ott: Ott,
        postId: String,
        post: MirrorPost,
        res: PlayLinkData,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (post.movie || res.season == null) {
            return emitPlaylist(
                ott.site, cookie, ott, postId, post.title, subtitleCallback, callback
            )
        }
        val idJson = """{"id":"$postId"}"""
        val latest = post.episodes.toMutableList()
        if (post.morePages && !post.pageCursor.isNullOrEmpty()) {
            latest.addAll(fetchEpisodePages(cookie, ott, idJson, post.pageCursor, 2))
        }
        latest.firstOrNull { it.season == res.season && it.episode == res.episode }?.let {
            return emitPlaylist(
                ott.site, cookie, ott, it.id, post.title, subtitleCallback, callback
            )
        }
        // the latest season rides in the post reply, the older ones need
        // their own fetch through the season ids
        for (season in post.seasons.dropLast(1)) {
            val eps = fetchEpisodePages(cookie, ott, idJson, season.id, 1)
            val hit = eps.firstOrNull { it.season == res.season && it.episode == res.episode }
            if (hit != null) {
                return emitPlaylist(
                    ott.site, cookie, ott, hit.id, post.title, subtitleCallback, callback
                )
            }
        }
        return false
    }

    private suspend fun resolveOtt(
        cookie: String,
        ott: Ott,
        res: PlayLinkData,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val title = res.title ?: return
        val hits = search(cookie, ott, title)
            .filter { PlayNet.titleMatches(it.title, title) }
        if (hits.isEmpty()) return

        // same title remakes are common across the three catalogs, the year
        // picks the right one and the first hit stays as the fallback
        var fallback: Pair<String, MirrorPost>? = null
        for (hit in hits.take(3)) {
            val post = fetchPost(cookie, ott, hit.id) ?: continue
            if (post.movie != (res.season == null)) continue
            if (fallback == null) fallback = hit.id to post
            if (!PlayNet.yearMatches(post.year.orEmpty(), res.matchYear)) continue
            if (resolvePost(cookie, ott, hit.id, post, res, subtitleCallback, callback)) return
        }
        val spare = fallback ?: return
        resolvePost(cookie, ott, spare.first, spare.second, res, subtitleCallback, callback)
    }

    suspend fun invoke(
        res: PlayLinkData,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val cookie = solvedCookie() ?: return
            coroutineScope {
                catalogs.forEach { ott ->
                    async(Dispatchers.IO) {
                        try {
                            resolveOtt(cookie, ott, res, subtitleCallback, callback)
                        } catch (_: Exception) {
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
    }
}
