package com.netnaija.app

import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson

object NetNaijaAppSources {

    private const val DISABLED_KEY = "netnaija_app_disabled_sources"
    private const val KNOWN_KEY = "netnaija_app_known_sources"

    // dubs the platform is known to carry; anything new found while browsing
    // gets merged in so the settings list stays complete
    private val seedSources = listOf(
        "Original",
        "Arabic Dub", "Bengali Dub", "English Dub", "ES-LA Dub", "French Dub",
        "German Dub", "Hindi Dub", "Indonesian Dub", "Italian Dub", "Japanese Dub",
        "Kannada Dub", "Korean Dub", "Malay Dub", "Malayalam Dub", "Portuguese Dub",
        "PT-BR Dub", "Punjabi Dub", "Russian Dub", "Spanish Dub", "Tagalog Dub",
        "Tamil Dub", "Telugu Dub", "Thai Dub", "Turkish Dub", "Urdu Dub"
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

    // "Hindi dub" -> Hindi Dub, "esla dub" -> ES-LA Dub, "Original dub" -> Original
    fun labelOf(lanName: String): String {
        if (lanName.contains("original", true)) return "Original"
        val hardsub = lanName.contains(" sub", true)
        val base = lanName.substringBefore(" dub").substringBefore(" sub").trim()
        if (base.isEmpty()) return lanName.trim().replaceFirstChar { it.uppercase() }
        val pretty = when (base.lowercase()) {
            "ptbr" -> "PT-BR"
            "esla" -> "ES-LA"
            else -> base.replaceFirstChar { it.uppercase() }
        }
        return if (hardsub) "$pretty Hardsub" else "$pretty Dub"
    }
}
