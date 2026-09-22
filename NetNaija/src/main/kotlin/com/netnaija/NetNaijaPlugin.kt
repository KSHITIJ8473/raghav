package com.netnaija

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.netnaija.settings.SourceSettingsFragment

@CloudstreamPlugin
class NetNaijaPlugin : Plugin() {
    override fun load(context: Context) {
        val api = NetNaija()
        registerMainAPI(api)
        api.warmUp()

        this.openSettings = { ctx ->
            (ctx as? androidx.appcompat.app.AppCompatActivity)?.let { activity ->
                SourceSettingsFragment().show(activity.supportFragmentManager, "NetNaijaSources")
            }
        }
    }
}
