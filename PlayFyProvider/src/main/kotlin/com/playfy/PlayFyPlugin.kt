package com.playfy

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.CommonActivity.activity
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.api.Log
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import kotlinx.coroutines.runBlocking

@CloudstreamPlugin
class PlayFyPlugin : Plugin() {
    override fun load(context: Context) {
        // Pass plugin reference to native bridge (for filename access)
        Log.d("PlayFy", "Plugin loaded, filename = ${this.filename}")

        val sp = activity?.getSharedPreferences("PlayFy", Context.MODE_PRIVATE)
        PlayFyPluginHelper.sharedPref = sp

        // Set context for API
        PlayFyAPI.context = context
        PlayFyIPTV.context = context

        // Register main provider
        registerMainAPI(PlayFyLiveEventsProvider())

        // Register sub-providers
        val iptvProviders: List<Map<String, Any>> = runBlocking {
            PlayFyProviderManager.fetchProviders()
        }

        iptvProviders.forEach { provider ->
            val title = provider["title"] as? String ?: return@forEach
            val catLink = provider["catLink"] as? String ?: return@forEach
            val enabled = sp?.getBoolean(title, false) ?: false
            if (enabled && catLink != "null" && catLink.isNotEmpty()) {
                registerMainAPI(PlayFyIPTV(title, catLink))
            }
        }

        // Register settings
        val appContext = context as AppCompatActivity
        openSettings = {
            val frag = PlayFySettings(this, sp, iptvProviders.mapNotNull { it["title"] as? String })
            frag.show(appContext.supportFragmentManager, "PlayFySettings")
        }
    }
}

object PlayFyPluginHelper {
    var sharedPref: android.content.SharedPreferences? = null
}
