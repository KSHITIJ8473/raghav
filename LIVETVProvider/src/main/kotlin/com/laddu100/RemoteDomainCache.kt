package com.laddu100

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ExpiringRemoteValue(
    private val ttlMs: Long = 1_800_000L,
    private val fallbackTtlMs: Long = 120_000L,
    private val minRetryMs: Long = 60_000L,
) {
    private val mutex = Mutex()

    @Volatile
    var value: String? = null
        private set

    @Volatile
    private var expiresAt = 0L

    @Volatile
    private var lastAttempt = 0L

    val peek: String?
        get() = value

    suspend fun get(
        fetch: suspend () -> String?,
        fallback: suspend () -> String,
    ): String {
        peek?.let { cached ->
            if (System.currentTimeMillis() < expiresAt) return cached
        }
        return mutex.withLock {
            val now = System.currentTimeMillis()
            val current = value
            val fresh = current != null && now < expiresAt
            if (fresh && now - lastAttempt < minRetryMs) {
                return@withLock current!!
            }
            lastAttempt = now
            val fetched = fetch()
            if (!fetched.isNullOrBlank()) {
                value = fetched
                expiresAt = now + ttlMs
                return@withLock fetched
            }
            if (current == null) {
                val result = fallback()
                value = result
                expiresAt = now + fallbackTtlMs
                return@withLock result
            }
            current
        }
    }

    fun invalidate() {
        expiresAt = 0L
    }

    fun reset() {
        expiresAt = 0L
        lastAttempt = 0L
    }
}

class StaleDomainInterceptor(private val domain: ExpiringRemoteValue) : okhttp3.Interceptor {
    override fun intercept(chain: okhttp3.Interceptor.Chain): okhttp3.Response {
        val host = chain.request().url.host
        val cached = domain.peek
        val ours = cached?.let {
            okhttp3.HttpUrl.Companion.run { it.toHttpUrlOrNull() }?.host?.equals(host, ignoreCase = true)
        } == true
        return try {
            val response = chain.proceed(chain.request())
            if (ours && response.code >= 400) {
                domain.invalidate()
            }
            response
        } catch (e: java.io.IOException) {
            if (ours) {
                domain.invalidate()
            }
            throw e
        }
    }
}
