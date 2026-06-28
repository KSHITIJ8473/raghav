package com.laddu100

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
open class NetMirrorPlugin : Plugin() {
    override fun load(context: Context) {
        NetMirrorStorage.init(context.applicationContext)
        registerMainAPI(NetflixMirrorProvider())
        registerMainAPI(PrimeVideoMirrorProvider())
        registerMainAPI(HotStarMirrorProvider())
        registerMainAPI(DisneyPlusProvider())
    }
}
