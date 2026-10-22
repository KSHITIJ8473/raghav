package com.vegamovies

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.nicehttp.NiceResponse
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

internal object VegaNet {

    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    private val cfKillers = ConcurrentHashMap<String, CloudflareKiller>()
    private val cfLocks = ConcurrentHashMap<String, Mutex>()

    fun browserHeaders(referer: String? = null): Map<String, String> {
        val h = mutableMapOf(
            "User-Agent" to UA,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.9"
        )
        if (referer != null) h["Referer"] = referer
        return h
    }

    fun originOf(url: String): String {
        return try {
            val uri = URI(url)
            "${uri.scheme}://${uri.host}"
        } catch (_: Exception) {
            url
        }
    }

    suspend fun <T> retry(attempts: Int = 3, gapMs: Long = 700L, block: suspend () -> T?): T? {
        repeat(attempts) { i ->
            try {
                block()?.let { return it }
            } catch (_: Exception) {
            }
            if (i < attempts - 1) delay(gapMs)
        }
        return null
    }

    private fun isCfChallenge(res: NiceResponse): Boolean {
        if (res.headers["cf-mitigated"] == "challenge") return true
        val body = try {
            res.text.lowercase()
        } catch (_: Exception) {
            ""
        }
        return (body.contains("just a moment") && body.contains("challenge-platform")) ||
            body.contains("checking your browser")
    }

    private fun killerFor(url: String): CloudflareKiller =
        cfKillers.getOrPut(originOf(url)) { CloudflareKiller() }

    private fun lockFor(url: String): Mutex =
        cfLocks.getOrPut(originOf(url)) { Mutex() }

    suspend fun fetchWithCf(
        url: String,
        referer: String? = null,
        timeout: Long = 20L,
        headers: Map<String, String>? = null
    ): NiceResponse? {
        val base = headers ?: browserHeaders(referer)
        val plain = try {
            app.get(url, headers = base, timeout = timeout)
        } catch (_: Exception) {
            null
        }
        if (plain != null && plain.code == 200 && !isCfChallenge(plain)) return plain

        val refererHeaders = if (headers != null && headers.keys.any { it.equals("Referer", true) }) {
            headers
        } else {
            base.toMutableMap().apply { if (referer != null) put("Referer", referer) }
        }
        return lockFor(url).withLock {
            val killer = killerFor(url)
            try {
                killer.savedCookies.remove(URI(url).host)
            } catch (_: Exception) {
            }
            val solved = try {
                app.get(url, headers = refererHeaders, interceptor = killer, timeout = 60L)
            } catch (_: Exception) {
                null
            }
            if (solved != null && solved.code == 200 && !isCfChallenge(solved)) solved else null
        }
    }

    // only the number matters, the player groups links by it
    fun qualityOf(label: String): Int {
        if (label.isBlank()) return Qualities.Unknown.value
        Regex("""(\d{3,4})[pP]""").find(label)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        val lower = label.lowercase()
        return when {
            lower.contains("8k") -> 4320
            lower.contains("4k") || lower.contains("uhd") -> 2160
            lower.contains("2k") -> 1440
            else -> Qualities.Unknown.value
        }
    }

    fun decodeDoubleBase64(encoded: String): String? {
        return try {
            base64Decode(base64Decode(encoded.trim()))
        } catch (_: Exception) {
            null
        }
    }

    // poster u= params are single base64 while the drive tokens are double, take whichever decodes to a url
    fun decodeParam(encoded: String): String? {
        val once = runCatching { base64Decode(encoded.trim()) }.getOrNull() ?: return null
        if (once.startsWith("http")) return once
        return runCatching { base64Decode(once) }.getOrNull()?.takeIf { it.startsWith("http") }
    }
}
