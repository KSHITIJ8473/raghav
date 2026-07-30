package com.laddu100

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.lagradost.api.Log
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.parseJson

@JsonIgnoreProperties(ignoreUnknown = true)
object FirebaseDomainHelper {
    private const val TAG = "FirebaseDomainHelper"
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
        if (!force && everLoadedSuccessfully && now - lastLoadTime < CACHE_TTL_MS) {
            return
        }
        try {
            val response = app.get(URL, timeout = 5000L).text
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
            lastLoadTime = now
        }
    }

    suspend fun getDomain(key: String): String? {
        load()
        return domains[key] ?: domains["${'$'}{key}_url"] ?: domains["${'$'}{key}_domain"]
    }

    fun invalidate() {
        lastLoadTime = 0L
    }
}
