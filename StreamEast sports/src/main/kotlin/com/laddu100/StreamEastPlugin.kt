package com.laddu100

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

/**
 * CloudStream plugin entry-point for StreamEast.
 *
 * Registers the [StreamEastProvider] which scrapes live and upcoming sports
 * events from istreameast.app.
 */
@CloudstreamPlugin
class StreamEast sports : Plugin() {

    override fun load(context: Context) {
        registerMainAPI(StreamEastProvider())
    }
}
