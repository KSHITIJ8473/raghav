package com.laddu100

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject

// vidout is a thin wrapper over a github hosted index of direct hls links
object MMVidout {

    private const val TAG = "MM_Vidout"

    private const val RAW = "https://raw.githubusercontent.com/Watchout2025/api/refs/heads/main"

    // the watchout cdn only serves its playlists to the vidout embed origin
    private suspend fun addLink(url: String, label: String, callback: (ExtractorLink) -> Unit) {
        callback(
            newExtractorLink(label, label, url, type = ExtractorLinkType.M3U8) {
                this.headers = mapOf("Referer" to "https://vidout.pages.dev/")
            }
        )
    }

    suspend fun resolve(
        tmdbId: String,
        isTv: Boolean,
        season: Int?,
        episode: Int?,
        labelPrefix: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        var added = false
        if (!isTv) {
            val url = MMNet.get("$RAW/hls/movie/$tmdbId", referer = "https://vidout.pages.dev/")
                ?.trim()
            if (!url.isNullOrBlank() && url.startsWith("http")) {
                addLink(url, "$labelPrefix Vidout", callback)
                added = true
            }
            val subsBody = MMNet.get("$RAW/sub/movie/$tmdbId/subtitles.json", referer = "https://vidout.pages.dev/")
            if (!subsBody.isNullOrBlank()) {
                try {
                    val subs = JSONObject(subsBody).optJSONObject("subtitles")
                    if (subs != null) {
                        for (lang in subs.keys()) {
                            val subUrl = subs.optString(lang)
                            if (subUrl.isNotBlank()) {
                                subtitleCallback(SubtitleFile(lang, subUrl))
                            }
                        }
                    }
                } catch (e: Exception) {
                    // subtitle metadata is optional
                    Log.d(TAG, "subs: ${e.message}")
                }
            }
        } else if (season != null) {
            val body = MMNet.get("$RAW/hls/tv/$tmdbId/S$season.json", referer = "https://vidout.pages.dev/")
            if (!body.isNullOrBlank()) {
                val map = try {
                    JSONObject(body)
                } catch (e: Exception) {
                    Log.d(TAG, "tv map: ${e.message}")
                    null
                }
                if (map != null) {
                    val key = episode?.toString()
                    val url = key?.let { map.optString(it) }
                    if (!url.isNullOrBlank() && url.startsWith("http")) {
                        addLink(url, "$labelPrefix Vidout", callback)
                        added = true
                    }
                }
            }
        }
        return added
    }
}

// vidsync publishes a plain embed, a best effort m3u8 grep keeps it usable when it is up
object MMVidsync {
    suspend fun resolve(
        url: String,
        label: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val html = MMNet.get(url, referer = "https://multimovies.garden/") ?: return false
        val urls = Regex("https?://[^\"'\\s\\\\]+\\.m3u8[^\"'\\s\\\\]*")
            .findAll(html)
            .map { MMNet.deEsc(it.groupValues.first()) }
            .toSet()
        for (u in urls) {
            callback(newExtractorLink(label, label, u, type = ExtractorLinkType.M3U8))
        }
        return urls.isNotEmpty()
    }
}

// bingr fans a request out over its own fleet of named scrapers
object MMBingr {

    private const val TAG = "MM_Bingr"

    private data class Server(val id: String, val name: String)

    private val servers = listOf(
        Server("s40", "Aphelion"),
        Server("s70", "Polaris"),
        Server("s62", "Bastion"),
        Server("s63", "Hallyu"),
        Server("s30", "Nova"),
        Server("s60", "Vertex"),
        Server("s61", "Corvus"),
        Server("s31", "Orion"),
        Server("s3", "Edmunds"),
    )

    private const val API = "https://api.bingr.one"

    private suspend fun call(
        server: Server,
        isTv: Boolean,
        id: String,
        title: String,
        year: String?,
        season: Int?,
        episode: Int?,
    ): JSONObject? {
        if (server.id == "s40" && isTv && season != null && episode != null) {
            val body = MMNet.get(
                "$API/stream/aphelion-tv/$id/$season/$episode",
                referer = "https://bingr.one/",
            ) ?: return null
            return try {
                JSONObject(body)
            } catch (e: Exception) {
                Log.d(TAG, "aphelion: ${e.message}")
                null
            }
        }
        val query = JSONObject().put("title", title)
        if (!year.isNullOrBlank()) query.put("year", year)
        if (isTv && season != null) query.put("season", season)
        if (isTv && episode != null) query.put("episode", episode)
        val body = JSONObject()
            .put("srv", server.id)
            .put("t", if (isTv) "tv" else "movie")
            .put("id", id)
            .put("query", query)
        val resp = MMNet.postJson(
            "$API/stream",
            body.toString(),
            referer = "https://bingr.one/",
            extraHeaders = mapOf("Origin" to "https://bingr.one"),
        ) ?: return null
        if (resp.first != 200) return null
        return try {
            JSONObject(resp.second)
        } catch (e: Exception) {
            Log.d(TAG, "${server.id}: ${e.message}")
            null
        }
    }

    private suspend fun addSubtitles(
        root: JSONObject,
        subtitleCallback: (SubtitleFile) -> Unit,
    ) {
        val subs = root.optJSONArray("subtitles") ?: return
        for (i in 0 until subs.length()) {
            val sub = subs.optJSONObject(i) ?: continue
            val url = sub.optString("url")
            if (url.isBlank()) continue
            val lang = sub.optString("lang").ifBlank { sub.optString("label").ifBlank { "en" } }
            val label = sub.optString("label").ifBlank { lang }
            subtitleCallback(SubtitleFile(lang, url))
        }
    }

    suspend fun resolve(
        isTv: Boolean,
        id: String,
        title: String,
        year: String?,
        season: Int?,
        episode: Int?,
        labelPrefix: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        var any = false
        for (server in servers) {
            val root = try {
                call(server, isTv, id, title, year, season, episode)
            } catch (e: Exception) {
                Log.e(TAG, "${server.name}: ${e.message}")
                null
            } ?: continue
            val sources = root.optJSONArray("sources") ?: continue
            for (i in 0 until sources.length()) {
                val src = sources.optJSONObject(i) ?: continue
                val url = src.optString("url")
                if (url.isBlank()) continue
                val quality = src.optString("quality").ifBlank { "HD" }
                callback(
                    newExtractorLink(
                        "$labelPrefix ${server.name}",
                        "$labelPrefix ${server.name} $quality",
                        url,
                        type = ExtractorLinkType.M3U8,
                    )
                )
                any = true
            }
            addSubtitles(root, subtitleCallback)
        }
        return any
    }
}

// filmu exposes one singularity endpoint per media type with a plain source list
object MMFilmu {

    private const val TAG = "MM_Filmu"

    private const val HOST = "https://embed.filmu.in"

    private suspend fun addSource(
        url: String,
        quality: String,
        label: String,
        callback: (ExtractorLink) -> Unit,
    ) {
        callback(
            newExtractorLink(
                label,
                "$label $quality",
                url,
                type = ExtractorLinkType.M3U8,
            )
        )
    }

    private suspend fun addSubtitles(
        root: JSONObject,
        subtitleCallback: (SubtitleFile) -> Unit,
    ) {
        val subs = root.optJSONArray("subtitles") ?: return
        for (i in 0 until subs.length()) {
            val sub = subs.optJSONObject(i) ?: continue
            val url = sub.optString("url")
            if (url.isBlank()) continue
            val lang = sub.optString("lang").ifBlank { "en" }
            subtitleCallback(SubtitleFile(lang, url))
        }
    }

    suspend fun resolve(
        isTv: Boolean,
        tmdbId: String,
        season: Int?,
        episode: Int?,
        labelPrefix: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val path = if (isTv) {
            if (season == null || episode == null) return false
            "$HOST/api/singularity-tv?tmdb=$tmdbId&s=$season&e=$episode"
        } else {
            "$HOST/api/singularity-movie?id=$tmdbId"
        }
        val referer = if (isTv) "$HOST/tv/$tmdbId/$season/$episode" else "$HOST/movie/$tmdbId"
        val body = MMNet.get(path, referer = referer) ?: return false
        val root = try {
            JSONObject(body)
        } catch (e: Exception) {
            Log.d(TAG, "json: ${e.message}")
            return false
        }

        var any = false
        val sources = root.optJSONArray("sources")
        if (sources != null) {
            val base = root.optString("_base")
            for (i in 0 until sources.length()) {
                val src = sources.optJSONObject(i) ?: continue
                var url = src.optString("url")
                if (url.isBlank()) continue
                if (!url.startsWith("http") && base.isNotBlank()) url = MMNet.abs(base, url)
                if (!url.startsWith("http")) continue
                addSource(url, src.optString("quality").ifBlank { "1080p" }, "$labelPrefix Filmu", callback)
                any = true
            }
            addSubtitles(root, subtitleCallback)
        }
        if (!any) {
            val single = root.optString("url").ifBlank {
                if (root.optBoolean("multilingual")) root.optString("multilingual_url") else ""
            }
            if (single.startsWith("http")) {
                addSource(single, root.optString("quality").ifBlank { "1080p" }, "$labelPrefix Filmu", callback)
                addSubtitles(root, subtitleCallback)
                any = true
            } else {
                val m3u8Path = root.optString("m3u8_path")
                val base = root.optString("_base")
                if (m3u8Path.isNotBlank() && base.isNotBlank()) {
                    addSource(
                        MMNet.abs(base, m3u8Path),
                        root.optString("quality").ifBlank { "1080p" },
                        "$labelPrefix Filmu",
                        callback,
                    )
                    any = true
                }
            }
        }
        return any
    }
}

// vidbolt mixes a movy mirror with its own scraper fleet
object MMVidbolt {

    private const val TAG = "MM_Vidbolt"

    private const val MOVY_KEY = "0f461eaa465bb2a7acd037425217f2f209ef540a3171e1ac"
    private const val CURX_KEY = "streamrip_secret_2026"
    private const val SCRAPER = "https://scraper.vidbolt.xyz"

    private suspend fun fetchJson(url: String): JSONObject? {
        val body = MMNet.get(url, referer = "https://vidbolt.xyz/") ?: return null
        return try {
            JSONObject(body)
        } catch (e: Exception) {
            Log.d(TAG, "json: ${e.message}")
            null
        }
    }

    private suspend fun addSources(
        root: JSONObject,
        label: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val sources = root.optJSONArray("sources") ?: return false
        var any = false
        for (i in 0 until sources.length()) {
            val src = sources.optJSONObject(i) ?: continue
            val url = src.optString("url")
            if (url.isBlank() || !url.startsWith("http")) continue
            val quality = src.optString("quality").ifBlank { "HD" }
            val language = src.optString("language").ifBlank { "" }
            val langTag = if (language.isNotBlank() && language.lowercase() != "original") " $language" else ""
            callback(
                newExtractorLink(
                    label,
                    "$label $quality$langTag",
                    url,
                    type = ExtractorLinkType.M3U8,
                )
            )
            any = true
        }
        return any
    }

    suspend fun resolve(
        isTv: Boolean,
        tmdbId: String,
        imdbId: String?,
        title: String,
        year: String?,
        season: Int?,
        episode: Int?,
        labelPrefix: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        var any = false

        if (!isTv) {
            val movy = fetchJson(
                "https://api.movy.lol/api/source/$tmdbId?api_key=$MOVY_KEY&apikey=$MOVY_KEY",
            )
            if (movy != null && addSources(movy, "$labelPrefix Orion", callback)) any = true
        } else if (season != null && episode != null) {
            val curx = fetchJson(
                "https://img.animecurx.tech/api/tv/$tmdbId/$season/$episode?api_key=$CURX_KEY&apikey=$CURX_KEY",
            )
            if (curx != null && addSources(curx, "$labelPrefix Orion", callback)) any = true
        }

        val idForScraper = imdbId ?: "tmdb$tmdbId"
        val type = if (isTv) "tv" else "movie"

        val quasarParams = buildString {
            append("tmdbId=$tmdbId")
            if (isTv && season != null) append("&season=$season")
            if (isTv && episode != null) append("&episode=$episode")
        }
        val quasar = fetchJson("$SCRAPER/scrape/Quasar/$type/$idForScraper?$quasarParams")
        if (quasar != null && addSources(quasar, "$labelPrefix Quasar", callback)) any = true

        val saffronParams = buildString {
            append("tmdbId=$tmdbId")
            if (imdbId != null) append("&imdbId=$imdbId")
            append("&title=${MMNet.urlEncode(title)}")
            if (!year.isNullOrBlank()) append("&year=$year")
            if (isTv && season != null) append("&season=$season")
            if (isTv && episode != null) append("&episode=$episode")
        }
        val saffron = fetchJson("$SCRAPER/scrape/Saffron/$type/$idForScraper?$saffronParams")
        if (saffron != null && addSources(saffron, "$labelPrefix Saffron", callback)) any = true

        val callistoParams = buildString {
            append("title=${MMNet.urlEncode(title)}")
            append("&tmdbId=$tmdbId")
            if (!year.isNullOrBlank()) append("&year=$year")
            if (isTv && season != null) append("&season=$season")
            if (isTv && episode != null) append("&episode=$episode")
        }
        val callisto = fetchJson("$SCRAPER/scrape/Callisto/$type/$idForScraper?$callistoParams")
        if (callisto != null && addSources(callisto, "$labelPrefix Callisto", callback)) any = true

        return any
    }
}
