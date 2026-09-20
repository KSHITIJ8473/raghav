package com.laddu100

import com.lagradost.api.Log
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import java.net.URLEncoder

object AniPMApi {
    const val MAIN_URL = "https://ani.pm"
    private const val SETTLAR_EMBED = "https://embed.settlar.io"
    private const val TAG = "AniPM"

    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    // every ani.pm and settlar edge 403s requests without a full browser ua,
    // the referer is only there to look like the web app
    private fun headers(referer: String = "$MAIN_URL/"): Map<String, String> = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "application/json",
        "Referer" to referer
    )

    private suspend fun getJson(url: String, referer: String = "$MAIN_URL/"): String? {
        return try {
            val res = app.get(url, headers = headers(referer), timeout = 30_000L)
            if (res.code == 200) res.text else {
                Log.d(TAG, "${url.substringBefore('?')} answered ${res.code}")
                null
            }
        } catch (e: Exception) {
            Log.d(TAG, "${url.substringBefore('?')} failed: ${e.message}")
            null
        }
    }

    // covers, banners and episode stills come back as /api/... paths
    fun absolute(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return if (url.startsWith("http")) url else "$MAIN_URL$url"
    }

    suspend fun search(query: String): List<AniPMTitle> {
        if (query.length < 2) return emptyList()
        val text = getJson("$MAIN_URL/api/anime/search?q=${encode(query)}") ?: return emptyList()
        return try {
            parseJson<AniPMSearchResponse>(text).items.orEmpty().filter { it.id != null }
        } catch (e: Exception) {
            Log.d(TAG, "search parse failed: ${e.message}")
            emptyList()
        }
    }

    suspend fun browse(sort: String, page: Int, format: String? = null): AniPMBrowseResponse? {
        val url = buildString {
            append("$MAIN_URL/api/anime/browse?sort=$sort&page=$page&limit=30")
            format?.let { append("&format=").append(it) }
        }
        val text = getJson(url) ?: return null
        return try {
            parseJson<AniPMBrowseResponse>(text)
        } catch (e: Exception) {
            Log.d(TAG, "browse parse failed: ${e.message}")
            null
        }
    }

    suspend fun latestEpisodes(page: Int): AniPMLatestResponse? {
        val text = getJson("$MAIN_URL/api/anime/latest-episodes?page=$page") ?: return null
        return try {
            parseJson<AniPMLatestResponse>(text)
        } catch (e: Exception) {
            Log.d(TAG, "latest parse failed: ${e.message}")
            null
        }
    }

    suspend fun series(id: Int): AniPMSeries? {
        val text = getJson("$MAIN_URL/api/anime/series/$id?routes=e3") ?: return null
        return try {
            parseJson<AniPMSeries>(text)
        } catch (e: Exception) {
            Log.d(TAG, "series $id parse failed: ${e.message}")
            null
        }
    }

    suspend fun filler(anilistId: String?, title: String?): AniPMFillerList? {
        if (anilistId.isNullOrBlank()) return null
        val text =
            getJson("$MAIN_URL/api/anime/filler?anilistId=$anilistId&title=${encode(title.orEmpty())}")
                ?: return null
        return try {
            parseJson<AniPMFillerRanges>(text).ranges
        } catch (e: Exception) {
            Log.d(TAG, "filler parse failed: ${e.message}")
            null
        }
    }

    // lang falls back on the server side when the episode has no dub, the
    // effectiveLanguage field in the response tells what actually came back
    suspend fun bootstrap(id: Int, episode: Int, lang: String, backup: Boolean = false): AniPMBootstrap? {
        val url = "$MAIN_URL/api/anime/playback-bootstrap/settlar/$id" +
            "?ep=$episode&lang=$lang" +
            if (backup) "&backup=1" else ""
        val text = getJson(url) ?: return null
        return try {
            parseJson<AniPMBootstrap>(text)
        } catch (e: Exception) {
            Log.d(TAG, "bootstrap $id ep$episode parse failed: ${e.message}")
            null
        }
    }

    suspend fun settlarSession(selection: String, episode: Int, channel: String): String? {
        val url = "$MAIN_URL/api/anime/settlar/session" +
            "?selection=${encode(selection)}&provider=anipm&ep=$episode&channel=$channel&telemetry=0"
        val text = getJson(url) ?: return null
        return try {
            parseJson<AniPMEmbedSession>(text).embedUrl
        } catch (e: Exception) {
            Log.d(TAG, "settlar session parse failed: ${e.message}")
            null
        }
    }

    suspend fun settlarResolve(embedUrl: String): SettlarStream? {
        val token = Regex("t=([^&]+)").find(embedUrl)?.groupValues?.get(1) ?: return null
        val text =
            getJson("$SETTLAR_EMBED/api/embed/session?t=$token", "$SETTLAR_EMBED/embed/v1")
                ?: return null
        return try {
            parseJson<SettlarStream>(text)
        } catch (e: Exception) {
            Log.d(TAG, "settlar resolve parse failed: ${e.message}")
            null
        }
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, "UTF-8")
}
