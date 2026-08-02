package com.laddu100.hdghartv

import com.lagradost.api.Log
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
object FirebaseDomainHelper {

    private const val URL = "https://cloudstreampluginhelper-default-rtdb.firebaseio.com/.json"
    private const val CACHE_TTL_MS = 5 * 60 * 1000L

    @Volatile
    private var domains: Map<String, String> = emptyMap()
    @Volatile
    private var lastLoadTime: Long = 0L
    @Volatile
    private var loaded: Boolean = false

    private suspend fun load() {
        val now = System.currentTimeMillis()
        if (loaded && now - lastLoadTime < CACHE_TTL_MS) return
        try {
            val response = app.get(URL, timeout = 5000L).text
            val parsed = parseJson<Map<String, Any?>>(response)
            domains = parsed.mapNotNull { (k, v) ->
                val s = when (v) {
                    is String -> v
                    is Number -> v.toString()
                    else -> null
                }
                s?.takeIf { it.isNotBlank() }?.let { k to it.removeSuffix("/") }
            }.toMap()
            lastLoadTime = now
            loaded = true
        } catch (e: Exception) {
            Log.e("FirebaseDomainHelper", "${e.message}")
            lastLoadTime = now
        }
    }

    suspend fun getDomain(key: String): String? {
        load()
        return domains[key] ?: domains["${key}_url"] ?: domains["${key}_domain"]
    }
}
