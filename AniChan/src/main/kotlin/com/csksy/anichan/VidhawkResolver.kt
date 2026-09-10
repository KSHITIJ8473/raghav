package com.csksy.anichan

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.lagradost.api.Log
import com.lagradost.cloudstream3.app
import java.net.URLEncoder

object VidhawkResolver {

    private const val MAIN_URL = "https://vidhawk.buzz"
    private const val TAG = "AniChan"
    private val mapper = ObjectMapper().registerModule(KotlinModule.Builder().build())

    suspend fun resolve(anilistId: Int, ep: Int, audio: String, server: String): List<VidhawkTrack>? {
        return try {
            val headers = mapOf(
                "User-Agent" to AniChanApi.USER_AGENT,
                "Referer" to "${AniChanApi.MAIN_URL}/"
            )
            val raceUrl = "$MAIN_URL/api/stream/race?episode=$ep&audio=$audio&server=$server" +
                "&anilistId=$anilistId&parentHost=anichan.net"
            val raceResp = app.get(raceUrl, headers = headers)
            val race = mapper.readValue(raceResp.text, VidhawkRace::class.java)

            val ticket = race.servers?.firstOrNull { it.id.equals(server, true) }?.ticket
                ?: race.ticket
                ?: return null

            val playResp = app.get(
                "$MAIN_URL/api/play?t=${URLEncoder.encode(ticket, "UTF-8")}",
                headers = headers
            )
            val play = mapper.readValue(playResp.text, VidhawkPlay::class.java)
            play.tracks
        } catch (e: Exception) {
            Log.d(TAG, "vidhawk resolve failed: ${e.message}")
            null
        }
    }
}
