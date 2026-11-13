package com.livetgtv

import android.util.Base64
import org.json.JSONObject
import java.util.Locale

object IptvPlaylistParser {

    private val EXTINF_PREFIX = Regex("(#EXTINF:.?[0-9]+)", RegexOption.IGNORE_CASE)
    private val ATTRIBUTE = Regex("(\\w[-\\w]*)\\s*=\\s*(?:\"([^\"]*)\"|([^\\s,]+))", RegexOption.IGNORE_CASE)

    fun parse(content: String): List<PlaylistItem> {
        val items = mutableListOf<PlaylistItem>()
        var title: String? = null
        var attributes: Map<String, String> = emptyMap()
        var headers = linkedMapOf<String, String>()
        var userAgent: String? = null
        var cookie: String? = null
        var key: String? = null
        var keyid: String? = null
        var licenseUrl: String? = null
        var drmKeys: Map<String, String> = emptyMap()

        val lines = content.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            i++
            if (line.isEmpty()) continue

            when {
                line.startsWith("#EXTINF") -> {
                    title = titleOf(line)
                    attributes = attributesOf(line)
                    val attrKey = attributes["key"] ?: attributes["drm-key"]
                    val attrKeyId = attributes["keyid"] ?: attributes["drm-keyid"] ?: attributes["kid"]
                    if (key == null) key = attrKey
                    if (keyid == null) keyid = attrKeyId
                }

                line.startsWith("#EXTHTTP:") -> {
                    try {
                        val raw = JSONObject(line.removePrefix("#EXTHTTP:").trim())
                        val lowered = HashMap<String, String>()
                        for (name in raw.keys()) lowered[name.lowercase(Locale.ROOT)] = raw.optString(name)
                        lowered["cookie"]?.let { cookie = it }
                        lowered["user-agent"]?.let { userAgent = it }
                        (lowered["referer"] ?: lowered["referrer"])?.let { headers["Referer"] = it }
                        lowered["origin"]?.let { headers["Origin"] = it }
                    } catch (_: Exception) {
                    }
                }

                line.startsWith("#EXTVLCOPT") -> {
                    tagValue(line, "http-user-agent")?.let { userAgent = it }
                    tagValue(line, "http-cookie")?.let { cookie = it }
                    (tagValue(line, "http-referrer") ?: tagValue(line, "http-referer"))
                        ?.let { headers["Referer"] = it }
                }

                line.startsWith("#KODIPROP:inputstream.adaptive.license_key=") -> {
                    val value = line.removePrefix("#KODIPROP:inputstream.adaptive.license_key=").trim()
                    when {
                        value.startsWith("http://") || value.startsWith("https://") ->
                            licenseUrl = value

                        value.startsWith("{") ->
                            jsonLicenseKeys(value)?.let { pairs ->
                                if (pairs.isNotEmpty()) {
                                    drmKeys = pairs
                                    if (key == null) key = pairs.entries.first().value
                                    if (keyid == null) keyid = pairs.entries.first().key
                                }
                            }

                        value.contains(":") -> {
                            // walked pair by pair, splitting on every colon mangles the pairs after the first
                            val pairs = linkedMapOf<String, String>()
                            for (pair in value.split(",")) {
                                val kid = hexOrNull(pair.substringBefore(":")) ?: continue
                                val k = hexOrNull(pair.substringAfter(":", "")) ?: continue
                                pairs[kid] = k
                            }
                            if (pairs.isNotEmpty()) {
                                drmKeys = pairs
                                if (key == null) key = pairs.entries.first().value
                                if (keyid == null) keyid = pairs.entries.first().key
                            }
                        }
                    }
                }

                line.startsWith("#") -> Unit

                else -> {
                    var full = line
                    while (i < lines.size) {
                        val next = lines[i].trim()
                        if (next.isEmpty() || next.startsWith("#")) break
                        full += next
                        i++
                    }
                    val url = stripQuotes(full.substringBefore("|"))
                    val playbackHeaders = linkedMapOf<String, String>()
                    playbackHeaders.putAll(headers)
                    (urlParam(full, "referer") ?: urlParam(full, "referrer"))
                        ?.let { playbackHeaders["Referer"] = it }
                    urlParam(full, "origin")?.let { playbackHeaders["Origin"] = it }
                    items.add(
                        PlaylistItem(
                            title = title ?: "Unknown Channel",
                            attributes = attributes,
                            headers = playbackHeaders,
                            url = url,
                            userAgent = urlParam(full, "user-agent") ?: userAgent,
                            key = urlParam(full, "key") ?: key,
                            keyid = urlParam(full, "keyid") ?: keyid,
                            cookie = urlParam(full, "cookie") ?: cookie,
                            licenseUrl = urlParam(full, "licenseUrl") ?: licenseUrl,
                            drmKeys = drmKeys
                        )
                    )
                    title = null
                    attributes = emptyMap()
                    headers = linkedMapOf()
                    userAgent = null
                    cookie = null
                    key = null
                    keyid = null
                    licenseUrl = null
                    drmKeys = emptyMap()
                }
            }
        }
        return items
    }

    private fun jsonLicenseKeys(value: String): Map<String, String>? {
        return try {
            val keys = JSONObject(value).optJSONArray("keys") ?: return null
            val pairs = linkedMapOf<String, String>()
            for (index in 0 until keys.length()) {
                val entry = keys.optJSONObject(index) ?: continue
                val kid = hexOrNull(entry.optString("kid")) ?: continue
                val k = hexOrNull(entry.optString("k")) ?: continue
                pairs[kid] = k
            }
            pairs
        } catch (_: Exception) {
            null
        }
    }

    private fun hexOrNull(value: String): String? {
        val trimmed = value.trim().replace("-", "")
        if (trimmed.isEmpty() || trimmed.length % 2 != 0) return null
        if (!trimmed.matches(Regex("^[0-9a-fA-F]+$"))) {
            return try {
                val normalized = trimmed.replace('-', '+').replace('_', '/')
                val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
                Base64.decode(padded, Base64.DEFAULT).joinToString("") { "%02x".format(it) }
            } catch (_: Exception) {
                null
            }
        }
        return trimmed.lowercase(Locale.ROOT)
    }

    private fun stripQuotes(value: String): String {
        return value.replace("\"", "").trim()
    }

    private fun tagValue(line: String, key: String): String? {
        val match = Regex("$key=(.*)", RegexOption.IGNORE_CASE).find(line) ?: return null
        return stripQuotes(match.groupValues[1])
    }

    private fun urlParam(line: String, key: String): String? {
        val params = if (line.contains('|')) line.substringAfterLast("|") else line
        for (param in stripQuotes(params).split("&")) {
            val kv = param.split("=", limit = 2)
            if (kv.size == 2 && kv[0].trim().equals(key, ignoreCase = true)) {
                return stripQuotes(kv[1])
            }
        }
        return null
    }

    private fun titleOf(line: String): String? {
        val after = EXTINF_PREFIX.replace(line, "").trim()
        val comma = lastCommaOutsideQuotes(after)
        if (comma != null && comma < after.length - 1) {
            return stripQuotes(after.substring(comma + 1))
        }
        return stripQuotes(after.split(",").last())
    }

    private fun attributesOf(line: String): Map<String, String> {
        val after = EXTINF_PREFIX.replace(line, "").trim()
        val comma = lastCommaOutsideQuotes(after)
        val attrsText = if (comma != null) after.substring(0, comma).trim() else after
        val attributes = linkedMapOf<String, String>()
        for (match in ATTRIBUTE.findAll(attrsText)) {
            val name = match.groupValues[1]
            val value = match.groupValues[2].ifEmpty { match.groupValues[3] }
            if (name.isNotEmpty()) attributes[name] = value.trim()
        }
        return attributes
    }

    private fun lastCommaOutsideQuotes(text: String): Int? {
        var inQuotes = false
        var last = -1
        for (index in text.indices) {
            when (text[index]) {
                '"' -> inQuotes = !inQuotes
                ',' -> if (!inQuotes) last = index
            }
        }
        return if (last >= 0) last else null
    }
}
