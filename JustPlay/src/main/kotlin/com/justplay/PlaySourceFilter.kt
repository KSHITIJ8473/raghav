package com.justplay

import com.lagradost.cloudstream3.utils.ExtractorLink

// download-only sources are matched by name on the final label the player
// shows, same link comes back tagged when download mode is active
internal object PlaySourceFilter {

    private val downloadTokens = listOf(
        "10gbps",
        "gdflix instant download",
        "instant download",
        "download"
    )

    fun isDownloadOnlyName(name: String): Boolean {
        val n = name.lowercase()
        return downloadTokens.any { n.contains(it) }
    }

    // built directly because newExtractorLink is suspend and this is not
    fun taggedDownloadOnly(link: ExtractorLink): ExtractorLink {
        val name = if (link.name.contains("(DOWNLOAD ONLY)", ignoreCase = true)) {
            link.name
        } else {
            "${link.name.trimEnd()} (DOWNLOAD ONLY)"
        }
        return ExtractorLink(
            link.source,
            name,
            link.url,
            link.referer,
            link.quality,
            link.headers,
            link.extractorData,
            link.type,
            link.audioTracks
        )
    }
}
