package com.justplay

internal object PlayLabels {

    private val siteNames = mapOf(
        "vegamovies" to "VegaMovies",
        "hdhub4u" to "HDHub4u",
        "4khdhub" to "4KHDHub",
        "themoviesflix" to "TheMoviesFlix",
        "multimovies" to "Multimovies",
        "movies4u" to "Movies4u",
        "moviesdrive" to "MoviesDrive",
        "hindmoviez" to "HindMoviez",
        "netnaija" to "NetNaija",
        "netmirror_nf" to "NetMirror Netflix",
        "netmirror_hs" to "NetMirror Hotstar",
        "netmirror_pv" to "NetMirror Prime Video"
    )

    private val qualityRegex = Regex("(?i)\\b(2160p|1440p|1080[pi]|720[pi]|480[pi]|360p|240p|4k|8k|uhd)\\b")
    private val sizeRegex = Regex("(?i)\\b(\\d+(?:[.,]\\d+)?)\\s*(gb|mb)\\b")
    private val langRegex = Regex(
        "(?i)\\b(hindi|english|tamil|telugu|kannada|malayalam|punjabi|marathi|bengali|urdu|gujarati|" +
            "japanese|korean|chinese|mandarin|spanish|french|german|russian|arabic|thai|turkish|" +
            "dual[ -]?audio|multi[ -]?audio)\\b"
    )
    private val sourceRegex = Regex("(?i)\\b(blu-?ray|web-?dl|webrip|hdrip|hdtv|dvdrip|hdcam|hdts|remux)\\b")
    private val codecRegex = Regex("(?i)\\b(x264|x265|h\\.?264|h\\.?265|hevc|avc|av1)\\b")
    private val tagRegex = Regex("(?i)\\b(dolby vision|hdr10\\+|hdr10p|hdr10|hdr|dv|sdr|10bit|8bit|e-?subs?|subs?|watch)\\b")
    private val audioRegex = Regex(
        "(?i)\\b(ddp|dd\\+|dd|eac3|ac3|aac|dts-hd ma|dts|truehd)(?:[ .]?\\d(?:[.,]\\d)?)?"
    )
    private val episodeRegex = Regex("(?i)\\bS(\\d{1,2})\\s*[xXeE]\\s*0*(\\d{1,3})(?!\\d)")
    private val episodeOnlyRegex = Regex("(?i)\\bepisodes?\\s*:?\\s*0*(\\d{1,3})(?!\\d)")

    fun siteName(id: String): String =
        siteNames[id] ?: id.replaceFirstChar { it.uppercase() }

    fun cleanText(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) {
            val c = ch.code
            if (c in 0x2190..0x2BFF || c in 0xD800..0xDFFF || c in 0xE000..0xF8FF ||
                c == 0x200D || c in 0xFE00..0xFE0F
            ) {
                sb.append(' ')
            } else {
                sb.append(ch)
            }
        }
        return sb.toString().replace(Regex("\\s+"), " ").trim()
    }

    private fun normalizeQuality(s: String): String {
        val low = s.lowercase()
        return when {
            low == "uhd" -> "2160p"
            low.endsWith("p") || low.endsWith("i") -> low
            else -> low.uppercase()
        }
    }

    private fun titleCase(s: String): String =
        s.lowercase().replaceFirstChar { it.uppercase() }

    private fun normalizeSource(s: String): String {
        val low = s.lowercase().replace("-", "").replace(" ", "")
        return when (low) {
            "bluray" -> "BluRay"
            "webdl" -> "WEB-DL"
            "webrip" -> "WEBRip"
            "hdrip" -> "HDRip"
            "hdtv" -> "HDTV"
            "dvdrip" -> "DVDRip"
            "hdcam" -> "HDCAM"
            "hdts" -> "HDTS"
            "remux" -> "REMUX"
            else -> s
        }
    }

    private fun normalizeCodec(s: String): String {
        val low = s.lowercase().replace(".", "")
        return when {
            low == "x264" || low == "x265" -> low
            low == "h264" || low == "h265" -> low.replaceFirstChar { it.uppercase() }
            else -> s.uppercase()
        }
    }

    private fun normalizeTag(s: String): String = when (s.lowercase().replace(" ", "")) {
        "dolbyvision" -> "DV"
        "e-sub", "esubs", "esub" -> "ESubs"
        "sub", "subs" -> "Subs"
        "10bit" -> "10bit"
        "8bit" -> "8bit"
        "watch" -> "Watch"
        else -> s.uppercase()
    }

    private fun normalizeAudio(s: String): String =
        cleanText(s).uppercase().replace(Regex("([A-Z])\\."), "$1 ")

    private fun codecFamily(s: String): Int {
        return when (s.lowercase().replace(".", "")) {
            "x264", "h264", "avc" -> 1
            "x265", "h265", "hevc" -> 2
            else -> 0
        }
    }

    fun buildLabel(site: String, server: String, info: String): String {
        val prefix = "[${siteName(site)}]"
        val text = cleanText(info)
        if (text.isBlank()) {
            val cleanServer = cleanText(server)
            return if (cleanServer.isBlank()) prefix else "$prefix - $cleanServer"
        }

        val segments = mutableListOf<String>()

        episodeRegex.find(text)?.let { m ->
            val season = m.groupValues[1].toIntOrNull() ?: 0
            val ep = m.groupValues[2].toIntOrNull() ?: 0
            if (season in 1..99 && ep > 0) {
                segments.add("S" + season.toString().padStart(2, '0') + "E" + ep.toString().padStart(2, '0'))
            }
        }
        if (segments.isEmpty()) {
            episodeOnlyRegex.find(text)?.let { m ->
                val ep = m.groupValues[1].toIntOrNull() ?: 0
                if (ep > 0) segments.add("Episode $ep")
            }
        }

        val qualities = qualityRegex.findAll(text).map { normalizeQuality(it.value) }.distinct().toList()
        when (qualities.size) {
            1 -> segments.add(qualities[0])
            0 -> {}
            else -> segments.add(qualities.joinToString("/"))
        }

        val langs = langRegex.findAll(text).map { it.value.lowercase().replace("-", " ") }.distinct().toList()
        val plain = langs.filter { it != "dual audio" && it != "multi audio" }
        when {
            plain.isNotEmpty() -> segments.add(plain.joinToString("-") { titleCase(it) })
            langs.isNotEmpty() -> segments.add(titleCase(langs[0]))
        }

        sourceRegex.findAll(text).map { normalizeSource(it.value) }.distinct().toList()
            .takeIf { it.isNotEmpty() }?.let { segments.add(it.joinToString(" ")) }

        val codecs = codecRegex.findAll(text).map { normalizeCodec(it.value) }.distinct().toList()
        val pickedCodecs = mutableListOf<String>()
        val seenFamilies = mutableSetOf<Int>()
        for (codec in codecs) {
            val family = codecFamily(codec)
            if (family == 0 || seenFamilies.add(family)) pickedCodecs.add(codec)
        }
        if (pickedCodecs.isNotEmpty()) segments.add(pickedCodecs.joinToString(" "))

        tagRegex.findAll(text).map { normalizeTag(it.value) }.distinct().toList()
            .takeIf { it.isNotEmpty() }?.let { segments.add(it.joinToString(" ")) }

        audioRegex.find(text)?.let { segments.add(normalizeAudio(it.value)) }

        sizeRegex.find(text)?.let { m ->
            val value = m.groupValues[1].replace(",", ".")
            val unit = m.groupValues[2].uppercase()
            segments.add("$value $unit")
        }

        val cleanServer = cleanText(server)
        val deduped = segments.filter { seg ->
            cleanServer.isBlank() || !seg.equals(cleanServer, ignoreCase = true)
        }
        if (deduped.isEmpty()) {
            if (cleanServer.isBlank()) return "$prefix - $text"
            return if (text.equals(cleanServer, ignoreCase = true)) "$prefix - $text"
            else "$prefix - $text · $cleanServer"
        }
        val body = if (cleanServer.isBlank()) {
            deduped.joinToString(" · ")
        } else {
            deduped.joinToString(" · ") + " · " + cleanServer
        }
        return "$prefix - $body"
    }
}
