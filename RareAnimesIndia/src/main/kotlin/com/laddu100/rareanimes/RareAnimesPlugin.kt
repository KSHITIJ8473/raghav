package com.laddu100.rareanimes

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class RareAnimesPlugin : Plugin() {
    override fun load(context: Context) {
        RAICFStore.init()
        registerMainAPI(RareAnimesProvider())
        openSettings = { ctx ->
            (ctx as? androidx.appcompat.app.AppCompatActivity)?.let { activity ->
                RAISettingsFragment(this).show(activity.supportFragmentManager, "RareAnimesSettings")
            }
            kotlin.Unit
        }
    }
}
