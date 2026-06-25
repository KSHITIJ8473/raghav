package com.laddu100

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

/**
 * Entry point for the Streamed.pk sports streaming plugin.
 */
@CloudstreamPlugin
class StreamedPkPlugin : Plugin() {

    override fun load(context: Context) {
        registerMainAPI(StreamedPkProvider())
    }
}
