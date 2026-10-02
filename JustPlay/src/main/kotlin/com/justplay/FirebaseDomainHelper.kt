package com.justplay

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.parseJson

object FirebaseDomainHelper {
    private const val URL = "https://cloudstreampluginhelper-default-rtdb.firebaseio.com/.json"
    private const val CACHE_TTL_MS = 5 * 60 * 1000L

    @Volatile
    private var domains: Map<String, String> = emptyMap()

    @Volatile
    private var lastLoadTime: Long = 0L

    @Volatile
    private var everLoadedSuccessfully: Boolean = false

    private suspend fun load(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && everLoadedSuccessfully && now - lastLoadTime < CACHE_TTL_MS) return
        try {
            val response = app.get(URL, timeout = 5L).text
            val parsed = parseJson<Map<String, String>>(response)
            domains = parsed.filterValues { it.isNotBlank() }
                .mapValues { it.value.trim().trimEnd('/') }
            lastLoadTime = now
            everLoadedSuccessfully = true
        } catch (_: Exception) {
            lastLoadTime = now
        }
    }

    suspend fun getDomain(key: String): String? {
        load()
        return domains[key] ?: domains["${key}_url"] ?: domains["${key}_domain"]
    }
}
