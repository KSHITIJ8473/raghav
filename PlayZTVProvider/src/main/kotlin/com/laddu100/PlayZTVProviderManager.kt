package com.laddu100

import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

// ── Data classes shared by provider/live-event layers ────────────────────────

data class PlayZTVProviderEntry(
    val id: Int,
    val title: String,
    val image: String,
    val catLink: String?
)

data class PlayZTVCategoryWrapper(
    val cat: String
)

data class PlayZTVCategoryData(
    val visible: Boolean?,
    val name: String,
    val logo: String?,
    val type: String?,
    val api: String
)

data class PlayZTVEventWrapper(
    val event: String
)

data class PlayZTVEventData(
    val category: String?,
    val eventName: String?,
    val eventLogo: String?,
    val teamAName: String?,
    val teamBName: String?,
    val teamAFlag: String?,
    val teamBFlag: String?,
    val date: String?,
    val time: String?,
    val end_date: String?,
    val end_time: String?,
    val links: String?,
    val link_names: List<String>?,
    val visible: Boolean?,
    val priority: Int?
)

// ── Live event domain model ──────────────────────────────────────────────────

data class PlayZLiveEventData(
    val id: Int,
    val title: String,
    val image: String?,
    val slug: String,
    val cat: String?,
    val eventInfo: PlayZLiveEventInfo?,
    val publish: Int,
    val formats: List<PlayZLiveEventFormat>?
)

data class PlayZLiveEventInfo(
    val teamA: String?,
    val teamB: String?,
    val teamAFlag: String?,
    val teamBFlag: String?,
    val eventCat: String?,
    val eventName: String?,
    val eventLogo: String?,
    val isHot: String?,
    val eventType: String?,
    val startTime: String?,
    val endTime: String?
)

data class PlayZLiveEventFormat(
    val title: String?,
    val webLink: String?
)

// ── Stream URL model ─────────────────────────────────────────────────────────

data class PlayZStreamUrl(
    val name: String?,
    val link: String?,
    val scheme: Int?,
    val api: String?,
    val tokenApi: String?
)

// ── Manager singleton ────────────────────────────────────────────────────────

object PlayZTVProviderManager {

    private const val CATEGORIES_FILE = "categories.txt"
    private const val EVENTS_FILE = "events.txt"

    private val FALLBACK_URLS = arrayOf(
        "https://playztv2828.store",
        "https://adsflw.xyz"
    )

    private var activeBaseUrl: String? = null

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    // ── URL resolution ────────────────────────────────────────────────────────

    /**
     * Resolves the active backend URL by trying Firebase, then probing
     * each fallback URL with a lightweight HEAD request.
     */
    private suspend fun resolveBaseUrl(): String {
        // Return cached value if set
        val cached = activeBaseUrl
        if (cached != null) return cached

        // Attempt Firebase Remote Config
        val fbUrl = PlayZTVFirebaseFetcher.getBaseApiUrl()
        if (!fbUrl.isNullOrBlank()) {
            activeBaseUrl = fbUrl
            return fbUrl
        }

        // Probe each fallback URL
        var bestUrl: String? = null
        for (candidate in FALLBACK_URLS) {
            val reachable = probeUrl(candidate)
            if (reachable) {
                bestUrl = candidate
                break
            }
        }

        val resolved = bestUrl ?: FALLBACK_URLS.first()
        activeBaseUrl = resolved
        return resolved
    }

    /** Checks whether a base URL responds with a non-5xx status. */
    private suspend fun probeUrl(base: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("$base/$CATEGORIES_FILE")
                .header("User-Agent", "okhttp/4.12.0")
                .head()
                .build()
            httpClient.newCall(req).execute().use { resp -> resp.code < 500 }
        } catch (_: Exception) {
            false
        }
    }

    // ── Fetch + decrypt pipeline ──────────────────────────────────────────────

    /**
     * Downloads an encrypted file from the backend, decrypts it, and
     * returns the plaintext. Returns null on any failure.
     */
    private suspend fun downloadAndDecrypt(fileName: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val base = resolveBaseUrl()
            val targetUrl = "$base/$fileName"

            val req = Request.Builder()
                .url(targetUrl)
                .header("Accept", "text/plain")
                .header("User-Agent", "Dalvik/2.1.0 (Linux; U; Android 12)")
                .get()
                .build()

            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    println("PlayZTV: $fileName → HTTP ${resp.code}")
                    return@runCatching null
                }
                val rawBody = resp.body?.string() ?: return@runCatching null
                if (rawBody.isBlank()) return@runCatching null
                PlayZTVCryptoUtils.fullDecrypt(rawBody.trim())
            }
        }.onFailure { ex ->
            println("PlayZTV: $fileName fetch failed – ${ex.message}")
        }.getOrNull()
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Retrieves the list of available IPTV providers from the backend.
     * Each entry is a map with keys: id, title, image, catLink.
     */
    suspend fun fetchProviders(): List<Map<String, Any>> = withContext(Dispatchers.IO) {
        val plaintext = downloadAndDecrypt(CATEGORIES_FILE) ?: return@withContext emptyList()

        runCatching {
            val wrappers = parseJson<List<PlayZTVCategoryWrapper>>(plaintext)
            val result = mutableListOf<Map<String, Any>>()

            wrappers.forEachIndexed { idx, wrapper ->
                runCatching {
                    val cat = parseJson<PlayZTVCategoryData>(wrapper.cat)
                    if (cat.visible != false) {
                        result.add(
                            mapOf(
                                "id" to (idx + 1),
                                "title" to cat.name,
                                "image" to (cat.logo ?: ""),
                                "catLink" to cat.api
                            )
                        )
                    }
                }.onFailure { ex ->
                    println("PlayZTV: category #$idx parse error – ${ex.message}")
                }
            }
            result
        }.onFailure { ex ->
            println("PlayZTV: fetchProviders error – ${ex.message}")
        }.getOrDefault(emptyList())
    }

    /**
     * Retrieves live sports events from the backend.
     */
    suspend fun fetchLiveEvents(): List<PlayZLiveEventData> = withContext(Dispatchers.IO) {
        val plaintext = downloadAndDecrypt(EVENTS_FILE) ?: return@withContext emptyList()

        runCatching {
            val wrappers = parseJson<List<PlayZTVEventWrapper>>(plaintext)
            val events = mutableListOf<PlayZLiveEventData>()

            wrappers.forEachIndexed { idx, wrapper ->
                runCatching {
                    val ev = parseJson<PlayZTVEventData>(wrapper.event)
                    val startTs = combineDateTime(ev.date, ev.time)
                    val endTs = combineDateTime(ev.end_date, ev.end_time)

                    events.add(
                        PlayZLiveEventData(
                            id = idx + 1,
                            title = ev.eventName ?: "Unknown Event",
                            image = ev.eventLogo,
                            slug = ev.links?.substringBeforeLast(".") ?: "",
                            cat = ev.category,
                            eventInfo = PlayZLiveEventInfo(
                                teamA = ev.teamAName,
                                teamB = ev.teamBName,
                                teamAFlag = ev.teamAFlag,
                                teamBFlag = ev.teamBFlag,
                                eventCat = ev.category,
                                eventName = ev.eventName,
                                eventLogo = ev.eventLogo,
                                isHot = null,
                                eventType = ev.category,
                                startTime = startTs,
                                endTime = endTs
                            ),
                            publish = if (ev.visible == true) 1 else 0,
                            formats = ev.link_names?.map { name ->
                                PlayZLiveEventFormat(title = name, webLink = ev.links)
                            } ?: emptyList()
                        )
                    )
                }.onFailure { ex ->
                    println("PlayZTV: event #$idx parse error – ${ex.message}")
                }
            }
            events.filter { it.publish == 1 }
        }.onFailure { ex ->
            println("PlayZTV: fetchLiveEvents error – ${ex.message}")
        }.getOrDefault(emptyList())
    }

    /**
     * Retrieves channel stream URLs for a given slug.
     */
    suspend fun fetchChannelStreams(slug: String): List<PlayZStreamUrl>? = withContext(Dispatchers.IO) {
        val plaintext = downloadAndDecrypt("$slug.txt") ?: return@withContext null

        runCatching {
            parseJson<List<PlayZStreamUrl>>(plaintext)
        }.onFailure { ex ->
            println("PlayZTV: stream parse error for $slug – ${ex.message}")
        }.getOrNull()
    }

    // ── Date helpers ──────────────────────────────────────────────────────────

    /** Combines "dd/MM/yyyy" date and "HH:mm" time into ISO-like format. */
    private fun combineDateTime(date: String?, time: String?): String? {
        if (date == null || time == null) return null
        val parts = date.split("/")
        if (parts.size != 3) return null
        val (d, m, y) = parts
        return "$y/$m/$d $time +0000"
    }
}
