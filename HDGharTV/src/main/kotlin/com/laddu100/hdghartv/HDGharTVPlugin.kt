package com.laddu100.hdghartv

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class HDGharTVPlugin : Plugin() {
    override fun load() {
        registerMainAPI(HDGharTVProvider())
    }
}
