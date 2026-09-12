package com.laddu100.raghavanime

import com.lagradost.api.Log
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request

object RaghavSenshiProxy {

    private const val TAG = "Senshi"
    private const val MAX_STREAMS = 12
    private const val HLS_TYPE = "application/vnd.apple.mpegurl"

    private var serverSocket: ServerSocket? = null
    private var serverPort = 0
    @Volatile
    private var serverRunning = false

    private val pool: ExecutorService = Executors.newCachedThreadPool()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .dispatcher(Dispatcher().apply {
            maxRequests = 32
            maxRequestsPerHost = 8
        })
        .build()

    private val streams = ConcurrentHashMap<String, StreamEntry>()
    private val order = ArrayDeque<String>()
    private val orderLock = Any()

    class StreamEntry(
        val id: String,
        val masterUrl: String,
        val masterContent: String,
        val headers: Map<String, String>
    ) {
        val servedPlaylists = ConcurrentHashMap<String, String>()
    }

    @Synchronized
    private fun ensureServerRunning(): Int {
        if (serverRunning && serverPort > 0) return serverPort
        try {
            val socket = ServerSocket(0)
            serverSocket = socket
            serverPort = socket.localPort
            serverRunning = true
            Log.d(TAG, "proxy listening on 127.0.0.1:$serverPort")
            Thread {
                while (serverRunning) {
                    try {
                        val conn = socket.accept()
                        pool.execute { handleRequest(conn) }
                    } catch (e: Exception) {
                        if (serverRunning) Log.e(TAG, "proxy accept failed: ${e.message}")
                    }
                }
            }.start()
        } catch (e: Exception) {
            Log.e(TAG, "proxy start failed: ${e.message}")
        }
        return serverPort
    }

    @Synchronized
    fun register(masterUrl: String, masterContent: String, headers: Map<String, String>): String? {
        val port = ensureServerRunning()
        if (port == 0) {
            Log.e(TAG, "proxy unavailable, cannot register stream")
            return null
        }
        val id = MessageDigest.getInstance("MD5")
            .digest(masterUrl.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(10)
        synchronized(orderLock) {
            if (!streams.containsKey(id)) {
                order.addLast(id)
            }
            while (order.size > MAX_STREAMS) {
                order.removeFirstOrNull()?.let { streams.remove(it) }
            }
            streams[id] = StreamEntry(id, masterUrl, masterContent, headers)
        }
        return "http://127.0.0.1:$port/$id"
    }

    private fun shortUrl(url: String): String {
        return try {
            val uri = URI(url)
            "${uri.host}${uri.path}"
        } catch (e: Exception) {
            url.take(80)
        }
    }

    private fun handleRequest(conn: Socket) {
        try {
            conn.soTimeout = 20000
            val reader = BufferedReader(InputStreamReader(conn.getInputStream()))
            val requestLine = reader.readLine() ?: return
            val rawPath = requestLine.split(" ").getOrNull(1) ?: return

            val requestHeaders = mutableMapOf<String, String>()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx > 0) {
                    requestHeaders[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
                }
            }

            val pathOnly = rawPath.substringBefore("?")
            val segments = pathOnly.split("/").filter { it.isNotEmpty() }
            if (segments.size < 3) {
                send404(conn)
                return
            }
            val entry = streams[segments[0]]
            if (entry == null) {
                Log.w(TAG, "proxy request for unknown stream ${segments[0]}")
                send404(conn)
                return
            }

            when (segments[1]) {
                "m" -> {
                    val mode = segments.getOrNull(2) ?: "sub"
                    val variant = segments.getOrNull(3)?.toIntOrNull() ?: 0
                    val rewritten = rewriteMaster(entry, mode, variant)
                    if (rewritten == null) {
                        Log.e(TAG, "proxy master rewrite failed mode=$mode variant=$variant")
                        send404(conn)
                    } else {
                        sendBytes(conn, rewritten.toByteArray(Charsets.UTF_8), HLS_TYPE)
                    }
                }
                "p" -> {
                    val target = decodeUrl(segments.drop(2).joinToString("/"))
                    if (target == null) send404(conn) else servePlaylist(conn, entry, target)
                }
                "s" -> {
                    val target = decodeUrl(segments.drop(2).joinToString("/"))
                    if (target == null) send404(conn) else serveSegment(conn, entry, target, requestHeaders["range"])
                }
                "k" -> {
                    val target = decodeUrl(segments.drop(2).joinToString("/"))
                    if (target == null) send404(conn) else serveKey(conn, entry, target)
                }
                else -> send404(conn)
            }
        } catch (e: Exception) {
            Log.e(TAG, "proxy request failed: ${e.message}")
        } finally {
            try { conn.close() } catch (_: Exception) {}
        }
    }

    private val uriAttr = Regex("""URI="([^"]+)"""")
    private val languageAttr = Regex("""LANGUAGE="([^"]*)"""")
    private val nameAttr = Regex("""NAME="([^"]*)"""")

    private fun isEnglishAudio(line: String, uri: String): Boolean {
        val lang = languageAttr.find(line)?.groupValues?.get(1)?.lowercase() ?: ""
        val name = nameAttr.find(line)?.groupValues?.get(1)?.lowercase() ?: ""
        if (lang.startsWith("en")) return true
        if (name.contains("english")) return true
        val u = uri.lowercase()
        return u.contains("1_en") || u.contains("_en") || u.contains("/en/") || u.contains("-en")
    }

    private fun markDefault(line: String, yes: Boolean): String {
        val value = if (yes) "YES" else "NO"
        return if (line.contains("DEFAULT=")) {
            line.replace(Regex("""DEFAULT=[^,]*"""), "DEFAULT=$value")
        } else {
            "$line,DEFAULT=$value"
        }
    }

    private fun rewriteMaster(entry: StreamEntry, mode: String, variantIdx: Int): String? {
        try {
            val lines = entry.masterContent.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
            val base = URI(entry.masterUrl.substringBefore("?"))

            val audioIdx = mutableListOf<Int>()
            for (i in lines.indices) {
                val l = lines[i]
                if (l.startsWith("#EXT-X-MEDIA") && l.contains("TYPE=AUDIO")) {
                    audioIdx.add(i)
                }
            }

            val keepAudio = mutableSetOf<Int>()
            if (audioIdx.isNotEmpty()) {
                val uriOf = { i: Int ->
                    uriAttr.find(lines[i])?.groupValues?.get(1)?.let { resolveUri(it, base) } ?: ""
                }
                val english = audioIdx.filter { isEnglishAudio(lines[it], uriOf(it)) }
                when {
                    mode == "dub" && english.isNotEmpty() -> keepAudio.addAll(english)
                    mode == "dub" -> keepAudio.addAll(audioIdx)
                    else -> {
                        val nonEnglish = audioIdx.filter { it !in english }
                        if (nonEnglish.isNotEmpty()) keepAudio.addAll(nonEnglish)
                        else keepAudio.addAll(audioIdx)
                    }
                }
            }

            val variantBlocks = mutableListOf<Pair<Int, Int>>()
            var pending = -1
            for (i in lines.indices) {
                val l = lines[i]
                if (l.startsWith("#EXT-X-STREAM-INF")) {
                    pending = i
                } else if (pending >= 0 && !l.startsWith("#")) {
                    variantBlocks.add(pending to i)
                    pending = -1
                }
            }

            if (variantBlocks.isEmpty()) {
                return entry.masterContent
            }

            val chosen = variantBlocks.getOrNull(variantIdx) ?: variantBlocks.first()
            val skipLines = mutableSetOf<Int>()
            variantBlocks.forEach { (si, ui) ->
                if (si != chosen.first) {
                    skipLines.add(si)
                    skipLines.add(ui)
                }
            }

            val out = StringBuilder()
            out.append("#EXTM3U\n")
            var firstAudioKept = true
            for (i in lines.indices) {
                if (i in skipLines) continue
                val line = lines[i]
                when {
                    line == "#EXTM3U" -> {}

                    line.startsWith("#EXT-X-MEDIA") -> {
                        if (line.contains("TYPE=AUDIO")) {
                            if (i !in keepAudio) continue
                            val rewritten = rewriteRenditionLine(line, base, entry)
                            out.append(markDefault(rewritten, firstAudioKept)).append('\n')
                            firstAudioKept = false
                        } else {
                            out.append(rewriteRenditionLine(line, base, entry)).append('\n')
                        }
                    }

                    line.startsWith("#EXT-X-SESSION-KEY") -> {
                        out.append(rewriteAttr(line, base, entry, "k")).append('\n')
                    }

                    line.startsWith("#EXT-X-STREAM-INF") -> {
                        out.append(line).append('\n')
                    }

                    !line.startsWith("#") && i == chosen.second -> {
                        val abs = resolveUri(line, base) ?: return null
                        out.append(proxyPath(entry, abs, "p")).append('\n')
                    }

                    else -> out.append(line).append('\n')
                }
            }
            return out.toString()
        } catch (e: Exception) {
            Log.e(TAG, "master rewrite failed: ${e.message}")
            return null
        }
    }

    private fun rewriteRenditionLine(line: String, base: URI, entry: StreamEntry): String {
        val m = uriAttr.find(line) ?: return line
        val abs = resolveUri(m.groupValues[1], base) ?: return line
        return line.replaceRange(m.range, """URI="${proxyPath(entry, abs, "p")}"""")
    }

    private fun rewriteAttr(line: String, base: URI, entry: StreamEntry, route: String): String {
        val m = uriAttr.find(line) ?: return line
        val abs = resolveUri(m.groupValues[1], base) ?: return line
        return line.replaceRange(m.range, """URI="${proxyPath(entry, abs, route)}"""")
    }

    private fun servePlaylist(conn: Socket, entry: StreamEntry, target: String) {
        try {
            entry.servedPlaylists[target]?.let {
                sendBytes(conn, it.toByteArray(Charsets.UTF_8), HLS_TYPE)
                return
            }
            val body = fetchText(target, entry)
            if (body == null) {
                Log.e(TAG, "playlist fetch failed: ${shortUrl(target)}")
                send404(conn)
                return
            }

            val playlist: String = if (RaghavSenshiCrypt.isEncrypted(body)) {
                val plain = RaghavSenshiCrypt.decrypt(body)
                if (plain == null) {
                    Log.e(TAG, "playlist decrypt failed: ${shortUrl(target)}")
                    send404(conn)
                    return
                }
                plain
            } else {
                body
            }

            if (!playlist.startsWith("#EXTM3U")) {
                Log.w(TAG, "playlist not m3u8: ${shortUrl(target)}")
                sendBytes(conn, playlist.toByteArray(Charsets.UTF_8), "application/octet-stream")
                return
            }

            val base = URI(target.substringBefore("?"))
            val out = StringBuilder()
            for (raw in playlist.split("\n")) {
                val line = raw.trim()
                if (line.isEmpty()) continue
                when {
                    line.startsWith("#EXT-X-KEY") -> {
                        out.append(rewriteAttr(line, base, entry, "k")).append('\n')
                    }
                    line.startsWith("#EXT-X-MAP") -> {
                        out.append(rewriteAttr(line, base, entry, "s")).append('\n')
                    }
                    line.startsWith("#EXT-X-SESSION-KEY") -> {
                        out.append(rewriteAttr(line, base, entry, "k")).append('\n')
                    }
                    !line.startsWith("#") -> {
                        val abs = resolveUri(line, base) ?: line
                        out.append(proxyPath(entry, abs, "s")).append('\n')
                    }
                    else -> out.append(line).append('\n')
                }
            }
            val rewritten = out.toString()
            entry.servedPlaylists[target] = rewritten
            sendBytes(conn, rewritten.toByteArray(Charsets.UTF_8), HLS_TYPE)
        } catch (e: Exception) {
            Log.e(TAG, "playlist serve failed: ${e.message}")
            send404(conn)
        }
    }

    private fun serveSegment(conn: Socket, entry: StreamEntry, target: String, range: String?) {
        try {
            val builder = Request.Builder().url(target).get()
            entry.headers.forEach { (k, v) -> builder.addHeader(k, v) }
            if (!range.isNullOrBlank()) {
                builder.addHeader("Range", range)
            }
            client.newCall(builder.build()).execute().use { resp ->
                if (!resp.isSuccessful && resp.code != 206) {
                    Log.e(TAG, "segment ${resp.code}: ${shortUrl(target)}")
                    sendEmpty(conn, resp.code)
                    return
                }
                val body = resp.body ?: run { send404(conn); return }
                val out: OutputStream = conn.getOutputStream()
                val sb = StringBuilder()
                sb.append("HTTP/1.1 ").append(resp.code)
                    .append(if (resp.code == 206) " Partial Content" else " OK").append("\r\n")
                val ct = body.contentType()?.toString() ?: "video/mp2t"
                sb.append("Content-Type: ").append(ct).append("\r\n")
                val len = body.contentLength()
                if (len >= 0) {
                    sb.append("Content-Length: ").append(len).append("\r\n")
                }
                resp.header("Content-Range")?.let {
                    sb.append("Content-Range: ").append(it).append("\r\n")
                }
                sb.append("Access-Control-Allow-Origin: *\r\n")
                sb.append("Connection: close\r\n\r\n")
                out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
                out.flush()

                val input: InputStream = body.byteStream()
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    out.flush()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "segment serve failed: ${e.message}")
        }
    }

    private fun serveKey(conn: Socket, entry: StreamEntry, target: String) {
        val bytes = fetchBytes(target, entry)
        if (bytes == null) {
            Log.e(TAG, "key fetch failed: ${shortUrl(target)}")
            send404(conn)
            return
        }
        sendBytes(conn, bytes, "application/octet-stream")
    }

    private fun buildUpstream(url: String, entry: StreamEntry): Request.Builder {
        val builder = Request.Builder().url(url).get()
        entry.headers.forEach { (k, v) -> builder.addHeader(k, v) }
        return builder
    }

    private fun fetchText(url: String, entry: StreamEntry): String? {
        return try {
            client.newCall(buildUpstream(url, entry).build()).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string() else null
            }
        } catch (e: Exception) {
            Log.d(TAG, "upstream fetch failed: ${e.message}")
            null
        }
    }

    private fun fetchBytes(url: String, entry: StreamEntry): ByteArray? {
        return try {
            client.newCall(buildUpstream(url, entry).build()).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.bytes() else null
            }
        } catch (e: Exception) {
            Log.d(TAG, "upstream bytes failed: ${e.message}")
            null
        }
    }

    private fun proxyPath(entry: StreamEntry, abs: String, route: String): String {
        val enc = URLEncoder.encode(abs, "UTF-8")
        return "/${entry.id}/$route/$enc"
    }

    private fun resolveUri(ref: String, base: URI): String? {
        return try {
            base.resolve(ref).toString()
        } catch (e: Exception) {
            null
        }
    }

    private fun decodeUrl(seg: String): String? {
        return try {
            java.net.URLDecoder.decode(seg, "UTF-8")
        } catch (e: Exception) {
            null
        }
    }

    private fun sendBytes(conn: Socket, bytes: ByteArray, contentType: String) {
        try {
            val out: OutputStream = conn.getOutputStream()
            val head = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: $contentType\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Connection: close\r\n\r\n"
            out.write(head.toByteArray(Charsets.ISO_8859_1))
            out.write(bytes)
            out.flush()
        } catch (_: Exception) {}
    }

    private fun sendEmpty(conn: Socket, code: Int) {
        try {
            val out: OutputStream = conn.getOutputStream()
            val head = "HTTP/1.1 $code Status\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
            out.write(head.toByteArray(Charsets.ISO_8859_1))
            out.flush()
        } catch (_: Exception) {}
    }

    private fun send404(conn: Socket) {
        try {
            val out: OutputStream = conn.getOutputStream()
            val head = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
            out.write(head.toByteArray(Charsets.ISO_8859_1))
            out.flush()
        } catch (_: Exception) {}
    }
}
