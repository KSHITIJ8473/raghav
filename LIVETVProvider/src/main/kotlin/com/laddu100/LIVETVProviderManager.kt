package com.laddu100

import com.lagradost.api.Log
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class LIVETVProviderEntry(
    val id: Int,
    val title: String,
    val image: String,
    val catLink: String?
)

data class LIVETVCategoryWrapper(
    val cat: String
)

data class LIVETVCategoryData(
    val visible: Boolean?,
    val name: String,
    val logo: String?,
    val type: String?,
    val api: String
)

data class LIVETVChannelWrapper(
    val channel: String = ""
)

data class LIVETVChannelData(
    val name: String?,
    val logo: String?,
    val visible: Boolean?,
    val links: String?,
    val link_names: List<String>?,
    val is_playlist: Boolean?
)

data class LIVETVEventWrapper(
    val event: String
)

data class LIVETVEventData(
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

data class LIVELiveEventData(
    val id: Int,
    val title: String,
    val image: String?,
    val slug: String,
    val cat: String?,
    val eventInfo: LIVELiveEventInfo?,
    val publish: Int,
    val formats: List<LIVELiveEventFormat>?
)

data class LIVELiveEventInfo(
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

data class LIVELiveEventFormat(
    val title: String?,
    val webLink: String?
)

data class LIVEStreamUrl(
    val name: String?,
    val link: String?,
    val scheme: Int?,
    val api: String?,
    val tokenApi: String?
)

object LIVETVProviderManager {

    private val DEFAULT_BASE_URLS = listOf(
        "https://adsflw.xyz",
        "https://playztv2828.store"
    )

    private var cachedBaseUrl: String? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun parseDateTime(date: String?, time: String?): String? {
        if (date == null || time == null) return null
        return try {
            val parts = date.split("/")
            if (parts.size == 3) {
                val day = parts[0]
                val month = parts[1]
                val year = parts[2]
                "$year/$month/$day $time +0000"
            } else null
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun getBaseUrl(): String {
        cachedBaseUrl?.let { return it }

        val firebaseUrl = LIVETVFirebaseFetcher.getBaseApiUrl()
        if (!firebaseUrl.isNullOrBlank()) {
            cachedBaseUrl = firebaseUrl
            return firebaseUrl
        }

        for (url in DEFAULT_BASE_URLS) {
            try {
                val req = Request.Builder()
                    .url("$url/categories.txt")
                    .header("User-Agent", "Dalvik/2.1.0 (Linux; U; Android 10; SM-A505F)")
                    .head()
                    .build()
                val resp = client.newCall(req).execute()
                if (resp.code < 500) {
                    cachedBaseUrl = url
                    return url
                }
            } catch (_: Exception) {
            }
        }

        cachedBaseUrl = DEFAULT_BASE_URLS.first()
        return cachedBaseUrl!!
    }

    private suspend fun fetchDecrypted(path: String): String? = withContext(Dispatchers.IO) {
        val baseUrl = getBaseUrl()
        val url = "$baseUrl/$path"
        return@withContext try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Dalvik/2.1.0 (Linux; U; Android 10; SM-A505F)")
                .build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body.string()
                if (body.isNotBlank()) LIVETVCryptoUtils.decryptLIVETV(body.trim()) else null
            } else {
                Log.d("LIVETV", "HTTP ${response.code} fetching $url")
                null
            }
        } catch (e: Exception) {
            Log.d("LIVETV", "Exception fetching $url - ${e.message}")
            null
        }
    }

    suspend fun fetchProviders(): List<Map<String, Any>> = withContext(Dispatchers.IO) {
        try {
            val decrypted = fetchDecrypted("categories.txt")
            if (!decrypted.isNullOrBlank()) {
                val wrappers = parseJson<List<LIVETVCategoryWrapper>>(decrypted)
                return@withContext wrappers.mapIndexedNotNull { index, wrapper ->
                    try {
                        val cat = parseJson<LIVETVCategoryData>(wrapper.cat)
                        if (cat.visible != false) {
                            mapOf(
                                "id" to (index + 1),
                                "title" to cat.name,
                                "image" to (cat.logo ?: ""),
                                "catLink" to cat.api,
                                "type" to (cat.type ?: "custom")
                            )
                        } else null
                    } catch (e: Exception) {
                        Log.d("LIVETV", "Failed to parse category at $index - ${e.message}")
                        null
                    }
                }
            }
        } catch (e: Exception) {
            Log.d("LIVETV", "fetchProviders exception - ${e.message}")
        }
        emptyList()
    }

    suspend fun fetchLiveEvents(): List<LIVELiveEventData> = withContext(Dispatchers.IO) {
        try {
            val decrypted = fetchDecrypted("events.txt")
            if (!decrypted.isNullOrBlank()) {
                val wrappers = parseJson<List<LIVETVEventWrapper>>(decrypted)
                val events = wrappers.mapIndexedNotNull { index, wrapper ->
                    try {
                        val ev = parseJson<LIVETVEventData>(wrapper.event)
                        LIVELiveEventData(
                            id = index + 1,
                            title = ev.eventName ?: "Unknown Event",
                            image = ev.eventLogo,
                            slug = ev.links?.substringBeforeLast(".") ?: "",
                            cat = ev.category,
                            eventInfo = LIVELiveEventInfo(
                                teamA = ev.teamAName,
                                teamB = ev.teamBName,
                                teamAFlag = ev.teamAFlag,
                                teamBFlag = ev.teamBFlag,
                                eventCat = ev.category,
                                eventName = ev.eventName,
                                eventLogo = ev.eventLogo,
                                isHot = null,
                                eventType = ev.category,
                                startTime = parseDateTime(ev.date, ev.time),
                                endTime = parseDateTime(ev.end_date, ev.end_time)
                            ),
                            publish = if (ev.visible == true) 1 else 0,
                            formats = ev.link_names?.map { name ->
                                LIVELiveEventFormat(title = name, webLink = ev.links)
                            } ?: emptyList()
                        )
                    } catch (e: Exception) {
                        Log.d("LIVETV", "Failed to parse event at $index - ${e.message}")
                        null
                    }
                }
                return@withContext events.filter { it.publish == 1 }
            }
        } catch (e: Exception) {
            Log.d("LIVETV", "fetchLiveEvents exception - ${e.message}")
        }
        emptyList()
    }

    suspend fun fetchCustomEvents(catLink: String): List<LIVELiveEventData> {
        return withContext(Dispatchers.IO) {
            try {
                val decrypted = fetchDecrypted(catLink)
                if (!decrypted.isNullOrBlank()) {
                    val wrappers = parseJson<List<LIVETVChannelWrapper>>(decrypted)
                    val events = wrappers.mapIndexedNotNull { index, wrapper ->
                        try {
                            val channelData = parseJson<LIVETVChannelData>(wrapper.channel)
                            if (channelData.visible == false) return@mapIndexedNotNull null
                            val links = channelData.links
                            if (links.isNullOrBlank()) return@mapIndexedNotNull null
                            LIVELiveEventData(
                                id = index + 1,
                                title = channelData.name ?: "Unknown Channel",
                                image = channelData.logo,
                                slug = links.substringBeforeLast(".", ""),
                                cat = "Custom",
                                eventInfo = LIVELiveEventInfo(
                                    null, null, null, null, null,
                                    channelData.name, channelData.logo, "0",
                                    null, null, null
                                ),
                                publish = 1,
                                formats = if (!channelData.link_names.isNullOrEmpty()) {
                                    channelData.link_names.map { name ->
                                        LIVELiveEventFormat(title = name, webLink = links)
                                    }
                                } else {
                                    links.split(", ").mapIndexed { i, link ->
                                        LIVELiveEventFormat(title = "Link ${i + 1}", webLink = link)
                                    }
                                }
                            )
                        } catch (e: Exception) {
                            Log.d("LIVETV", "Failed to parse custom event at $index - ${e.message}")
                            null
                        }
                    }
                    return@withContext events.filter { it.publish == 1 }
                }
            } catch (e: Exception) {
                Log.d("LIVETV", "fetchCustomEvents exception - ${e.message}")
            }
            emptyList()
        }
    }

    suspend fun fetchChannelStreams(slug: String): List<LIVEStreamUrl>? = withContext(Dispatchers.IO) {
        try {
            val decrypted = fetchDecrypted("$slug.txt")
            if (!decrypted.isNullOrBlank()) {
                return@withContext parseJson<List<LIVEStreamUrl>>(decrypted)
            }
        } catch (e: Exception) {
            Log.d("LIVETV", "fetchChannelStreams exception for $slug - ${e.message}")
        }
        null
    }
}
