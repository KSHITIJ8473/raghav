package com.laddu100.raghavanime

import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

object CustomPosters {

    private const val PREFIX = "raghavanime_poster_"
    private const val HIDDEN_LABEL = "\u200B"

    private const val LOOKUP_HOST = "https://api.ani.zip/mappings"
    private const val LOOKUP_PARALLELISM = 8

    val presets = listOf(
        "BetterPosters" to "https://btttr.cc/poster-ra/imdb/poster-default/{imdb_id}.jpg?tag=none&rs=IM",
        "Poster+" to "https://postersplus.elfhosted.com/p/bar_sash/{type}/{imdb_id}.jpg"
    )

    var enabled: Boolean
        get() = getKey<Boolean>(PREFIX + "custom") ?: false
        set(value) { setKey(PREFIX + "custom", value) }

    var hideLabels: Boolean
        get() = getKey<Boolean>(PREFIX + "hide_labels") ?: false
        set(value) { setKey(PREFIX + "hide_labels", value) }

    var template: String
        get() = (getKey<String>(PREFIX + "template") ?: "").trim()
        set(value) { setKey(PREFIX + "template", value.trim()) }

    private val lookupGate = Semaphore(LOOKUP_PARALLELISM)
    private val mappingsCache = ConcurrentHashMap<String, MetaMappings>()

    fun pattern(): String? {
        if (!enabled) return null
        return template
            .replace("%7B", "{", ignoreCase = true)
            .replace("%7D", "}", ignoreCase = true)
            .takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }

    fun cardTitle(title: String): String = if (hideLabels) HIDDEN_LABEL else title

    // supported placeholders: {imdb_id} {tmdb_id} {anilist_id} {kitsu_id} {type}
    suspend fun poster(
        anilistId: Int?,
        kitsuId: Int?,
        isMovie: Boolean,
        fallback: String?,
        known: MetaMappings? = null
    ): String? {
        val tpl = pattern() ?: return fallback
        var url = tpl

        if ("{imdb_id}" in url || "{tmdb_id}" in url) {
            val ids = known ?: mappings(anilistId, kitsuId) ?: return fallback
            if ("{imdb_id}" in url) {
                val imdb = ids.imdb_id?.takeIf { it.startsWith("tt") } ?: return fallback
                url = url.replace("{imdb_id}", imdb)
            }
            if ("{tmdb_id}" in url) {
                val tmdb = ids.themoviedb_id ?: return fallback
                url = url.replace("{tmdb_id}", tmdb.toString())
            }
        }
        if ("{anilist_id}" in url) {
            if (anilistId == null) return fallback
            url = url.replace("{anilist_id}", anilistId.toString())
        }
        if ("{kitsu_id}" in url) {
            if (kitsuId == null) return fallback
            url = url.replace("{kitsu_id}", kitsuId.toString())
        }
        if ("{type}" in url) url = url.replace("{type}", if (isMovie) "movie" else "series")
        return url
    }

    private suspend fun mappings(anilistId: Int?, kitsuId: Int?): MetaMappings? {
        val aKey = anilistId?.let { "anilist:$it" }
        val kKey = kitsuId?.let { "kitsu:$it" }
        aKey?.let { mappingsCache[it] }?.let { return it }
        kKey?.let { mappingsCache[it] }?.let { return it }

        val param = when {
            anilistId != null -> "anilist_id=$anilistId"
            kitsuId != null -> "kitsu_id=$kitsuId"
            else -> return null
        }
        return try {
            lookupGate.withPermit {
                parseAnimeData(app.get("$LOOKUP_HOST?$param").text)?.mappings
            }?.also { ids ->
                aKey?.let { mappingsCache[it] = ids }
                kKey?.let { mappingsCache[it] = ids }
            }
        } catch (_: Exception) {
            null
        }
    }
}
