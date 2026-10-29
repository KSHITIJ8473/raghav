package com.netnaija

import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson

object NetNaijaSources {

    private const val DISABLED_KEY = "netnaija_disabled_sources"
    private const val KNOWN_KEY = "netnaija_known_sources"

    // every audio track the platform is known to carry, new ones found while
    // browsing get merged in so the settings list stays complete
    private val seedSources = listOf(
        "Original",
        "Arabic Dub", "English Dub", "French Dub", "Hindi Dub", "Indonesian Dub",
        "Kannada Dub", "Malayalam Dub", "Portuguese Dub", "PT-BR Dub", "Russian Dub",
        "Spanish Dub", "ES-LA Dub", "Tagalog Dub", "Tamil Dub", "Telugu Dub",
        "Arabic Hardsub", "English Hardsub", "Indonesian Hardsub", "Kurdish Hardsub",
        "Portuguese Hardsub", "Russian Hardsub", "Spanish Hardsub"
    )

    fun disabled(): Set<String> = try {
        val raw = getKey<String>(DISABLED_KEY) ?: return emptySet()
        parseJson<Set<String>>(raw)
    } catch (_: Exception) {
        emptySet()
    }

    fun setDisabled(labels: Set<String>) {
        try {
            setKey(DISABLED_KEY, labels.toJson())
        } catch (_: Exception) {}
    }

    fun isEnabled(label: String): Boolean = label !in disabled()

    fun knownSources(): List<String> {
        val found = try {
            getKey<String>(KNOWN_KEY)?.let { parseJson<Set<String>>(it) } ?: emptySet()
        } catch (_: Exception) {
            emptySet<String>()
        }
        return (seedSources + found).distinct().sortedWith(
            compareBy({ it != "Original" }, { it.endsWith("Hardsub") }, { it })
        )
    }

    fun register(labels: List<String>) {
        if (labels.isEmpty()) return
        try {
            val known = knownSources().toMutableSet()
            if (known.addAll(labels)) {
                setKey(KNOWN_KEY, known.toJson())
            }
        } catch (_: Exception) {}
    }
}
