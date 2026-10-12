package com.laddu100.raghavanime

import com.lagradost.cloudstream3.app
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

object TmdbArtwork {

    private const val API = "https://api.themoviedb.org/3"
    private const val KEY = "1865f43a0549ca50d341dd9ab8b29f49"

    private val cache = ConcurrentHashMap<String, String>()

    // ani.zip only carries the tmdb id, the actual clear logo lives on
    // the tmdb images endpoint keyed by movie/tv
    suspend fun clearLogo(tmdbId: Int?, isMovie: Boolean): String? {
        if (tmdbId == null || tmdbId <= 0) return null
        val key = "${if (isMovie) "movie" else "tv"}/$tmdbId"
        cache[key]?.let { return it }
        val logo = fetch(key) ?: return null
        cache[key] = logo
        return logo
    }

    private suspend fun fetch(key: String): String? = try {
        pickLogo(JSONObject(app.get("$API/$key/images?api_key=$KEY").text).optJSONArray("logos"))
    } catch (_: Exception) {
        null
    }

    // english png is what the title cards on the source sites use, svg only
    // as a last resort since the app image loader cannot render it
    private fun pickLogo(logos: JSONArray?): String? {
        if (logos == null || logos.length() == 0) return null
        fun urlOf(logo: JSONObject) = "https://image.tmdb.org/t/p/w500" + logo.optString("file_path")
        fun isSvg(logo: JSONObject) = logo.optString("file_path").endsWith(".svg", true)

        var svgFallback: JSONObject? = null
        for (i in 0 until logos.length()) {
            val logo = logos.optJSONObject(i) ?: continue
            if (logo.optString("file_path").isBlank()) continue
            if (logo.optString("iso_639_1").equals("en", true)) {
                if (!isSvg(logo)) return urlOf(logo)
                if (svgFallback == null) svgFallback = logo
            }
        }
        svgFallback?.let { return urlOf(it) }

        var best: JSONObject? = null
        var bestSvg: JSONObject? = null
        for (i in 0 until logos.length()) {
            val logo = logos.optJSONObject(i) ?: continue
            if (logo.optString("file_path").isBlank()) continue
            if (logo.optDouble("vote_average", 0.0) <= 0.0 && logo.optInt("vote_count", 0) <= 0) continue
            val slot = if (isSvg(logo)) bestSvg else best
            if (slot == null ||
                logo.optDouble("vote_average", 0.0) > slot.optDouble("vote_average", 0.0) ||
                (logo.optDouble("vote_average", 0.0) == slot.optDouble("vote_average", 0.0) &&
                    logo.optInt("vote_count", 0) > slot.optInt("vote_count", 0))
            ) {
                if (isSvg(logo)) bestSvg = logo else best = logo
            }
        }
        best?.let { return urlOf(it) }
        bestSvg?.let { return urlOf(it) }
        return null
    }
}
