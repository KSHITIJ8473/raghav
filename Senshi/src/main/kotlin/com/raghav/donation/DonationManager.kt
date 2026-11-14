package com.raghav.donation

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale

object DonationManager {

    private const val PREFS_NAME = "raghav_donation_prefs"
    private const val KEY_LAST_SHOWN = "raghav_donation_last_shown"
    private const val KEY_CACHED_GOAL = "raghav_donation_goal_cache"
    private const val DIALOG_TAG = "raghav_donation_dialog"

    private const val COOLDOWN_HOURS = 24L
    private const val GOAL_URL =
        "https://raw.githubusercontent.com/KSHITIJ8473/raghav/master/goal.json"
    private const val GOAL_TIMEOUT_MS = 4000L
    private const val DEFAULT_TARGET = 40.0

    data class Goal(
        val raised: Double,
        val target: Double,
        val currency: String,
        val donor: String,
        val donation: String
    ) {
        val achieved: Boolean get() = target > 0.0 && raised >= target
        val percent: Int
            get() = if (target > 0.0) (raised / target * 100.0).toInt().coerceIn(0, 100) else 0
    }

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
                val goal = loadGoal(context)
                waitForActivityAndShow(context, goal)
            } finally {
                isLaunching = false
            }
        }
    }

    fun onDialogDismissed() {
        isDialogShowing = false
    }

    private suspend fun loadGoal(context: Context): Goal {
        val live = withTimeoutOrNull(GOAL_TIMEOUT_MS) {
            withContext(Dispatchers.IO) {
                try {
                    val body = app.get(GOAL_URL).text
                    if (JSONObject(body).optString("month") == monthKey()) body else null
                } catch (e: Exception) {
                    null
                }
            }
        }
        if (live != null) {
            prefs(context).edit().putString(KEY_CACHED_GOAL, live).apply()
            return parseGoal(live)
        }
        val cached = prefs(context).getString(KEY_CACHED_GOAL, null) ?: return fallbackGoal()
        return parseGoal(cached)
    }

    private fun parseGoal(raw: String): Goal {
        return try {
            val obj = JSONObject(raw)
            if (obj.optString("month") != monthKey()) {
                fallbackGoal()
            } else {
                val target = obj.optDouble("targetAmount", DEFAULT_TARGET)
                Goal(
                    obj.optDouble("currentAmount", 0.0),
                    if (target > 0.0) target else DEFAULT_TARGET,
                    obj.optString("currency", "$"),
                    obj.optString("lastDonor", ""),
                    obj.optString("lastDonation", "")
                )
            }
        } catch (e: Exception) {
            fallbackGoal()
        }
    }

    private fun fallbackGoal() = Goal(0.0, DEFAULT_TARGET, "$", "", "")

    private fun monthKey(): String {
        val cal = Calendar.getInstance()
        return String.format(
            Locale.US,
            "%04d-%02d",
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH) + 1
        )
    }

    private suspend fun waitForActivityAndShow(context: Context, goal: Goal) {
        repeat(6) {
            if (isCooldownActive(context)) return
            val activity = CommonActivity.activity as? AppCompatActivity
            if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
                val fm = activity.supportFragmentManager
                if (!fm.isDestroyed && fm.findFragmentByTag(DIALOG_TAG) == null) {
                    isDialogShowing = true
                    recordShown(context)
                    val args = Bundle().apply {
                        putDouble("raised", goal.raised)
                        putDouble("target", goal.target)
                        putString("currency", goal.currency)
                        putString("donor", goal.donor)
                        putString("donation", goal.donation)
                    }
                    fm.beginTransaction()
                        .add(DonationDialogFragment().apply { arguments = args }, DIALOG_TAG)
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
