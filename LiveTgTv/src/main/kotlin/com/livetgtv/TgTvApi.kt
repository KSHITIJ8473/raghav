package com.livetgtv

import com.lagradost.cloudstream3.app
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

object TgTvApi {

    const val SITE = "https://livetgtv.lovable.app"
    const val CHANNELS_URL = "https://jjtvxweb.pages.dev/jstr4web.json"
    const val COOKIE_PRIMARY = "https://allinonereborn2.online/jstrweb2/cookies.json"
    const val COOKIE_STAR = "https://allinonereborn2.online/jtv-fetch/jstarcookie/cookie.json"

    // function id baked into the bundle the site ships today
    private const val KNOWN_FN = "211ba18c9fcbaf4e68e5bb262905070b60b7ed4ffe802d16fbbed00150f1c024"

    const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.0.0 Safari/537.36"

    val siteHeaders = mapOf(
        "User-Agent" to UA,
        "Accept" to "*/*",
        "Origin" to SITE,
        "Referer" to "$SITE/"
    )

    // jiotvmblive refuses the old jiotv app headers, the same url and
    // token only answers for a browser style request like the site's own
    val playerHeaders = mapOf(
        "User-Agent" to UA,
        "Accept" to "*/*",
        "Origin" to SITE
    )

    private const val CHANNELS_TTL = 10L * 60L * 1000L

    @Volatile
    private var channelsCache: List<JSONObject> = emptyList()

    @Volatile
    private var channelsAt = 0L

    // the raw file is a json array, the site wraps it when it is not
    suspend fun channels(): List<JSONObject> {
        val now = System.currentTimeMillis()
        if (channelsCache.isNotEmpty() && now - channelsAt < CHANNELS_TTL) return channelsCache
        return try {
            val text = app.get(CHANNELS_URL, headers = mapOf("User-Agent" to UA), timeout = 20L).text
            val trimmed = text.trim()
            val arr = if (trimmed.startsWith("[")) JSONArray(trimmed) else JSONArray("[$trimmed]")
            val out = mutableListOf<JSONObject>()
            for (i in 0 until arr.length()) {
                val c = arr.optJSONObject(i) ?: continue
                if (c.optString("id").isBlank() || c.optString("name").isBlank()) continue
                out.add(c)
            }
            channelsCache = out
            channelsAt = now
            out
        } catch (_: Exception) {
            channelsCache
        }
    }

    suspend fun channelById(id: String): JSONObject? =
        channels().firstOrNull { it.optString("id") == id }

    @Volatile
    private var fnHash: String? = null

    // the function id rotates when the site redeploys - the known id first,
    // rediscovered from the live bundle once it stops answering
    suspend fun serverFn(id: String): JSONObject? {
        val first = fnHash ?: KNOWN_FN
        fetchServerFn(first, id)?.let { return it }
        val discovered = discoverFnHash() ?: return null
        if (discovered == first) return null
        fnHash = discovered
        return fetchServerFn(discovered, id)
    }

    private suspend fun fetchServerFn(hash: String, id: String): JSONObject? {
        return try {
            val text = app.get(
                "$SITE/_serverFn/$hash?payload=${buildPayload(id)}",
                headers = siteHeaders + mapOf("Referer" to "$SITE/watch/$id"),
                // fronts a live jiotv fetch that regularly answers in 10-15s
                timeout = 30L
            ).text
            if (text.isBlank()) null else decodeEnvelope(text)
        } catch (_: Exception) {
            null
        }
    }

    private fun buildPayload(id: String): String {
        val inner = JSONObject().put("t", 1).put("s", id)
        val arg = JSONObject()
            .put("t", 10)
            .put("i", 1)
            .put(
                "p",
                JSONObject()
                    .put("k", JSONArray().put("id"))
                    .put("v", JSONArray().put(inner))
            )
            .put("o", 0)
        val envelope = JSONObject()
            .put("t", 10)
            .put("i", 0)
            .put(
                "p",
                JSONObject()
                    .put("k", JSONArray().put("data"))
                    .put("v", JSONArray().put(arg))
            )
            .put("o", 0)
        val root = JSONObject()
            .put("t", envelope)
            .put("f", 127)
            .put("m", JSONArray())
        return java.net.URLEncoder.encode(root.toString(), "UTF-8")
    }

    private fun decodeEnvelope(text: String): JSONObject? {
        return try {
            val decoded = decodeNode(JSONObject(text))
            (decoded as? JSONObject)?.optJSONObject("result")
        } catch (_: Exception) {
            null
        }
    }

    // tagged-value tree: t 1 string, t 2 number, t 9 array, t 10 object, t 11 null
    private fun decodeNode(node: Any?): Any? {
        return when (node) {
            is JSONObject -> when (node.optInt("t", -1)) {
                1 -> node.optString("s")
                2 -> node.opt("s") ?: JSONObject.NULL
                9 -> {
                    val out = JSONArray()
                    val a = node.optJSONArray("a")
                    if (a != null) for (i in 0 until a.length()) out.put(decodeNode(a.opt(i)) ?: JSONObject.NULL)
                    out
                }
                10 -> {
                    val p = node.optJSONObject("p") ?: return JSONObject.NULL
                    val out = JSONObject()
                    val keys = p.optJSONArray("k")
                    val values = p.optJSONArray("v")
                    if (keys != null && values != null) {
                        for (i in 0 until keys.length()) {
                            if (i >= values.length()) break
                            out.put(keys.optString(i), decodeNode(values.opt(i)) ?: JSONObject.NULL)
                        }
                    }
                    out
                }
                11 -> JSONObject.NULL
                else -> JSONObject.NULL
            }
            else -> JSONObject.NULL
        }
    }

    // the reference moved across deploys (entry js -> watch route chunk), so
    // every page that has carried it is walked
    private suspend fun discoverFnHash(): String? {
        val serversJs = try {
            val pages = mutableListOf<String>()
            for (pageUrl in listOf(SITE, "$SITE/watch/146")) {
                runCatching {
                    pages.add(app.get(pageUrl, headers = mapOf("User-Agent" to UA), timeout = 15L).text)
                }
            }
            val entryJs = pages.flatMap { html ->
                Regex("""(/assets/index-[A-Za-z0-9._-]+\.js)""").findAll(html)
                    .map { it.groupValues[1] }.distinct().toList()
            }.firstOrNull()?.let { path ->
                runCatching {
                    app.get("$SITE$path", headers = mapOf("User-Agent" to UA), timeout = 15L).text
                }.getOrNull()
            }
            if (entryJs != null) pages.add(entryJs)
            val chunk = pages.flatMap { html ->
                Regex("""(/assets/servers-[A-Za-z0-9._-]+\.js)""").findAll(html)
                    .map { it.groupValues[1] }.distinct().toList()
            }.firstOrNull() ?: return null
            app.get("$SITE$chunk", headers = mapOf("User-Agent" to UA), timeout = 15L).text
        } catch (_: Exception) {
            return null
        }
        return Regex("[0-9a-f]{64}").find(serversJs)?.value
    }

    // shared by every channel, so both endpoints are fetched once per ttl -
    // an interstitial answer from some networks just means no token
    private const val COOKIE_TTL = 4L * 60L * 1000L

    private val endpointCache = ConcurrentHashMap<String, Pair<String, Long>>()

    private suspend fun endpointText(url: String): String {
        val cached = endpointCache[url]
        val now = System.currentTimeMillis()
        if (cached != null && now - cached.second < COOKIE_TTL) return cached.first
        return try {
            val text = app.get(
                url,
                headers = mapOf(
                    "User-Agent" to UA,
                    "Accept" to "application/json",
                    "Referer" to "https://jjtvxweb.pages.dev/"
                ),
                timeout = 15L
            ).text
            if (text.isNotBlank()) {
                endpointCache[url] = text to now
                text
            } else {
                cached?.first ?: ""
            }
        } catch (_: Exception) {
            cached?.first ?: ""
        }
    }

    // the first entries are update stamps without a cookie
    suspend fun primaryCookie(): String {
        val text = endpointText(COOKIE_PRIMARY)
        if (text.isBlank()) return ""
        return try {
            val arr = JSONArray(text.trim())
            var out = ""
            for (i in 0 until arr.length()) {
                val e = arr.optJSONObject(i) ?: continue
                val c = e.optString("cookie")
                if (c.isNotBlank()) {
                    out = c
                    break
                }
            }
            out
        } catch (_: Exception) {
            ""
        }
    }

    // both result buckets can carry the final url with the token in it
    suspend fun starCookie(id: String): String {
        val text = endpointText(COOKIE_STAR)
        if (text.isBlank()) return ""
        return try {
            val root = JSONObject(text.trim())
            var out = ""
            val buckets = listOf(root.optJSONArray("successful_results"), root.optJSONArray("failed_results"))
            outer@ for (bucket in buckets) {
                if (bucket == null) continue
                for (i in 0 until bucket.length()) {
                    val e = bucket.optJSONObject(i) ?: continue
                    if (e.optString("channel_id") != id) continue
                    val details = e.optJSONObject("error_details")
                    val finalUrl = e.optString("final_url").ifBlank { details?.optString("final_url") ?: "" }
                    val m = Regex("[?&]__hdnea__=([^&]+)").find(finalUrl)
                        ?: Regex("__hdnea__=([^&]+)").find(finalUrl)
                    if (m != null) {
                        out = "__hdnea__=" + m.groupValues[1]
                        break@outer
                    }
                }
            }
            out
        } catch (_: Exception) {
            ""
        }
    }
}
