package com.playfy

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.lagradost.api.Log
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.parseJson

/**
 * Shared PlayFy API utility — used by all PlayFy providers.
 * Uses native .so libraries for decryption (bundled in plugin).
 */
object PlayFyAPI {
    private const val TAG = "PlayFy"

    const val BASE_URL = "https://globalmediapoint.online"
    const val LORA_KEY = "zH7hY9@lO=8uXk#f%mI/VvJd2G10Z5eU+L6Pi&aEbwA4scBCStQ3KyWqRjDgnoMRP"

    private val apiHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
        "Accept" to "application/json, text/plain, */*",
        "Accept-Language" to "en-US,en;q=0.5",
        "Referer" to "$BASE_URL/",
        "Origin" to BASE_URL
    )

    var context: android.content.Context? = null

    suspend fun fetchDecrypted(endpoint: String): String? {
        val url = if (endpoint.startsWith("http")) endpoint else "$BASE_URL/$endpoint"

        return try {
            val response = app.get(url, headers = apiHeaders, timeout = 15_000L)

            if (!response.isSuccessful) {
                Log.e(TAG, "API: HTTP ${response.code} for $url")
                return null
            }

            val body = response.text

            if (body.contains("\"data\"")) {
                val json = parseJson<EncryptedResponse>(body)
                val encrypted = json.data
                if (encrypted.isNullOrBlank()) {
                    Log.e(TAG, "API: Encrypted data field is empty")
                    return null
                }
                Log.d(TAG, "API: Encrypted data length=${encrypted.length}")
                decryptNative(encrypted)
            } else {
                Log.d(TAG, "API: Response is plain JSON (not encrypted)")
                body
            }
        } catch (e: Exception) {
            Log.e(TAG, "API: Fetch failed for $url: ${e.message}")
            null
        }
    }

    private fun decryptNative(encryptedData: String): String? {
        Log.d(TAG, "DECRYPT: Starting native decryption, data length=${encryptedData.length}")

        val ctx = context ?: run {
            Log.e(TAG, "DECRYPT: No context available")
            return null
        }

        // Load native libraries
        if (!PlayFyNativeBridge.loadLibraries(ctx)) {
            Log.e(TAG, "DECRYPT: Failed to load native libraries")
            return null
        }

        // Call the native decryption
        Log.d(TAG, "DECRYPT: Calling PlayFyNativeBridge.decrypt...")
        val result = PlayFyNativeBridge.decrypt(encryptedData, LORA_KEY, ctx)

        if (result != null && (result.startsWith("{") || result.startsWith("["))) {
            return result
        } else {
            Log.e(TAG, "DECRYPT: ❌ Native decryption returned null or non-JSON")
            return null
        }
    }

    //  Data Classes

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EncryptedResponse(val data: String? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AppData(val channels: List<ChannelItem>? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ChannelItem(
        val id: Int? = null,
        val title: String? = null,
        val image: String? = null,
        val slug: String? = null,
        val cat: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EventsResponse(val events: List<EventItem>? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EventItem(
        val id: Int? = null,
        val title: String? = null,
        val image: String? = null,
        val slug: String? = null,
        val cat: String? = null,
        val eventInfo: EventInfo? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EventInfo(
        val teamA: String? = null,
        val teamB: String? = null,
        val teamAFlag: String? = null,
        val teamBFlag: String? = null,
        val eventCat: String? = null,
        val eventName: String? = null,
        val eventLogo: String? = null,
        val startTime: String? = null,
        val endTime: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ChannelStreamResponse(val streamUrls: List<StreamUrl>? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class StreamUrl(
        val title: String? = null,
        val link: String? = null,
        val type: Int? = null,
        val api: String? = null,
        val drmType: String? = null,
        val headers: Map<String, String>? = null
    )

    fun parseStreamLink(link: String): Pair<String, Map<String, String>> {
        val parts = link.split("|")
        val url = parts[0].trim()
        val headers = mutableMapOf<String, String>()
        for (i in 1 until parts.size) {
            val headerPart = parts[i].trim()
            val eqIndex = headerPart.indexOf("=")
            if (eqIndex > 0) {
                val key = headerPart.substring(0, eqIndex).trim()
                val value = headerPart.substring(eqIndex + 1).trim().removeSurrounding("\"")
                if (key.isNotEmpty() && value.isNotEmpty()) {
                    headers[key] = value
                }
            }
        }
        return Pair(url, headers)
    }

    fun hexToBase64Url(hex: String): String {
        val bytes = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }
}
