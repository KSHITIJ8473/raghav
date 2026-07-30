package com.laddu100.cinestream

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class CineStreamPlugin : Plugin() {
    override fun load(context: Context) {
        initCineStreamCFBypass()
        registerMainAPI(CineStreamProvider())
        openSettings = { ctx ->
            (ctx as? androidx.appcompat.app.AppCompatActivity)?.let { activity ->
                CineStreamSettingsFragment(this).show(activity.supportFragmentManager, "CineStreamSettings")
            }
            kotlin.Unit
        }
    }
}
