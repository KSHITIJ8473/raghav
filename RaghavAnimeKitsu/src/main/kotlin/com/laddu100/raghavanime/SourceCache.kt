package com.laddu100.raghavanime

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

object SourceCache {

    private const val FOUND_TTL = 15 * 60 * 1000L
    private const val MISSING_TTL = 5 * 60 * 1000L
    private const val MAX_ENTRIES = 400

    class Match(val episodes: Map<Int, String>, val hasEpisode: Boolean)

    private class Entry(val episodes: Map<Int, String>, val absent: MutableSet<Int>, val time: Long) {
        fun fresh(): Boolean {
            val ttl = if (episodes.isNotEmpty()) FOUND_TTL else MISSING_TTL
            return System.currentTimeMillis() - time < ttl
        }
    }

    private val cache = ConcurrentHashMap<String, Entry>()
    private val locks = ConcurrentHashMap<String, Mutex>()

    private fun key(provider: String, anime: String, isDub: Boolean): String {
        return "$provider|$anime|${if (isDub) "dub" else "sub"}"
    }

    suspend fun episodeData(
        provider: String,
        anime: String,
        isDub: Boolean,
        episode: Int,
        resolve: suspend () -> Match?
    ): String? {
        val k = key(provider, anime, isDub)
        val lock = locks.computeIfAbsent(k) { Mutex() }
        lock.withLock {
            val cached = cache[k]
            if (cached != null && cached.fresh()) {
                cached.episodes[episode]?.let { return it }
                if (cached.episodes.isEmpty() || episode in cached.absent) return null
            }
            val match = resolve() ?: run {
                store(k, Entry(emptyMap(), mutableSetOf(), System.currentTimeMillis()))
                return null
            }
            if (match.hasEpisode) {
                store(k, Entry(match.episodes, mutableSetOf(), System.currentTimeMillis()))
                return match.episodes[episode]
            }
            store(k, Entry(match.episodes, mutableSetOf(episode), System.currentTimeMillis()))
            return null
        }
    }

    suspend fun warm(
        provider: String,
        anime: String,
        isDub: Boolean,
        resolve: suspend () -> Map<Int, String>?
    ) {
        val k = key(provider, anime, isDub)
        val lock = locks.computeIfAbsent(k) { Mutex() }
        lock.withLock {
            val cached = cache[k]
            if (cached != null && cached.fresh()) return
            store(k, Entry(resolve() ?: emptyMap(), mutableSetOf(), System.currentTimeMillis()))
        }
    }

    private fun store(k: String, entry: Entry) {
        if (cache.size >= MAX_ENTRIES) {
            cache.entries.sortedBy { it.value.time }.take(MAX_ENTRIES / 4).forEach { cache.remove(it.key) }
        }
        cache[k] = entry
    }
}
