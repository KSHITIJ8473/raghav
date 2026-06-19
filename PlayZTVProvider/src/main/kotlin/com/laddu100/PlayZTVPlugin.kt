package com.laddu100

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.CommonActivity.activity
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import kotlinx.coroutines.runBlocking

/**
 * PlayZTV — CloudStream plugin providing IPTV playlists and live sports.
 *
 * Registration flow:
 *  1. SharedPreferences keyed by "PlayZTV".
 *  2. Fetches the provider manifest from the backend API.
 *  3. Registers the live-events provider unconditionally.
 *  4. Registers each user-selected playlist provider.
 *  5. Attaches the settings bottom-sheet.
 */
@CloudstreamPlugin
class PlayZTVPlugin : Plugin() {

    private val prefs = activity?.getSharedPreferences("PlayZTV", Context.MODE_PRIVATE)

    private var providerList: List<Map<String, Any>> = emptyList()

    override fun load(context: Context) {
        // Seed the static context references
        PlayZTV.context = context
        PlayZTVLiveEventsProvider.context = context

        // Fetch available providers from PlayZTV backend
        providerList = runBlocking { PlayZTVProviderManager.fetchProviders() }

        // Build a lookup of user-enabled providers
        val enabled = providerList.mapNotNull { entry ->
            val name = entry["title"] as? String ?: return@mapNotNull null
            name to (prefs?.getBoolean(name, false) ?: false)
        }.toMap()

        // Register live-events (always on)
        registerMainAPI(PlayZTVLiveEventsProvider())

        // Register each enabled IPTV provider
        for (entry in providerList) {
            val name = entry["title"] as? String ?: continue
            if (enabled[name] != true) continue
            val link = entry["catLink"] as? String ?: continue
            registerMainAPI(PlayZTV(name, link))
        }

        // Wire up the settings dialog
        val activity = context as AppCompatActivity
        openSettings = {
            PlayZTVSettings(
                this,
                prefs,
                providerList.mapNotNull { it["title"] as? String }
            ).show(activity.supportFragmentManager, "PlayZTVSettings")
        }
    }
}
