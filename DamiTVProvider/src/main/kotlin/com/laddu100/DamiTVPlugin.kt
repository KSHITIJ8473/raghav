package com.laddu100

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

/**
 * CloudStream plugin entry-point for DamiTV.
 *
 * Registers the [DamiTVProvider] which scrapes live sports events
 * from dami-tv.pro (cricket, football, basketball, etc.).
 */
@CloudstreamPlugin
class DamiTVPlugin : Plugin() {

    override fun load(context: Context) {
        registerMainAPI(DamiTVProvider())
    }
}
