package com.csksy.xanime

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class XanimePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Xanime())
    }
}
