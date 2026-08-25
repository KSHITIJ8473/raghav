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

        registerMainAPI(LIVETVLiveEventsProvider())

        iptvProviders = runBlocking { LIVETVProviderManager.fetchProviders() }

        val providerSettings = iptvProviders.mapNotNull { p ->
            val title = p["title"] as? String ?: return@mapNotNull null
            title to (sharedPref?.getBoolean(title, false) ?: false)
        }.toMap()

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
                if (type == "custom") {
                    registerMainAPI(LIVETVLiveEventsProvider(displayTitle, catLink))
                } else {
                    registerMainAPI(LIVETV(displayTitle, catLink))
                }
            }

        val act = context as AppCompatActivity
        openSettings = {
            LIVETVSettings(
                this,
                sharedPref,
                iptvProviders.mapNotNull { it["title"] as? String }
            ).show(act.supportFragmentManager, "LIVETVSettings")
        }
    }
}
