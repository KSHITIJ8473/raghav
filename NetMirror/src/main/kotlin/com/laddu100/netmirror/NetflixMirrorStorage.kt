package com.laddu100.netmirror

import android.content.Context
import android.content.SharedPreferences

/**
 * SharedPreferences-backed storage for the netmirror bypass cookie.
 *
 * Mirrors the structure used by the upstream mobile plugin:
 *   - Prefs name: "NetflixMirrorPrefsMobile"
 *   - Keys: nf_cookie / nf_cookie_timestamp
 *   - Full cookie (Set-Cookie raw value) also stored for the m3u8 `in=` parameter
 */
object NetflixMirrorStorage {
    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        this.context = context.applicationContext
        this.prefs = context.getSharedPreferences("NetflixMirrorPrefsMobile", Context.MODE_PRIVATE)
    }

    // ---- t_hash_t cookie (the short hash used as Cookie: t_hash_t=...) ----

    fun saveCookie(cookie: String) {
        prefs.edit().apply {
            putString("nf_cookie", cookie)
            putLong("nf_cookie_timestamp", System.currentTimeMillis())
            apply()
        }
    }

    fun getCookie(): Pair<String?, Long> {
        return Pair(
            prefs.getString("nf_cookie", null),
            prefs.getLong("nf_cookie_timestamp", 0L)
        )
    }

    fun clearCookie() {
        prefs.edit().apply {
            remove("nf_cookie")
            remove("nf_cookie_timestamp")
            apply()
        }
    }

    // ---- Full cookie (raw Set-Cookie value, used for m3u8 `in=` param) ----

    fun saveFullCookie(cookie: String) {
        prefs.edit().apply {
            putString("nf_cookie_full", cookie)
            putLong("nf_cookie_full_timestamp", System.currentTimeMillis())
            apply()
        }
    }

    fun getFullCookie(): Pair<String?, Long> {
        return Pair(
            prefs.getString("nf_cookie_full", null),
            prefs.getLong("nf_cookie_full_timestamp", 0L)
        )
    }

    fun clearFullCookie() {
        prefs.edit().apply {
            remove("nf_cookie_full")
            remove("nf_cookie_full_timestamp")
            apply()
        }
    }
}
