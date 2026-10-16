package com.raghav.donation

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.CommonActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object DonationManager {

    private const val PREFS_NAME = "raghav_donation_prefs"
    private const val KEY_LAST_SHOWN = "raghav_donation_last_shown"
    private const val DIALOG_TAG = "raghav_donation_dialog"

    // one dialog per day across every extension of the repo
    private const val COOLDOWN_HOURS = 24L

    private val gateLock = Any()

    @Volatile
    private var isLaunching = false

    @Volatile
    private var isDialogShowing = false

    fun checkAndShow() {
        val context = CommonActivity.activity?.applicationContext ?: return
        synchronized(gateLock) {
            if (isLaunching || isDialogShowing || isCooldownActive(context)) return
            isLaunching = true
        }
        CoroutineScope(Dispatchers.Main).launch {
            try {
                waitForActivityAndShow(context)
            } finally {
                isLaunching = false
            }
        }
    }

    fun onDialogDismissed() {
        isDialogShowing = false
    }

    private suspend fun waitForActivityAndShow(context: Context) {
        repeat(6) {
            // another plugin can have shown the dialog while this one was
            // still waiting for the activity, the shared cooldown catches that
            if (isCooldownActive(context)) return
            val activity = CommonActivity.activity as? AppCompatActivity
            if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
                val fm = activity.supportFragmentManager
                if (!fm.isDestroyed && fm.findFragmentByTag(DIALOG_TAG) == null) {
                    isDialogShowing = true
                    recordShown(context)
                    fm.beginTransaction()
                        .add(DonationDialogFragment(), DIALOG_TAG)
                        .commitAllowingStateLoss()
                }
                return
            }
            delay(500L)
        }
    }

    private fun isCooldownActive(context: Context): Boolean {
        val lastShown = prefs(context).getLong(KEY_LAST_SHOWN, 0L)
        if (lastShown <= 0L) return false
        return System.currentTimeMillis() - lastShown < COOLDOWN_HOURS * 60L * 60L * 1000L
    }

    private fun recordShown(context: Context) {
        prefs(context).edit().putLong(KEY_LAST_SHOWN, System.currentTimeMillis()).apply()
    }

    private fun prefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}
