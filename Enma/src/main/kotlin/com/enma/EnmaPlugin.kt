package com.enma

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class EnmaPlugin : Plugin() {
    override fun load(context: Context) {
        // Store context for the WASM decryptor (initialized lazily on first API call)
        EnmaDecryptor.setContext(context)
        registerMainAPI(EnmaProvider())
    }
}
