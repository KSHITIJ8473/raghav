package com.justplay

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class MovySeedReply(
    val seed: String? = null,
    val ttlMs: Long? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class MovySource(
    val quality: String? = null,
    val url: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class MovySubtitle(
    val lang: String? = null,
    val language: String? = null,
    val url: String? = null,
    val type: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class MovySourcesReply(
    val sources: List<MovySource>? = null,
    val subtitles: List<MovySubtitle>? = null
)

internal object MovyCrypto {

    private const val GOLDEN = -1640531527
    private const val MAGIC_M = 109
    private const val MAGIC_V = 118
    private const val MAGIC_1 = 49

    private fun fmix(value: Int): Int {
        var v = value
        v = v xor (v ushr 16)
        v *= 0x85ebca6b.toInt()
        v = v xor (v ushr 13)
        v *= 0xc2b2ae35.toInt()
        v = v xor (v ushr 16)
        return v
    }

    private fun rotl(value: Int, shift: Int): Int {
        val s = shift and 31
        if (s == 0) return value
        return (value shl s) or (value ushr (32 - s))
    }

    private fun mod61(value: Int): Int = ((value.toLong() and 0xFFFFFFFFL) % 61L).toInt()

    private fun fnv1aMixed(text: String): Int {
        var hash = -2128831035
        for (ch in text) {
            hash = hash xor ch.code
            hash *= 0x1000193
        }
        return fmix(hash)
    }

    private fun toUint32(text: String): Int {
        val parsed = text.trim().toDoubleOrNull() ?: return 0
        if (parsed.isNaN() || parsed.isInfinite()) return 0
        val truncated = parsed.toLong()
        return (truncated and 0xFFFFFFFFL).toInt()
    }

    private class State(val slots: IntArray, val filled: BooleanArray, var acc: Int)

    private fun initState(seed: String, mediaId: String): State {
        val slots = IntArray(61)
        val filled = BooleanArray(61)
        var n = fmix(fnv1aMixed(seed) xor fmix(toUint32(mediaId) xor GOLDEN))
        for (round in 0 until 8) {
            val slot = mod61(n)
            n = rotl(n + GOLDEN, 7 + (7 and round))
            slots[slot] = n xor fmix(n)
            filled[slot] = true
            n = fmix(n + slot)
        }
        val acc = fmix(0xa5a5a5a5.toInt() xor n)
        return State(slots, filled, acc)
    }

    private fun nextWord(state: State, counter: Int): Int {
        val slot = mod61(state.acc)
        val known = state.filled[slot]
        val picked = if (known) state.slots[slot] else 0
        val salt = picked xor (GOLDEN * (counter + 1))
        val acc = state.acc
        val blended = if (known) acc or salt else acc xor salt
        val rotated = rotl(blended + acc, slot and 31) xor rotl(acc, 31 and (slot * 7))
        val word = fmix(rotated + GOLDEN)
        state.slots[slot] = word
        state.filled[slot] = true
        state.acc = word
        return word
    }

    private fun keystream(seed: String, mediaId: String, length: Int): ByteArray {
        val state = initState(seed, mediaId)
        val out = ByteArray(length)
        var index = 0
        var counter = 0
        while (index < length) {
            val word = nextWord(state, counter++)
            out[index++] = word.toByte()
            if (index < length) out[index++] = (word ushr 8).toByte()
            if (index < length) out[index++] = (word ushr 16).toByte()
            if (index < length) out[index++] = (word ushr 24).toByte()
        }
        return out
    }

    private fun decodeBase64Url(payload: String): ByteArray {
        var text = payload.replace("-", "+").replace("_", "/")
        text = text.trimEnd('=')
        while (text.length % 4 != 0) text += "="
        return Base64.getDecoder().decode(text)
    }

    fun decrypt(payload: String, seed: String, mediaId: String): String? {
        return try {
            val raw = decodeBase64Url(payload)
            if (raw.size <= 4) return null
            val mask = keystream(seed, mediaId, raw.size)
            val plain = ByteArray(raw.size)
            for (i in raw.indices) {
                plain[i] = (raw[i].toInt() xor mask[i].toInt()).toByte()
            }
            if (plain[0] != MAGIC_M.toByte() || plain[1] != MAGIC_V.toByte() ||
                plain[2] != MAGIC_M.toByte() || plain[3] != MAGIC_1.toByte()
            ) {
                return null
            }
            String(plain, 4, plain.size - 4, Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }
}

internal object MovySite {

    private const val DEFAULT_MAIN = "https://www.movy.sx"
    private const val STREAM_BASE = "https://api.wecollege.net"
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/155.0.0.0 Safari/537.36"

    @Volatile
    private var mainUrl: String = DEFAULT_MAIN

    private fun baseHeaders(): Map<String, String> = mapOf(
        "User-Agent" to UA,
        "Accept" to "*/*",
        "Referer" to "$mainUrl/",
        "Origin" to mainUrl
    )

    private fun playHeaders(): Map<String, String> = mapOf(
        "User-Agent" to UA,
        "Referer" to "$mainUrl/",
        "Origin" to mainUrl
    )

    private suspend fun syncDomain() {
        if (mainUrl != DEFAULT_MAIN) return
        val remote = FirebaseDomainHelper.getDomain("justplay_movy")
            ?: FirebaseDomainHelper.getDomain("movy")
        remote?.let { mainUrl = it.trimEnd('/') }
    }

    private class City(val slug: String, val display: String, val langNote: String? = null)

    private val cities = listOf(
        City("miami", "Miami"),
        City("boise", "Boise"),
        City("vegas", "Vegas"),
        City("reno", "Reno"),
        City("oakland", "Oakland"),
        City("seattle", "Seattle"),
        City("phoenix", "Phoenix"),
        City("atlanta", "Atlanta"),
        City("portland", "Portland"),
        City("dallas", "Dallas"),
        City("tampa", "Tampa"),
        City("orlando", "Orlando"),
        City("austin", "Austin"),
        City("delhi", "Delhi"),
        City("munich", "Munich", "German"),
        City("berlin", "Berlin", "German"),
        City("paris", "Paris", "French"),
        City("cancun", "Cancun", "Spanish")
    )

    private class SeedPool(private val mediaId: String) {
        @Volatile
        private var seed: String? = null
        private var expiresAt = 0L
        private var lastFetchAt = 0L
        private val mutex = Mutex()

        fun drop() {
            seed = null
        }

        suspend fun current(): String? {
            mutex.lock()
            try {
                if (seed != null && System.currentTimeMillis() < expiresAt) return seed
                return fetchPaced()
            } finally {
                mutex.unlock()
            }
        }

        private suspend fun fetchPaced(): String? {
            var rateLimited = 0
            while (true) {
                val wait = lastFetchAt + 1000L - System.currentTimeMillis()
                if (wait > 0) delay(wait)
                val res = try {
                    app.get(
                        "$STREAM_BASE/seed?mediaId=$mediaId",
                        headers = baseHeaders(),
                        timeout = 15L
                    )
                } catch (_: Exception) {
                    return null
                }
                lastFetchAt = System.currentTimeMillis()
                when {
                    res.code == 200 -> {
                        val reply = try {
                            parseJson<MovySeedReply>(res.text)
                        } catch (_: Exception) {
                            null
                        }
                        val fresh = reply?.seed?.takeIf { it.isNotBlank() } ?: return null
                        seed = fresh
                        expiresAt = lastFetchAt - 5000L + (reply.ttlMs ?: 30000L)
                        return fresh
                    }
                    res.code == 429 && rateLimited < 1 -> {
                        rateLimited++
                        delay(2500)
                    }
                    else -> return null
                }
            }
        }
    }

    private fun titleOnWire(title: String): String {
        val once = java.net.URLEncoder.encode(title, "UTF-8").replace("+", "%20")
        return once.replace("%", "%25")
    }

    private fun buildUrl(
        city: City,
        mediaType: String,
        tmdbId: String,
        imdbId: String,
        title: String,
        year: String,
        season: Int,
        episode: Int,
        seed: String
    ): String {
        val parts = StringBuilder()
        parts.append("title=").append(titleOnWire(title))
        parts.append("&mediaType=").append(mediaType)
        parts.append("&year=").append(year)
        parts.append("&episodeId=").append(episode)
        parts.append("&seasonId=").append(season)
        parts.append("&tmdbId=").append(tmdbId)
        if (imdbId.isNotBlank()) parts.append("&imdbId=").append(imdbId)
        if (city.slug == "munich") parts.append("&language=german")
        parts.append("&enc=2&seed=").append(java.net.URLEncoder.encode(seed, "UTF-8"))
        return "$STREAM_BASE/${city.slug}/sources?$parts"
    }

    private suspend fun citySources(
        city: City,
        mediaType: String,
        tmdbId: String,
        imdbId: String,
        title: String,
        year: String,
        season: Int,
        episode: Int,
        pool: SeedPool
    ): MovySourcesReply? {
        var transportRetries = 1
        var staleSeedStrikes = 0
        var coolDowns = 0
        repeat(5) {
            val seed = pool.current() ?: return null
            val url = buildUrl(city, mediaType, tmdbId, imdbId, title, year, season, episode, seed)
            val code: Int
            val body: String?
            try {
                val res = app.get(url, headers = baseHeaders(), timeout = 30L)
                code = res.code
                body = if (code == 200) res.text else null
            } catch (_: Exception) {
                if (transportRetries > 0) {
                    transportRetries--
                    delay(500)
                    return@repeat
                }
                return null
            }
            when {
                code == 200 && body != null -> {
                    val plain = MovyCrypto.decrypt(body, seed, tmdbId) ?: run {
                        staleSeedStrikes++
                        if (staleSeedStrikes >= 2) {
                            pool.drop()
                            staleSeedStrikes = 0
                        }
                        delay(250)
                        return@repeat
                    }
                    return try {
                        parseJson<MovySourcesReply>(plain)
                    } catch (_: Exception) {
                        null
                    }
                }
                code == 401 -> {
                    staleSeedStrikes++
                    if (staleSeedStrikes >= 3) {
                        pool.drop()
                        staleSeedStrikes = 0
                    }
                    delay(250)
                }
                code == 429 -> {
                    if (coolDowns >= 1) return null
                    coolDowns++
                    delay(2500)
                    pool.drop()
                }
                else -> return null
            }
        }
        return null
    }

    private fun qualityValue(quality: String): Int? = when (quality.lowercase()) {
        "2160p", "4k" -> Qualities.P2160.value
        "1080p" -> Qualities.P1080.value
        "720p" -> Qualities.P720.value
        "480p" -> Qualities.P480.value
        "360p", "sd" -> Qualities.P360.value
        else -> null
    }

    private suspend fun emitSubtitles(
        city: City,
        reply: MovySourcesReply,
        seenUrls: MutableSet<String>,
        seenLabels: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        reply.subtitles.orEmpty().forEach { sub ->
            val url = sub.url?.takeIf { it.startsWith("http") } ?: return@forEach
            val format = sub.type?.lowercase() ?: return@forEach
            if (format != "vtt" && format != "srt") return@forEach
            var label = sub.language?.takeIf { it.isNotBlank() } ?: sub.lang ?: return@forEach
            if (label.length < 4) return@forEach
            val cut = label.indexOf(" - [")
            if (cut > 0) label = label.substring(0, cut).trim()
            if (!seenUrls.add(url)) return@forEach
            if (!seenLabels.add(label.lowercase())) {
                label = "$label (${city.display})"
            }
            try {
                subtitleCallback.invoke(newSubtitleFile(label, url) {
                    headers = playHeaders()
                })
            } catch (_: Exception) {}
        }
    }

    private suspend fun emitLinks(
        city: City,
        reply: MovySourcesReply,
        seenUrls: MutableSet<String>,
        callback: (ExtractorLink) -> Unit
    ) {
        reply.sources.orEmpty().forEach { source ->
            val url = source.url?.takeIf { it.startsWith("http") } ?: return@forEach
            val qualityLabel = source.quality.orEmpty().trim()
            if (qualityLabel.isEmpty()) return@forEach
            if (city.slug == "austin" && qualityLabel != "English") return@forEach
            if (!seenUrls.add(url)) return@forEach
            val info = buildString {
                append(qualityLabel)
                city.langNote?.let { append(' ').append(it) }
            }
            val type = if (url.contains("/mp4/")) ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8
            val name = PlayLabels.buildLabel("movy", city.display, info)
            try {
                callback.invoke(
                    newExtractorLink("[Movy]", name, url, type) {
                        qualityValue(qualityLabel)?.let { quality = it }
                        referer = "$mainUrl/"
                        headers = playHeaders()
                    }
                )
            } catch (_: Exception) {}
        }
    }

    suspend fun invoke(
        res: PlayLinkData,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        syncDomain()
        val tmdbId = res.id?.toString() ?: return
        val title = res.title?.takeIf { it.isNotBlank() } ?: return
        val mediaType = if (res.isMovie) "movie" else "tv"
        val imdbId = res.imdbId.orEmpty()
        val year = (res.matchYear ?: 0).toString()
        val season = res.season ?: 1
        val episode = res.episode ?: 1
        val pool = SeedPool(tmdbId)

        val seenLinks = ConcurrentHashMap.newKeySet<String>()
        val seenSubUrls = ConcurrentHashMap.newKeySet<String>()
        val seenSubLabels = ConcurrentHashMap.newKeySet<String>()

        coroutineScope {
            cities.chunked(6).forEach { wave ->
                wave.map { city ->
                    async(Dispatchers.IO) {
                        val reply = citySources(
                            city, mediaType, tmdbId, imdbId, title, year, season, episode, pool
                        ) ?: return@async
                        emitSubtitles(city, reply, seenSubUrls, seenSubLabels, subtitleCallback)
                        emitLinks(city, reply, seenLinks, callback)
                    }
                }.awaitAll()
            }
        }
    }
}
