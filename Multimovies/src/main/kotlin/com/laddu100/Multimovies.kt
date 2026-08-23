package com.laddu100

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class Multimovies : Plugin() {
    override fun load(context: Context) {
        initMMCFBypass()
        registerMainAPI(MultimoviesProvider())
        openSettings = { ctx ->
            (ctx as? androidx.appcompat.app.AppCompatActivity)?.let { activity ->
                MMSettingsFragment(this).show(activity.supportFragmentManager, "MultimoviesSettings")
            }
            kotlin.Unit
        }
    }
}
