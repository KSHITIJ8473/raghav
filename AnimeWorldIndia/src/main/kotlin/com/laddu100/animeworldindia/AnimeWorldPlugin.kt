package com.laddu100.animeworldindia

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AnimeWorldPlugin : Plugin() {
    override fun load(context: Context) {
        initAnimeWorldCFBypass()
        registerMainAPI(AnimeWorldProvider())
        openSettings = { ctx ->
            (ctx as? androidx.appcompat.app.AppCompatActivity)?.let { activity ->
                AnimeWorldSettingsFragment(this).show(activity.supportFragmentManager, "AnimeWorldSettings")
            }
            kotlin.Unit
        }
    }
}
