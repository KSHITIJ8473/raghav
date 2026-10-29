package com.netnaija.app

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.netnaija.app.settings.NetNaijaAppSettingsFragment

@CloudstreamPlugin
class NetNaijaAppPlugin : Plugin() {
    override fun load(context: Context) {
        val sharedPref = context.getSharedPreferences("NetNaijaApp", 0)
        registerMainAPI(NetNaijaApp(sharedPref))
        this.openSettings = { ctx ->
            val activity = getActivity(ctx)
            if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
                try {
                    NetNaijaAppSettingsFragment(this, sharedPref).show(
                        activity.supportFragmentManager,
                        "NetNaijaAppSettings"
                    )
                } catch (e: Throwable) {
                    // a dead activity rejects the transaction, settings stay
                    // closed until the next open
                }
            }
        }
    }

    // settings open from arbitrary view contexts, walk the wrapper chain
    // for the activity
    private fun getActivity(context: Context): AppCompatActivity? {
        var current = context
        while (current is ContextWrapper) {
            if (current is AppCompatActivity) return current
            current = current.baseContext
        }
        return null
    }
}
