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

    private const val PACKAGE_NAME = "com.cricfy.tv"

    private val API_KEY: String
        get() = "AIzaSyAh9jkEU0E_UYxH0m_BKAt-uUSTiTPqhb8"

    private val APP_ID: String
        get() = "1:963020218535:android:47ec53252c64fb3c9c7b82"

    private val PROJECT_NUMBER: String
        get() = "963020218535"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    data class RemoteConfigResponse(
        val entries: Map<String, String>? = null,
        val appName: String? = null,
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
                        "appInstanceId": "$appInstanceId",
                        "appInstanceIdToken": "",
                        "appId": "$APP_ID",
                        "countryCode": "US",
                        "languageCode": "en-US",
                        "platformVersion": "30",
                        "timeZone": "UTC",
                        "appVersion": "5.0",
                        "appBuild": "50",
                        "packageName": "$PACKAGE_NAME",
                        "sdkVersion": "22.1.0",
                        "analyticsUserProperties": {}
                    }
                """.trimIndent()

                val request = Request.Builder()
                    .url(url)
                    .post(payload.toRequestBody("application/json".toMediaType()))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("X-Android-Package", PACKAGE_NAME)
                    .header("X-Goog-Api-Key", API_KEY)
                    .header("X-Google-GFE-Can-Retry", "yes")
                    .build()

                val response = client.newCall(request).execute()

                if (response.isSuccessful) {
                    val responseBody = response.body.string()
                    if (!responseBody.isNullOrBlank()) {
                        val configResponse = parseJson<RemoteConfigResponse>(responseBody)
                        return@withContext configResponse.entries
                    }
                }

                null
            } catch (_: Exception) {
                null
            }
        }
    }

    // cric_api2 is the working endpoint most of the time, cric_api1 is the spare
    suspend fun getProviderApiUrl(): String? {
        val entries = fetchRemoteConfig()
        return entries?.get("cric_api2") ?: entries?.get("cric_api1")
    }

}
