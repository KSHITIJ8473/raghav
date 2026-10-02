package com.csksy.xanime

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.CloudflareKiller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal object XanimeApi {

    private const val TAG = "Xanime"
    private const val DEFAULT_HOST = "https://xanime.me"
    private const val API_PATH = "/z2/"

    // the site bundles this string and derives the AES key from it, swap it if they rotate
    private const val OBFUSCATION_SECRET = "xanime-ph25-obfuscation-secret-key-2026"

    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    private const val PAGE_SIZE = 24
    private const val EP_PAGE_SIZE = 60

    @Volatile
    private var host = DEFAULT_HOST

    suspend fun refreshDomain() {
        FirebaseDomainHelper.getDomain("xanime")?.let { host = it }
    }

    fun host(): String = host

    private fun endpoint(): String = host + API_PATH

    private val aesKey by lazy {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(OBFUSCATION_SECRET.toByteArray(Charsets.UTF_8))
        SecretKeySpec(digest, "AES")
    }

    private val random = SecureRandom()

    private fun seal(payload: JSONObject): Map<String, Any> {
        val iv = ByteArray(12).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, aesKey, GCMParameterSpec(128, iv))
        val ct = cipher.doFinal(payload.toString().toByteArray(Charsets.UTF_8))
        return mapOf(
            "v" to 1,
            "iv" to Base64.encodeToString(iv, Base64.NO_WRAP),
            "ct" to Base64.encodeToString(ct, Base64.NO_WRAP)
        )
    }

    private fun open(envelope: JSONObject): JSONObject? {
        val iv = Base64.decode(envelope.optString("iv"), Base64.DEFAULT)
        val ct = Base64.decode(envelope.optString("ct"), Base64.DEFAULT)
        if (iv.isEmpty() || ct.isEmpty()) return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, aesKey, GCMParameterSpec(128, iv))
            JSONObject(String(cipher.doFinal(ct), Charsets.UTF_8))
        } catch (e: Exception) {
            null
        }
    }

    private fun baseHeaders(): Map<String, String> = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "application/json",
        "Content-Type" to "application/json",
        "Origin" to host,
        "Referer" to "$host/"
    )

    private val killerMap = ConcurrentHashMap<String, CloudflareKiller>()

    private fun killerFor(url: String): CloudflareKiller =
        killerMap.getOrPut(java.net.URI(url).host ?: host) { CloudflareKiller() }

    private fun parseBody(text: String): JSONObject? {
        val obj = try {
            JSONObject(text)
        } catch (e: Exception) {
            return null
        }
        return if (obj.has("ct")) open(obj) else obj
    }

    private suspend fun post(payload: Map<String, Any>): JSONObject? {
        val resp = try {
            app.post(endpoint(), json = payload, headers = baseHeaders())
        } catch (e: Exception) {
            null
        } ?: return null
        if (resp.code !in 200..399) return null
        return parseBody(resp.text)
    }

    // cloudflare sometimes challenges the app client, fall back to a real webview
    private suspend fun postViaKiller(payload: Map<String, Any>): JSONObject? =
        withContext(Dispatchers.Main) {
            try {
                val killer = killerFor(endpoint())
                val resp = app.post(
                    endpoint(), json = payload, headers = baseHeaders(),
                    interceptor = killer
                )
                if (resp.code !in 200..399) null else parseBody(resp.text)
            } catch (e: Exception) {
                Log.d(TAG, "cloudflare fallback failed: ${e.message}")
                null
            }
        }

    // graphql variables are matched by exact name, keep the select key lowercase
    private suspend fun query(query: String, variables: JSONObject): JSONObject? {
        val payload = seal(JSONObject().put("query", query).put("variables", variables))
        var result = post(payload) ?: post(payload)
        if (result == null) result = postViaKiller(payload)
        if (result == null) return null
        if (result.has("errors")) return null
        val data = result.optJSONObject("data") ?: return null
        val node = data.keys().asSequence().firstOrNull { key ->
            key.startsWith("get_") || key.startsWith("set_") || key.startsWith("put_") ||
                key.startsWith("mut_")
        } ?: return null
        return data.optJSONObject(node)
    }

    class TitleEntry(
        val aniId: String,
        val title: String,
        val poster: String?,
        val year: Int?,
        val types: List<String>,
        val audio: Set<String>
    )

    class EpisodeEntry(
        val epId: String,
        val index: Int,
        val title: String,
        val audio: Set<String>
    )

    class Track(val label: String, val url: String)

    class Source(
        val name: String,
        val type: String,
        val path: String,
        val tracks: List<Track>
    )

    private const val SEARCH_QUERY = """
    query get_q27(${'$'}select: SearchAnime_Select) {
      get_q27(select: ${'$'}select) {
        items {
          id
          data {
            ani_id
            info_title
            urlCover600
            info_meta_type
            info_meta_year
            info_sou_types
          }
        }
      }
    }
    """

    private const val DETAIL_QUERY = """
    query Get_animesNode(${'$'}getAnimesNodeId: String!) {
      get_q02(id: ${'$'}getAnimesNodeId) {
        id
        data {
          info_title
          urlCover600
          urlCoverOri
          info_meta_year
          info_meta_type
          info_meta_genre
          info_meta_scores
          info_meta_status
          info_filmdesc
          info_sou_types
        }
      }
    }
    """

    private const val EPISODES_QUERY = """
    query get_q01(${'$'}select: AnimesEpisodesList_Select) {
      get_q01(select: ${'$'}select) {
        paging { total pages page init size }
        items {
          id
          data {
            ep_id
            ep_index
            ep_title
            sourcesNode_list { id data { src_type } }
          }
        }
      }
    }
    """

    private const val SOURCES_QUERY = """
    query get_q07(${'$'}select: Episodes_Select) {
      get_q07(select: ${'$'}select) {
        id
        data {
          ep_id
          sourcesNode_list {
            id
            data {
              src_name
              src_type
              souPath
              track { label trackPath }
            }
          }
        }
      }
    }
    """

    private fun JSONArray?.toStringList(): List<String> {
        this ?: return emptyList()
        val out = ArrayList<String>(length())
        for (i in 0 until length()) out.add(optString(i))
        return out
    }

    private fun parseTitles(node: JSONObject?): List<TitleEntry> {
        val out = ArrayList<TitleEntry>()
        node ?: return out
        val items = node.optJSONArray("items") ?: return out
        for (i in 0 until items.length()) {
            val entry = items.optJSONObject(i) ?: continue
            val data = entry.optJSONObject("data") ?: continue
            val aniId = data.optString("ani_id").ifBlank { entry.optString("id") }
            val title = data.optString("info_title")
            if (aniId.isBlank() || title.isBlank()) continue
            out.add(
                TitleEntry(
                    aniId = aniId,
                    title = title,
                    poster = data.optString("urlCover600").takeIf { it.isNotBlank() },
                    year = data.optString("info_meta_year").takeIf { it.isNotBlank() }?.toIntOrNull(),
                    types = data.optJSONArray("info_meta_type").toStringList(),
                    audio = data.optJSONArray("info_sou_types").toStringList()
                        .map { it.lowercase() }.toSet()
                )
            )
        }
        return out
    }

    private fun searchSelect(params: Map<String, Any?>): JSONObject {
        val select = JSONObject()
        select.put("where", "browse")
        select.put("word", params["word"] ?: "")
        select.put("page", (params["page"] as? Int) ?: 1)
        select.put("size", (params["size"] as? Int) ?: PAGE_SIZE)
        select.put("sortby", params["sortby"] ?: "field_update")
        select.put(
            "incGenres",
            JSONArray(params["incGenres"] as? List<*> ?: emptyList<Any>())
        )
        select.put("excGenres", JSONArray())
        params["origStatus"]?.let { select.put("origStatus", it) }
        params["type"]?.let { select.put("type", it) }
        params["sources"]?.let { select.put("sources", it) }
        return select
    }

    // only these sorts actually reorder on the backend, the rest echo the default
    suspend fun browse(
        sortby: String? = null,
        genre: String? = null,
        type: String? = null,
        status: String? = null,
        audio: String? = null,
        page: Int
    ): List<TitleEntry> {
        val variables = JSONObject().put(
            "select",
            searchSelect(
                buildMap {
                    put("page", page)
                    sortby?.let { put("sortby", it) }
                    genre?.let { put("incGenres", listOf(it)) }
                    type?.let { put("type", it) }
                    status?.let { put("origStatus", it) }
                    audio?.let { put("sources", it) }
                }
            )
        )
        return parseTitles(query(SEARCH_QUERY, variables))
    }

    suspend fun search(word: String): List<TitleEntry> {
        val variables = JSONObject().put(
            "select",
            searchSelect(mapOf("word" to word, "sortby" to "field_score"))
        )
        return parseTitles(query(SEARCH_QUERY, variables))
    }

    class Detail(
        val title: String,
        val poster: String?,
        val background: String?,
        val year: Int?,
        val types: List<String>,
        val genres: List<String>,
        val score: Int?,
        val status: String?,
        val audio: Set<String>,
        val description: String?
    )

    suspend fun detail(aniId: String): Detail? {
        val node = query(DETAIL_QUERY, JSONObject().put("getAnimesNodeId", aniId)) ?: return null
        val data = node.optJSONObject("data") ?: return null
        val title = data.optString("info_title")
        if (title.isBlank()) return null
        return Detail(
            title = title,
            poster = data.optString("urlCover600").takeIf { it.isNotBlank() },
            background = data.optString("urlCoverOri").takeIf { it.isNotBlank() },
            year = data.optString("info_meta_year").takeIf { it.isNotBlank() }?.toIntOrNull(),
            types = data.optJSONArray("info_meta_type").toStringList(),
            genres = data.optJSONArray("info_meta_genre").toStringList(),
            score = data.optInt("info_meta_scores").takeIf { it > 0 },
            status = data.optString("info_meta_status").takeIf { it.isNotBlank() },
            audio = data.optJSONArray("info_sou_types").toStringList()
                .map { it.lowercase() }.toSet(),
            description = data.optString("info_filmdesc").takeIf { it.isNotBlank() }
        )
    }

    // the first response reports the total page count so long shows need no page walk
    suspend fun episodes(aniId: String): List<EpisodeEntry> {
        val first = episodePage(aniId, 1) ?: return emptyList()
        if (first.pages <= 1) return first.episodes

        val collected = ArrayList<EpisodeEntry>(first.episodes)
        var page = 2
        while (page <= first.pages) {
            val end = minOf(page + 3, first.pages)
            val batch = coroutineScope {
                (page..end).map { async { episodePage(aniId, it) } }.awaitAll()
            }
            batch.forEach { if (it != null) collected.addAll(it.episodes) }
            page = end + 1
        }

        val seen = HashSet<String>()
        return collected.filter { seen.add(it.epId) }.sortedBy { it.index }
    }

    private class EpisodePage(val pages: Int, val episodes: List<EpisodeEntry>)

    private fun parseEpisodePage(node: JSONObject?): EpisodePage? {
        node ?: return null
        val items = node.optJSONArray("items") ?: return null
        val out = ArrayList<EpisodeEntry>(items.length())
        for (i in 0 until items.length()) {
            val entry = items.optJSONObject(i) ?: continue
            val data = entry.optJSONObject("data") ?: continue
            val epId = data.optString("ep_id")
            if (epId.isBlank()) continue
            val audio = HashSet<String>()
            data.optJSONArray("sourcesNode_list")?.let { arr ->
                for (j in 0 until arr.length()) {
                    arr.optJSONObject(j)?.optJSONObject("data")?.optString("src_type")
                        ?.takeIf { it.isNotBlank() }?.let { audio.add(it.lowercase()) }
                }
            }
            out.add(
                EpisodeEntry(
                    epId = epId,
                    index = data.optInt("ep_index"),
                    title = data.optString("ep_title")
                        .takeIf { it.isNotBlank() } ?: "Episode ${data.optInt("ep_index")}",
                    audio = audio
                )
            )
        }
        val paging = node.optJSONObject("paging")
        val pages = paging?.optInt("pages")?.takeIf { it > 0 } ?: 1
        return EpisodePage(pages, out)
    }

    private suspend fun episodePage(aniId: String, page: Int): EpisodePage? {
        val select = JSONObject()
            .put("ani_id", aniId)
            .put("init", EP_PAGE_SIZE)
            .put("size", EP_PAGE_SIZE)
            .put("page", page)
        return parseEpisodePage(query(EPISODES_QUERY, JSONObject().put("select", select)))
    }

    suspend fun sources(epId: String): List<Source> {
        val node = query(SOURCES_QUERY, JSONObject().put("select", JSONObject().put("id", epId)))
            ?: return emptyList()
        val data = node.optJSONObject("data") ?: return emptyList()
        val list = data.optJSONArray("sourcesNode_list") ?: return emptyList()
        val out = ArrayList<Source>(list.length())
        for (i in 0 until list.length()) {
            val src = list.optJSONObject(i)?.optJSONObject("data") ?: continue
            val path = src.optString("souPath").takeIf { it.startsWith("http") } ?: continue
            val tracks = ArrayList<Track>()
            src.optJSONArray("track")?.let { arr ->
                for (j in 0 until arr.length()) {
                    val t = arr.optJSONObject(j) ?: continue
                    val url = t.optString("trackPath").takeIf { it.startsWith("http") } ?: continue
                    tracks.add(Track(t.optString("label").ifBlank { "Subtitle" }, url))
                }
            }
            out.add(
                Source(
                    name = src.optString("src_name").ifBlank { "Server" },
                    type = src.optString("src_type").lowercase(),
                    path = path,
                    tracks = tracks
                )
            )
        }
        return out
    }

    fun fetchText(url: String): String? {
        return try {
            val req = okhttp3.Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .build()
            app.baseClient.newBuilder()
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .build()
                .newCall(req).execute().use { resp ->
                    if (resp.code !in 200..399) null else resp.body?.string()
                }
        } catch (e: Exception) {
            null
        }
    }

    // dub entries often list subs the cdn never hosted, one probe filters those out
    fun tracksAreReal(source: Source): Boolean {
        val first = source.tracks.firstOrNull() ?: return true
        return fetchText(first.url)?.contains("WEBVTT") == true
    }
}
