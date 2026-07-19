package com.laddu100.raghavanime

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.parseJson

@JsonIgnoreProperties(ignoreUnknown = true)
object FirebaseDomainHelper {
    private const val URL = "https://cloudstreampluginhelper-default-rtdb.firebaseio.com/.json"
    private var domains: Map<String, String> = emptyMap()
    private var loaded = false

    private suspend fun load() {
        if (loaded) return
        loaded = true
        try {
            val response = app.get(URL, timeout = 5000L).text
            val parsed = parseJson<Map<String, String?>>(response)
            domains = parsed.mapNotNull { (k, v) ->
                v?.takeIf { it.isNotBlank() }?.let { k to it.removeSuffix("/") }
            }.toMap()
        } catch (_: Exception) {
        }
    }

    suspend fun getDomain(key: String): String? {
        load()
        return domains[key]
    }
}
