package com.torrentsv1

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class TorrentsV1Plugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(TorrentsV1())
        this.openSettings = { ctx ->
            val activity = ctx as? AppCompatActivity
            if (activity != null) {
                val frag = SettingsFragment(this)
                frag.show(activity.supportFragmentManager, "TorrentsV1Settings")
            }
        }
    }
}
