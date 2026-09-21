package com.netnaija

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class NetNaijaPlugin : BasePlugin() {
    override fun load() {
        val api = NetNaija()
        registerMainAPI(api)
        api.warmUp()
    }
}
