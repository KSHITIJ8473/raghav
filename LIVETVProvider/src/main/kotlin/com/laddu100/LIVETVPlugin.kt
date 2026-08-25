package com.laddu100

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.api.Log
import com.lagradost.cloudstream3.CommonActivity.activity
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import kotlinx.coroutines.runBlocking

@CloudstreamPlugin
class LIVETVPlugin : Plugin() {

    private val sharedPref = activity?.getSharedPreferences("LIVETV", Context.MODE_PRIVATE)

    private var iptvProviders: List<Map<String, Any>> = emptyList()

    override fun load(context: Context) {
        Log.d("LIVETV", "load: start")
        LIVETV.context = context
        LIVETVLiveEventsProvider.context = context

        registerMainAPI(LIVETVLiveEventsProvider())
        Log.d("LIVETV", "load: registered default LiveEventsProvider")

        iptvProviders = runBlocking { LIVETVProviderManager.fetchProviders() }
        Log.d("LIVETV", "load: fetched ${iptvProviders.size} providers")

        val providerSettings = iptvProviders.mapNotNull { p ->
            val title = p["title"] as? String ?: return@mapNotNull null
            title to (sharedPref?.getBoolean(title, false) ?: false)
        }.toMap()

        val enabledCount = providerSettings.count { it.value }
        Log.d("LIVETV", "load: $enabledCount providers enabled in settings")

        iptvProviders
            .filter { p ->
                val title = p["title"] as? String
                title != null && providerSettings[title] == true
            }
            .forEach { p ->
                val title = p["title"] as String
                val catLink = p["catLink"] as String
                val type = p["type"] as? String ?: "custom"
                val displayTitle = "📺 $title"
                Log.d("LIVETV", "load: registering '$displayTitle' type=$type catLink=$catLink")
                if (type == "custom") {
                    registerMainAPI(LIVETVLiveEventsProvider(displayTitle, catLink))
                } else {
                    registerMainAPI(LIVETV(displayTitle, catLink))
                }
            }

        val act = context as AppCompatActivity
        openSettings = {
            Log.d("LIVETV", "openSettings: showing ${iptvProviders.size} playlists")
            LIVETVSettings(
                this,
                sharedPref,
                iptvProviders.mapNotNull { it["title"] as? String }
            ).show(act.supportFragmentManager, "LIVETVSettings")
        }
        Log.d("LIVETV", "load: done")
    }
}
