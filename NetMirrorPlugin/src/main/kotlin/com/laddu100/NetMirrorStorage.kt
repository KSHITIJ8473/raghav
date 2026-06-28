package com.laddu100

import android.content.Context
import android.content.SharedPreferences

object NetMirrorStorage {
    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        this.context = context.applicationContext
        this.prefs = context.getSharedPreferences("NetMirrorPrefs", Context.MODE_PRIVATE)
    }

    fun saveCookie(cookie: String) {
        val editor = prefs.edit()
        editor.putString("nm_cookie", cookie)
        editor.putLong("nm_cookie_time", System.currentTimeMillis())
        editor.apply()
    }

    fun getCookie(): Pair<String?, Long> {
        return Pair(
            prefs.getString("nm_cookie", null),
            prefs.getLong("nm_cookie_time", 0L)
        )
    }

    fun clearCookie() {
        val editor = prefs.edit()
        editor.remove("nm_cookie")
        editor.remove("nm_cookie_time")
        editor.apply()
    }
}
