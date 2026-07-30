package com.laddu100

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.CommonActivity.activity
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import kotlinx.coroutines.runBlocking

/**
 * CloudStream plugin entry-point for LIVE TV.
 *
 * On load it:
 *  1. Always registers [LIVETVLiveEventsProvider] (live sports, non-removable).
 *  2. Fetches the provider/category list from the LIVE TV API.
 *  3. Registers whichever providers the user enabled in settings.
 */
@CloudstreamPlugin
class LIVETVPlugin : Plugin() {

    private val sharedPref = activity?.getSharedPreferences("LIVETV", Context.MODE_PRIVATE)

    private var iptvProviders: List<Map<String, Any>> = emptyList()

    override fun load(context: Context) {
        LIVETV.context = context
        LIVETVLiveEventsProvider.context = context

        // Always available — Live Events (not user-configurable)
        registerMainAPI(LIVETVLiveEventsProvider())

        // Fetch provider list from API
        iptvProviders = runBlocking { LIVETVProviderManager.fetchProviders() }

        // Determine which are enabled in settings
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
                val type = (p["type"] as? String) ?: "m3u"
                if (type == "custom") {
                    registerMainAPI(LIVETVLiveEventsProvider(title, catLink))
                } else {
                    registerMainAPI(LIVETV(title, catLink))
                }
            }

        // Hook up the settings screen
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
