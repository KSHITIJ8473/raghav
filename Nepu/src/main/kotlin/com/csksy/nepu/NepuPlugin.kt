package com.csksy.nepu

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class NepuPlugin : Plugin() {
    override fun load(context: Context) {
        NepuSession.init()
        registerMainAPI(Nepu())
        openSettings = { ctx ->
            (ctx as? AppCompatActivity)?.let { activity ->
                NepuSettingsFragment(this).show(activity.supportFragmentManager, "NepuSettings")
            }
            kotlin.Unit
        }
    }
}
