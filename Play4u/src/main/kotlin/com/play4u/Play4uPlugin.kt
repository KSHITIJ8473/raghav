package com.play4u

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class Play4uPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Play4uProvider())
    }
}
