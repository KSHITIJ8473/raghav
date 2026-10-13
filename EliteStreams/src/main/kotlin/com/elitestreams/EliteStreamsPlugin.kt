package com.elitestreams

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class EliteStreamsPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(EliteStreams())
        openSettings = { ctx ->
            try {
                EsSettings.show(ctx)
            } catch (_: Exception) {}
        }
    }
}
