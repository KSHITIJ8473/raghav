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

        iptvProviders = runBlocking {
            LIVETVProviderManager.fetchProviders()
        }

        val providerSettings = iptvProviders.mapNotNull { provider ->
            val title = provider["title"] as? String ?: return@mapNotNull null
            title to (sharedPref?.getBoolean(title, false) ?: false)
        }.toMap()

        val selectedProviders = iptvProviders.filter {
            val title = it["title"] as? String
            title != null && providerSettings[title] == true
        }

        selectedProviders.forEach { provider ->
            val title = provider["title"] as String
            val catLink = provider["catLink"] as String
            var type = provider["type"] as? String
            if (type == null) {
                type = "m3u"
            }
            val displayTitle = title
            if (type == "custom") {
                registerMainAPI(LIVETVLiveEventsProvider(displayTitle, catLink))
            } else {
                registerMainAPI(LIVETV(displayTitle, catLink))
            }
        }

        val activity = context as AppCompatActivity
        openSettings = {
            val frag = LIVETVSettings(this, sharedPref, iptvProviders.mapNotNull { it["title"] as? String })
            frag.show(activity.supportFragmentManager, "LIVETVSettings")
        }
    }
}
