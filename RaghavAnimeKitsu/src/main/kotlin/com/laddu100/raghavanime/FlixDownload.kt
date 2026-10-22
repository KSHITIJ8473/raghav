package com.laddu100.raghavanime

import com.lagradost.cloudstream3.app

/**
 * flixcloud.cc serves every episode as one plain MKV download (both audio tracks, all
 * subtitles inside), so no local proxy is needed to decrypt the HLS stream:
 *   /d/{id}/__data.json -> file id + signed token -> /download/{file}/progress -> /download/{file}
 * The token is bound to the IP that asked for it, which is the device that plays the file.
 */
object FlixDownload {

    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    private const val BASE = "https://flixcloud.cc"

    data class File(
        val url: String,
        val quality: Int?,
        val size: String?,
        val headers: Map<String, String>
    )

    private val idRegex = Regex("""/e/([a-z0-9]+)""", RegexOption.IGNORE_CASE)
    private val fileIdRegex = Regex("""[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}""", RegexOption.IGNORE_CASE)
    private val tokenRegex = Regex("""eyJ[\w-]+\.[\w-]+\.[\w-]+""")
    private val hostRegex = Regex("""https://[a-z0-9-]+\.flixcloud\.cc""", RegexOption.IGNORE_CASE)
    private val qualityRegex = Regex("""(\d{3,4})p""")
    private val sizeRegex = Regex(""""(\d+(?:\.\d+)?\s*[KMG]B)"""", RegexOption.IGNORE_CASE)

    /** The embed id ("dtpx2rezr4mb" of https://flixcloud.cc/e/dtpx2rezr4mb?v=1): every server of an episode shares it. */
    fun embedId(embedUrl: String): String? = idRegex.find(embedUrl)?.groupValues?.get(1)

    suspend fun resolve(embedUrl: String): File? {
        val id = embedId(embedUrl) ?: return null
        val headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "$BASE/", "Accept" to "*/*")
        return try {
            val data = app.get("$BASE/d/$id/__data.json", headers = headers)
            if (!data.isSuccessful) return null
            val body = data.text
            val fileId = fileIdRegex.find(body)?.value ?: return null
            val token = tokenRegex.find(body)?.value ?: return null
            val host = hostRegex.find(body)?.value ?: BASE

            // Only a file the host has finished preparing can be played
            val progress = app.get("$host/download/$fileId/progress?token=$token", headers = headers)
            if (!progress.isSuccessful || !progress.text.contains("\"ready\"")) return null

            File(
                url = "$host/download/$fileId?token=$token",
                quality = qualityRegex.find(body)?.groupValues?.get(1)?.toIntOrNull(),
                size = sizeRegex.find(body)?.groupValues?.get(1),
                headers = headers - "Accept"
            )
        } catch (_: Exception) {
            null
        }
    }
}
