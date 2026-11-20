package com.laddu100

import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import java.util.concurrent.TimeUnit

object LIVETVFirebaseFetcher {

    private const val PACKAGE_NAME = "com.playfy.tv"
    private const val ANDROID_CERT = "31CD6939D1BEAE32D1B0EF2D9460B170116A1885"

    private val API_KEY: String
        get() = "AIzaSyDdHIwVAD3XgP5bEwZOcR1QIz7gO5q5EoM"

    private val APP_ID: String
        get() = "1:239487160038:android:308875071ce6f0fd48f527"

    private val PROJECT_NUMBER: String
        get() = "239487160038"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    data class RemoteConfigResponse(
        val entries: Map<String, String>? = null,
        val state: String? = null,
        val templateVersion: String? = null,
    )

    suspend fun fetchRemoteConfig(): Map<String, String>? {
        if (API_KEY.isBlank() || APP_ID.isBlank() || PROJECT_NUMBER.isBlank()) {
            return null
        }

        return withContext(Dispatchers.IO) {
            try {
                val url = "https://firebaseremoteconfig.googleapis.com/v1/projects/$PROJECT_NUMBER/namespaces/firebase:fetch"

                val appInstanceId = UUID.randomUUID().toString().replace("-", "")

                val payload = """
                    {
                        "appVersion": "1.7",
                        "appInstanceIdToken": "",
                        "languageCode": "en-IN",
                        "appBuild": "8",
                        "appInstanceId": "$appInstanceId",
                        "countryCode": "IN",
                        "analyticsUserProperties": {},
                        "appId": "$APP_ID",
                        "platformVersion": "33",
                        "sdkVersion": "22.1.2",
                        "packageName": "$PACKAGE_NAME"
                    }
                """.trimIndent()

                val request = Request.Builder()
                    .url(url)
                    .post(payload.toRequestBody("application/json".toMediaType()))
                    .header("Accept", "application/json")
                    .header("Connection", "Keep-Alive")
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "Dalvik/2.1.0 (Linux; U; Android 13; Pixel 5 Build/TQ3A.230901.001)")
                    .header("X-Android-Cert", ANDROID_CERT)
                    .header("X-Android-Package", PACKAGE_NAME)
                    .header("X-Firebase-RC-Fetch-Type", "BASE/1")
                    .header("X-Goog-Api-Key", API_KEY)
                    .header("X-Google-GFE-Can-Retry", "yes")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val responseBody = response.body.string()
                        if (!responseBody.isNullOrBlank()) {
                            val configResponse = parseJson<RemoteConfigResponse>(responseBody)
                            return@withContext configResponse.entries
                        }
                    }
                    null
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    suspend fun getLora(): String? {
        return fetchRemoteConfig()?.get("lora")
    }

    suspend fun getBaseUrl(): String? {
        return fetchRemoteConfig()?.get("baseUrl")?.trimEnd('/')
    }
}
