package com.laddu100.raghavanime

import android.app.ActivityManager
import android.content.Context
import com.lagradost.cloudstream3.CommonActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

object RaghavPerf {

    enum class DeviceProfile {
        LOW_END, MID_RANGE, HIGH_END
    }

    @Volatile
    private var cachedProfile: DeviceProfile? = null

    fun profile(): DeviceProfile {
        cachedProfile?.let { return it }
        val cores = Runtime.getRuntime().availableProcessors()
        val ramMb = runCatching {
            val am = CommonActivity.activity
                ?.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return@runCatching 0L
            val info = ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            info.totalMem / (1024L * 1024L)
        }.getOrDefault(0L)
        val p = when {
            (ramMb > 0 && ramMb < 2048) || cores < 4 -> DeviceProfile.LOW_END
            (ramMb > 0 && ramMb < 4096) || cores < 6 -> DeviceProfile.MID_RANGE
            else -> DeviceProfile.HIGH_END
        }
        cachedProfile = p
        return p
    }

    fun sourceConcurrency(): Int = when (profile()) {
        DeviceProfile.LOW_END -> 8
        DeviceProfile.MID_RANGE -> 12
        DeviceProfile.HIGH_END -> 16
    }

    fun prefetchConcurrency(): Int = when (profile()) {
        DeviceProfile.LOW_END -> 2
        DeviceProfile.MID_RANGE -> 3
        DeviceProfile.HIGH_END -> 4
    }

    suspend fun runLimitedAsync(concurrency: Int, tasks: List<suspend () -> Unit>) {
        if (tasks.isEmpty()) return
        if (tasks.size == 1) {
            tasks.first().invoke()
            return
        }
        val gate = Semaphore(concurrency.coerceAtLeast(1))
        coroutineScope {
            tasks.map { task ->
                async(Dispatchers.IO) {
                    gate.withPermit {
                        try {
                            task()
                        } catch (c: CancellationException) {
                            throw c
                        } catch (_: Throwable) {
                            // a dead source must not cancel the rest of the wave
                        }
                    }
                }
            }.awaitAll()
        }
    }

    // every WebView costs a renderer process, so at most two run at once no
    // matter how many sources are resolving in parallel
    private val webViewGate = Semaphore(2)

    suspend fun <T> withWebView(block: suspend () -> T): T {
        webViewGate.acquire()
        try {
            return block()
        } finally {
            webViewGate.release()
        }
    }
}

object RaghavSourceStats {

    private const val PREFS = "raghavanime_perf"
    private const val KEY_PREFIX = "source_stats_"
    private const val BROKEN_AFTER_FAILS = 5

    private class Stat {
        var success = 0
        var fail = 0
        var totalMs = 0L
        var consecutiveFails = 0
    }

    private val stats = ConcurrentHashMap<String, Stat>()
    private val lock = Any()

    @Volatile
    private var loaded = false

    private fun prefs() = CommonActivity.activity?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            val saved = runCatching { prefs()?.all }.getOrNull()
            if (saved != null) {
                for ((key, value) in saved) {
                    if (!key.startsWith(KEY_PREFIX) || value !is String) continue
                    val parts = value.split(",")
                    if (parts.size < 4) continue
                    val s = Stat()
                    s.success = parts[0].toIntOrNull() ?: 0
                    s.fail = parts[1].toIntOrNull() ?: 0
                    s.totalMs = parts[2].toLongOrNull() ?: 0L
                    s.consecutiveFails = parts[3].toIntOrNull() ?: 0
                    stats[key.removePrefix(KEY_PREFIX)] = s
                }
            }
            loaded = true
        }
    }

    private fun persist() {
        val p = prefs() ?: return
        val editor = p.edit()
        for ((name, s) in stats) {
            editor.putString(KEY_PREFIX + name, "${s.success},${s.fail},${s.totalMs},${s.consecutiveFails}")
        }
        editor.apply()
    }

    fun record(source: String, success: Boolean, durationMs: Long) {
        ensureLoaded()
        synchronized(lock) {
            val s = stats.getOrPut(source) { Stat() }
            if (success) {
                // one success clears the failure history so a recovered source
                // climbs back to the front of the queue right away
                s.success++
                s.fail = 0
                s.totalMs += durationMs
                s.consecutiveFails = 0
            } else {
                // one failure wipes the timing history so a freshly dead source
                // stops holding an early slot
                s.fail++
                s.success = 0
                s.totalMs = 0L
                s.consecutiveFails++
            }
        }
        persist()
    }

    fun priority(source: String): Double {
        ensureLoaded()
        val s = stats[source] ?: return 0.0
        // still queued, only pushed to the back
        if (s.consecutiveFails >= BROKEN_AFTER_FAILS) return -1000.0
        val runs = s.success + s.fail
        if (runs <= 0) return 0.0
        val successRate = s.success.toDouble() / runs
        val avgSec = if (s.success > 0) s.totalMs / s.success / 1000.0 else 0.0
        return successRate * 100.0 - avgSec
    }
}
