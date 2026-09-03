package com.laddu100.kuroanime

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object KuroAnimeApi {

    const val MAIN_URL = "https://kuroanime.lol"
    const val KYREN_API = "https://kyren.moe/api"
    const val FLIX_EMBED_BASE = "https://flixcloud.cc"

    private val mapper = ObjectMapper().registerModule(KotlinModule.Builder().build())

    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.6367.82 Mobile Safari/537.36"

    val BROWSER_HEADERS = mapOf(
        "User-Agent" to USER_AGENT,
        "Origin" to MAIN_URL,
        "Referer" to "$MAIN_URL/",
        "Sec-Fetch-Dest" to "empty",
        "Sec-Fetch-Mode" to "cors",
        "Sec-Fetch-Site" to "same-origin"
    )

    val PLAIN_HEADERS = mapOf("User-Agent" to USER_AGENT)

    private val sessionMutex = Mutex()
    @Volatile
    private var kvSid: String? = null
    @Volatile
    private var kvSidAt: Long = 0

    private fun parseSetCookie(raw: String): Pair<String, String>? {
        val first = raw.substringBefore(';').trim()
        val name = first.substringBefore('=').trim()
        val value = first.substringAfter('=', "").trim()
        return if (name.isNotEmpty() && value.isNotEmpty()) name to value else null
    }

    suspend fun sessionCookie(): String? {
        if (kvSid != null && System.currentTimeMillis() - kvSidAt < 6 * 3600_000L) return kvSid
        return sessionMutex.withLock {
            if (kvSid != null && System.currentTimeMillis() - kvSidAt < 6 * 3600_000L) return@withLock kvSid
            try {
                val resp = app.get("$MAIN_URL/api/session", headers = BROWSER_HEADERS)
                val raw = resp.headers.values("Set-Cookie")
                    .mapNotNull { parseSetCookie(it) }
                    .firstOrNull { it.first == "kv_sid" }
                if (raw != null) {
                    kvSid = raw.second
                    kvSidAt = System.currentTimeMillis()
                }
            } catch (e: Exception) {
                null
            }
            kvSid
        }
    }

    suspend fun cookieHeaders(): Map<String, String> {
        val sid = sessionCookie() ?: return emptyMap()
        return mapOf("Cookie" to "kv_sid=$sid")
    }

    private inline fun <reified T> parse(text: String): T? =
        try {
            mapper.readValue(text, T::class.java)
        } catch (e: Exception) {
            null
        }

    private suspend fun getJson(url: String, headers: Map<String, String> = BROWSER_HEADERS): String? =
        try {
            val resp = app.get(url, headers = headers)
            if (resp.isSuccessful) resp.text else null
        } catch (e: Exception) {
            null
        }

    suspend fun search(query: String, limit: Int = 25): List<MediaItem> {
        val body = getJson("$MAIN_URL/api/kuro/search?query=${urlEncode(query)}&limit=$limit")
            ?: return emptyList()
        return parse<ListEnvelope>(body)?.all ?: emptyList()
    }

    suspend fun filter(params: String, page: Int, limit: Int = 40): ListEnvelope? {
        val sep = if (params.isEmpty()) "" else "&"
        val body = getJson("$MAIN_URL/api/kuro/filter?$params${sep}page=$page&limit=$limit")
            ?: return null
        return parse<ListEnvelope>(body)
    }

    suspend fun localAnime(id: Int): MediaItem? {
        val body = getJson("$MAIN_URL/api/local/anime/$id") ?: return null
        return parse<MediaItem>(body)
    }

    suspend fun kuroInfo(id: Int): MediaItem? {
        val body = getJson("$MAIN_URL/api/kuro/info/$id") ?: return null
        val direct = parse<MediaItem>(body)
        if (direct?.id != null) return direct
        return parse<ListEnvelope>(body)?.all?.firstOrNull()
    }

    suspend fun selfHost(anilistId: Int): List<SelfHostEpisode> {
        val body = getJson("$MAIN_URL/api/self-host?anilist_id=$anilistId") ?: return emptyList()
        return parse<SelfHostResponse>(body)?.episodes ?: emptyList()
    }

    suspend fun selfHostLatest(lang: String, limit: Int = 24): List<MediaItem> {
        val body = getJson("$MAIN_URL/api/self-host?latest=1&limit=$limit&lang=$lang") ?: return emptyList()
        return parse<SelfHostResponse>(body)?.items ?: emptyList()
    }

    suspend fun streamSign(path: String): String? {
        val sid = sessionCookie() ?: return null
        val body = getJson(
            "$MAIN_URL/api/stream-sign?path=${urlEncode(path)}",
            BROWSER_HEADERS + mapOf("Cookie" to "kv_sid=$sid")
        ) ?: return null
        return parse<SignResponse>(body)?.url
    }

    suspend fun allAnimeExtract(anilistId: Int, ep: Int, lang: String): ExtractResult? {
        val body = getJson(
            "$MAIN_URL/api/media/allanime/api/extract?ani=$anilistId&ep=$ep&lang=$lang"
        ) ?: return null
        return parse<ExtractResult>(body)
    }

    suspend fun megaPlayExtract(anilistId: Int?, malId: Int?, ep: Int, lang: String): ExtractResult? {
        val sb = StringBuilder("$MAIN_URL/api/megaplay/extract?lang=$lang&ep=$ep&raw=1")
        if (anilistId != null && anilistId > 0) sb.append("&ani=$anilistId")
        if (malId != null && malId > 0) sb.append("&mal=$malId")
        val first = getJson(sb.toString())?.let { parse<ExtractResult>(it) }
        if (first?.ok == true) return first
        val second = getJson(sb.toString())?.let { parse<ExtractResult>(it) }
        return second ?: first
    }

    suspend fun paheEpisodes(anilistId: Int): AudioEpisodes? {
        val body = getJson("$MAIN_URL/api/media/pahe/api/episodes?id=$anilistId") ?: return null
        return parse<AnidbAppEpisodes>(body)?.episodes
    }

    suspend fun paheWatch(watchId: String): List<StreamEntry> {
        val id = watchId.removePrefix("/")
        val body = getJson("$MAIN_URL/api/media/pahe/$id") ?: return emptyList()
        return parse<WatchResponse>(body)?.streams ?: emptyList()
    }

    suspend fun anidbEpisodes(anilistId: Int): AudioEpisodes? {
        val body = getJson("$MAIN_URL/api/anidb/api/episodes?id=$anilistId") ?: return null
        return parse<AnidbAppEpisodes>(body)?.episodes
    }

    suspend fun anidbWatch(watchId: String): List<StreamEntry> {
        val id = watchId.removePrefix("/")
        val body = getJson("$MAIN_URL/api/anidb/$id") ?: return emptyList()
        return parse<WatchResponse>(body)?.streams ?: emptyList()
    }

    suspend fun miruroEpisodes(anilistId: Int): MiruroEpisodes? {
        val body = getJson("$MAIN_URL/api/media/miruro/episodes/$anilistId") ?: return null
        return parse<MiruroEpisodes>(body)
    }

    suspend fun miruroWatch(watchId: String): List<StreamEntry> {
        val id = watchId.removePrefix("/")
        val body = getJson("$MAIN_URL/api/media/miruro/$id") ?: return emptyList()
        return parse<WatchResponse>(body)?.streams ?: emptyList()
    }

    suspend fun kyrenStream(
        anilistId: Int,
        ep: Int,
        lang: String,
        title: String,
        server: String,
        year: Int?,
        episodes: Int?
    ): KyrenStreamResponse? {
        val url = buildString {
            append(KYREN_API)
            append("/stream/")
            append(anilistId)
            append('/')
            append(ep)
            append("?lang=")
            append(lang)
            append("&title=")
            append(urlEncode(title))
            append("&server=")
            append(server)
            if (year != null) append("&year=$year")
            if (episodes != null && episodes > 0) append("&episodes=$episodes")
        }
        val body = getJson(url, PLAIN_HEADERS) ?: return null
        return parse<KyrenStreamResponse>(body)
    }

    suspend fun flixPlayerPage(anilistId: Int, ep: Int, lang: String): String? {
        val body = getJson("$MAIN_URL/api/media/flix/player/anilist/$anilistId/$ep?audio=$lang")
            ?: return null
        return body.takeIf { it.contains("<iframe", ignoreCase = true) }
    }

    fun urlEncode(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")
}
