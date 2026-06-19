package com.laddu100

import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Retrieves remote configuration from Firebase for the PlayZTV backend.
 *
 * Uses the official Firebase REST API to fetch key-value pairs
 * that control the backend URL and feature flags at runtime.
 *
 * Credentials sourced from the PlayZTV app manifest:
 *   package  : com.playz.tv
 *   key      : AIzaSyDKRqLlbaZBIpHzLBiQTUrJqr3gN-nDWWc
 *   app      : 1:516859456626:android:12a75869902c4f8a6826eb
 *   project  : 516859456626
 */
object PlayZTVFirebaseFetcher {

    private const val PKG = "com.playz.tv"
    private const val GOOGLE_API_KEY = "AIzaSyDKRqLlbaZBIpHzLBiQTUrJqr3gN-nDWWc"
    private const val FIREBASE_APP_ID = "1:516859456626:android:12a75869902c4f8a6826eb"
    private const val FIREBASE_PROJECT = "516859456626"
    private const val VER = "2.1"
    private const val BLD = "4"
    private const val SDK = "22.1.0"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    data class ConfigPayload(val entries: Map<String, String>? = null, val state: String? = null)

    /**
     * Contacts the Firebase Remote Config REST endpoint and returns
     * all key-value entries, or null when the request fails.
     */
    suspend fun fetchRemoteConfig(): Map<String, String>? = withContext(Dispatchers.IO) {
        try {
            val instanceToken = UUID.randomUUID().toString().replace("-", "")
            val endpoint = "https://firebaseremoteconfig.googleapis.com/v1/projects/$FIREBASE_PROJECT/namespaces/firebase:fetch"

            val jsonBody = buildString {
                append("{")
                append("\"appInstanceId\":\"$instanceToken\",")
                append("\"appInstanceIdToken\":\"\",")
                append("\"appId\":\"$FIREBASE_APP_ID\",")
                append("\"countryCode\":\"IN\",")
                append("\"languageCode\":\"ta-IN\",")
                append("\"platformVersion\":\"31\",")
                append("\"timeZone\":\"Asia/Kolkata\",")
                append("\"appVersion\":\"$VER\",")
                append("\"appBuild\":\"$BLD\",")
                append("\"packageName\":\"$PKG\",")
                append("\"sdkVersion\":\"$SDK\",")
                append("\"analyticsUserProperties\":{}")
                append("}")
            }

            val req = Request.Builder()
                .url(endpoint)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("X-Goog-Api-Key", GOOGLE_API_KEY)
                .header("X-Android-Package", PKG)
                .header("X-Google-GFE-Can-Retry", "yes")
                .header("User-Agent", "Dalvik/2.1.0 (Linux; U; Android 12)")
                .post(jsonBody.toRequestBody("application/json".toMediaType()))
                .build()

            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val text = resp.body?.string() ?: return@withContext null
                if (text.isBlank()) return@withContext null
                parseJson<ConfigPayload>(text).entries
            }
        } catch (ex: Exception) {
            println("PlayZTV: Remote config fetch error – ${ex.message}")
            null
        }
    }

    /**
     * Convenience method that extracts the `api_url` value
     * from the remote config, stripping any trailing slash.
     */
    suspend fun getBaseApiUrl(): String? {
        val cfg = fetchRemoteConfig() ?: return null
        return cfg["api_url"]?.trimEnd('/')
    }
}
