package com.laddu100

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.CommonActivity.activity
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import kotlinx.coroutines.runBlocking

@CloudstreamPlugin
class LIVETVPlugin : Plugin() {

    private val sharedPref = activity?.getSharedPreferences("LIVETV", Context.MODE_PRIVATE)

    private var iptvProviders: List<Map<String, Any>> = emptyList()

    override fun load(context: Context) {
        LIVETV.context = context
        LIVETVLiveEventsProvider.context = context

        registerMainAPI(LIVETVLiveEventsProvider("⚡ LIVE TV Live Events", "live"))
        registerMainAPI(LIVETVLiveEventsProvider("🎬 LIVE TV Highlights", "highlights"))

        iptvProviders = runBlocking {
            LIVETVProviderManager.fetchProviders()
        }

        val selectedProviders = iptvProviders.filter { provider ->
            val title = provider["title"] as? String
            title != null && sharedPref?.getBoolean(title, false) == true
        }

        selectedProviders.forEach { provider ->
            val title = provider["title"] as String
            val catLink = provider["catLink"] as String
            registerMainAPI(LIVETV("📺 $title", catLink))
        }

        val activity = context as AppCompatActivity
        openSettings = {
            val frag = LIVETVSettings(this, sharedPref, iptvProviders.mapNotNull { it["title"] as? String })
            frag.show(activity.supportFragmentManager, "LIVETVSettings")
        }
    }
}
