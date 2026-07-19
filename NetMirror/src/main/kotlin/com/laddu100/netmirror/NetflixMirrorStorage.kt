package com.laddu100.netmirror

import android.content.Context
import android.content.SharedPreferences

object NetflixMirrorStorage {
    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        this.context = context.applicationContext
        this.prefs = context.getSharedPreferences("NetflixMirrorPrefs", Context.MODE_PRIVATE)
    }

    fun saveCookie(cookie: String) {
        val editor = prefs.edit()
        editor.putString("nf_cookie", cookie)
        editor.putLong("nf_cookie_timestamp", System.currentTimeMillis())
        editor.apply()
    }

    fun getCookie(): Pair<String?, Long> {
        return Pair(
            prefs.getString("nf_cookie", null),
            prefs.getLong("nf_cookie_timestamp", 0L)
        )
    }

    fun clearCookie() {
        val editor = prefs.edit()
        editor.remove("nf_cookie")
        editor.remove("nf_cookie_timestamp")
        editor.apply()
    }

    fun saveUserToken(ott: String, token: String) {
        prefs.edit().apply {
            putString("usertoken_$ott", token)
            putLong("usertoken_timestamp_$ott", System.currentTimeMillis())
            apply()
        }
    }

    fun getUserToken(ott: String): Pair<String?, Long> = Pair(
        prefs.getString("usertoken_$ott", null),
        prefs.getLong("usertoken_timestamp_$ott", 0L)
    )

    fun clearUserToken(ott: String) {
        prefs.edit().apply {
            remove("usertoken_$ott")
            remove("usertoken_timestamp_$ott")
            apply()
        }
    }

    fun saveApiBase(apiBase: String) {
        prefs.edit().apply {
            putString("newtv_api_base", apiBase)
            putLong("newtv_api_base_ts", System.currentTimeMillis())
            apply()
        }
    }

    fun getApiBase(): Pair<String?, Long> = Pair(
        prefs.getString("newtv_api_base", null),
        prefs.getLong("newtv_api_base_ts", 0L)
    )
}
