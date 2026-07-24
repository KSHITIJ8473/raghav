package com.laddu100.netmirror

import android.content.Context
import com.lagradost.api.Log
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

/**
 * Entry point for the NetMirror plugin. Registers four providers:
 *   - Netflix        (ott = nf)
 *   - PrimeVideo     (ott = pv)
 *   - Hotstar        (ott = hs)
 *   - Disney         (ott = dp, studio = disney)
 *
 * No settings UI, no sub-plugins, no studio selector — just the four
 * top-level providers.
 */
@CloudstreamPlugin
class NetMirrorPlugin : Plugin() {
    override fun load(context: Context) {
        try {
            Log.d("NetMirror", "Plugin.load() START")
            NetflixMirrorStorage.init(context.applicationContext)
            NetMirrorBaseProvider.context = context
            registerMainAPI(NetflixMirrorProvider())
            registerMainAPI(PrimeVideoMirrorProvider())
            registerMainAPI(HotStarMirrorProvider())
            registerMainAPI(DisneyPlusProvider())
            Log.d("NetMirror", "Plugin.load() SUCCESS — 4 providers registered")
        } catch (e: Throwable) {
            Log.e("NetMirror", "Plugin.load() FAILED: ${e.message}", e)
            throw e
        }
    }
}
