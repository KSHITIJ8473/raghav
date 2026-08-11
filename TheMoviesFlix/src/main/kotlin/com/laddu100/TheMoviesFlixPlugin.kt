package com.laddu100

import android.content.Context
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class TheMoviesFlixPlugin : Plugin() {
    override fun load(context: Context) {
        initTMFCFBypass(context)
        registerMainAPI(TheMoviesFlix())
        registerExtractorAPI(FastDlExtractor())
        registerExtractorAPI(VCloudExtractor())
        registerExtractorAPI(GoFileExtractor())
        registerExtractorAPI(FileBeeExtractor())
        openSettings = { ctx ->
            (ctx as? androidx.appcompat.app.AppCompatActivity)?.let { activity ->
                TheMoviesFlixSettingsFragment(this).show(activity.supportFragmentManager, "TheMoviesFlixSettings")
            }
            kotlin.Unit
        }
    }

    companion object {
        private const val KEY_CF_COOKIES = "TMF_CF_COOKIES"
        private const val KEY_CF_UA = "TMF_CF_USER_AGENT"
        private const val KEY_CF_HOST = "TMF_CF_COOKIE_HOST"

        var cfCookies: String
            get() = try { CloudStreamApp.getKey<String>(KEY_CF_COOKIES) ?: "" } catch (e: Exception) { "" }
            set(value) { try { CloudStreamApp.setKey(KEY_CF_COOKIES, value) } catch (e: Exception) {} }

        var cfUserAgent: String
            get() = try { CloudStreamApp.getKey<String>(KEY_CF_UA) ?: "" } catch (e: Exception) { "" }
            set(value) { try { CloudStreamApp.setKey(KEY_CF_UA, value) } catch (e: Exception) {} }

        var cfCookieHost: String
            get() = try { CloudStreamApp.getKey<String>(KEY_CF_HOST) ?: "" } catch (e: Exception) { "" }
            set(value) { try { CloudStreamApp.setKey(KEY_CF_HOST, value) } catch (e: Exception) {} }
    }
}
