package com.laddu100.netmirror

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
open class CNCVersePlugin : Plugin() {
    override fun load(context: Context) {
        NetflixMirrorStorage.init(context.applicationContext)
        NetflixMirrorProvider.context = context
        PrimeVideoMirrorProvider.context = context
        HotStarMirrorProvider.context = context
        DisneyPlusProvider.context = context
        registerMainAPI(NetflixMirrorProvider())
        registerMainAPI(PrimeVideoMirrorProvider())
        registerMainAPI(HotStarMirrorProvider())
        registerMainAPI(DisneyPlusProvider())
    }
}
