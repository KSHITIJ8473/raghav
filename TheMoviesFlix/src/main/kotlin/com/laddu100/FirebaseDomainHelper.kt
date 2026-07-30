package com.laddu100

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.lagradost.api.Log
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.parseJson

/**
 * Shared helper for fetching runtime-overridable domain URLs from the Firebase
 * Realtime Database at cloudstreampluginhelper-default-rtdb.firebaseio.com.
 *
 * The database is a flat JSON object mapping plugin keys to their current URL,
 * e.g. { "themoviesflix_url": "https://themoviesflix.boats", ... }.
 *
 * Call sites use the SHORT key (without the "_url" suffix), e.g.
 *   FirebaseDomainHelper.getDomain("themoviesflix")
 * and this helper transparently tries both "themoviesflix" and
 * "themoviesflix_url" so the database schema and call sites stay decoupled.
 *
 * Cache: 5 minutes. If a fetch fails, the cache is kept stale (so we still
 * serve the last-known-good URL) and retried on the next call after the TTL.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
object FirebaseDomainHelper {
    private const val TAG = "FirebaseDomainHelper"
    private const val URL = "https://cloudstreampluginhelper-default-rtdb.firebaseio.com/.json"
    private const val CACHE_TTL_MS = 5 * 60 * 1000L  // 5 minutes

    @Volatile
    private var domains: Map<String, String> = emptyMap()

    @Volatile
    private var lastLoadTime: Long = 0L

    @Volatile
    private var everLoadedSuccessfully: Boolean = false

    private suspend fun load(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && everLoadedSuccessfully && now - lastLoadTime < CACHE_TTL_MS) {
            return  // cache is fresh enough
        }

        try {
            val response = app.get(URL, timeout = 5000L).text
            // Parse as Map<String, Any?> first — Firebase values may be strings,
            // numbers, or nested objects. We only keep string values.
            val parsed = parseJson<Map<String, Any?>>(response)
            domains = parsed.mapNotNull { (k, v) ->
                val strVal = when (v) {
                    is String -> v
                    is Number -> v.toString()
                    else -> null
                }
                strVal?.takeIf { it.isNotBlank() }?.let { k to it.removeSuffix("/") }
            }.toMap()
            lastLoadTime = now
            everLoadedSuccessfully = true
            Log.d(TAG, "load: success, ${domains.size} domains cached")
        } catch (e: Exception) {
            Log.d(TAG, "load: failed - ${e.message}")
            // Keep stale cache if we have one; only update lastLoadTime so we
            // retry after the TTL (rather than hammering Firebase on every call).
            lastLoadTime = now
        }
    }

    /**
     * Look up the domain for [key]. Tries the key as-is, then "key_url".
     * Returns null if neither exists or the fetch failed and no cache exists.
     */
    suspend fun getDomain(key: String): String? {
        load()
        return domains[key] ?: domains["${key}_url"] ?: domains["${key}_domain"]
    }

    /** Force a refresh on the next getDomain() call. Rarely needed. */
    fun invalidate() {
        lastLoadTime = 0L
    }
}
