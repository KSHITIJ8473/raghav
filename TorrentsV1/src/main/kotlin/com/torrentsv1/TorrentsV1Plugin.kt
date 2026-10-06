package com.torrentsv1

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class TorrentsV1Plugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(TorrentsV1())
        openSettings = { ctx ->
            try {
                TorrentsSettings.show(ctx)
            } catch (_: Exception) {}
        }
    }
}
