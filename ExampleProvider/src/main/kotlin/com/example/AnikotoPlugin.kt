package com.example

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class AnikotoPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(AnikotoProvider())
    }
}
