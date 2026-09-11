package com.laddu100.raghavanime

import com.lagradost.api.Log
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
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
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request

object FlixProxy {

    private const val FLIX_EMBED_BASE = "https://flixcloud.cc"

    private const val TAG = "RaghavAnime"
    private const val MAX_STREAMS = 20
    private const val PREFETCH_COUNT = 3
    private const val CACHE_LIMIT_BYTES = 48L * 1024 * 1024
    private const val HLS_TYPE = "application/vnd.apple.mpegurl"
    private const val MPEG_TS = "video/mp2t"

    private val XOR_KEY = intArrayOf(157, 42, 241, 71, 179, 142, 92, 112, 166, 25, 228, 59, 216, 98, 15, 197)
        .map { it.toByte() }
        .toByteArray()

    private var serverSocket: ServerSocket? = null
    private var serverPort = 0
    @Volatile
    private var serverRunning = false

    private val pool: ExecutorService = Executors.newCachedThreadPool()

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .dispatcher(Dispatcher().apply {
            maxRequests = 32
            maxRequestsPerHost = 8
        })
        .connectionPool(ConnectionPool(8, 30, TimeUnit.SECONDS))
        .build()

    private val streams = ConcurrentHashMap<String, StreamEntry>()
    private val order = ArrayDeque<String>()

    private val segmentCache = LinkedHashMap<String, ByteArray>(64, 0.75f, true)
    private var cacheBytes = 0L
    private val inFlight: MutableSet<String> = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    class StreamEntry(
        val id: String,
        val masterUrl: String,
        val masterContent: String,
        val pkKey: ByteArray
    ) {
        val playlists = ConcurrentHashMap<String, List<String>>()
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
            Thread {
                while (serverRunning) {
                    try {
                        val conn = socket.accept()
                        pool.execute { handleRequest(conn) }
                    } catch (e: Exception) {
                        if (serverRunning) Log.e(TAG, "accept failed: ${e.message}")
                    }
                }
            }.start()
        } catch (e: Exception) {
            Log.e(TAG, "proxy start failed: ${e.message}")
        }
        return serverPort
    }

    @Synchronized
    fun registerMaster(masterUrl: String, masterContent: String, pkKey: ByteArray): String? {
        val port = ensureServerRunning()
        if (port == 0) return null
        val id = MessageDigest.getInstance("MD5")
            .digest(masterUrl.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(12)
        if (!streams.containsKey(id)) {
            order.addLast(id)
            while (order.size > MAX_STREAMS) {
                order.removeFirstOrNull()?.let { streams.remove(it) }
            }
        }
        streams[id] = StreamEntry(id, masterUrl, masterContent, pkKey)
        return "http://127.0.0.1:$port/$id/master.m3u8"
    }

    private fun handleRequest(conn: Socket) {
        try {
            conn.soTimeout = 15000
            val reader = BufferedReader(InputStreamReader(conn.getInputStream()))
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            val rawPath = parts.getOrNull(1) ?: return
            while (reader.readLine()?.isNotEmpty() == true) {}

            val pathOnly = rawPath.substringBefore("?")
            val query = rawPath.substringAfter("?", "")
            var segments = pathOnly.split("/").filter { it.isNotEmpty() }
            if (segments.size >= 2 && segments[0] == segments[1]) {
                segments = segments.drop(1)
            }
            if (segments.size < 2) {
                send404(conn)
                return
            }
            val id = segments[0]
            val entry = streams[id]
            if (entry == null) {
                send404(conn)
                return
            }

            when (segments[1]) {
                "master.m3u8" -> serveMaster(conn, entry, query)
                "p" -> {
                    val target = decodeUrl(segments.drop(2).joinToString("/"))
                    if (target == null) send404(conn) else servePlaylist(conn, entry, target)
                }
                "s" -> {
                    val target = decodeUrl(segments.drop(2).joinToString("/"))
                    if (target == null) send404(conn) else serveSegment(conn, entry, target)
                }
                "k" -> {
                    val target = decodeUrl(segments.drop(2).joinToString("/"))
                    if (target == null) send404(conn) else serveKey(conn, target)
                }
                else -> send404(conn)
            }
        } catch (e: Exception) {
            Log.e(TAG, "proxy request failed: ${e.message}")
        } finally {
            try { conn.close() } catch (_: Exception) {}
        }
    }

    private fun serveMaster(conn: Socket, entry: StreamEntry, query: String) {
        val lang = Regex("""(?:^|&)lang=([^&]*)""").find(query)?.groupValues?.get(1) ?: "sub"
        val rewritten = rewriteMaster(entry, lang)
        if (rewritten == null) {
            send404(conn)
            return
        }
        sendBytes(conn, rewritten.toByteArray(Charsets.UTF_8), HLS_TYPE)
    }

    private fun rewriteMaster(entry: StreamEntry, lang: String): String? {
        try {
            val lines = entry.masterContent.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
            val audioIdx = mutableListOf<Int>()
            for (i in lines.indices) {
                if (lines[i].startsWith("#EXT-X-MEDIA") && lines[i].contains("TYPE=AUDIO")) {
                    audioIdx.add(i)
                }
            }
            val keep = mutableSetOf<Int>()
            if (audioIdx.isNotEmpty()) {
                val english = audioIdx.filter { idx ->
                    val l = Regex("""LANGUAGE="([^"]*)"""").find(lines[idx])?.groupValues?.get(1) ?: ""
                    val n = Regex("""NAME="([^"]*)"""").find(lines[idx])?.groupValues?.get(1) ?: ""
                    l.startsWith("en") || n.contains("English", ignoreCase = true)
                }
                val wanted = if (lang == "dub") english else audioIdx.filter { it !in english }
                keep.addAll(if (wanted.isEmpty()) audioIdx else wanted)
            }
            val out = StringBuilder()
            val masterUri = URI(entry.masterUrl.substringBefore("?"))
            var pendingVariant = false
            for (i in lines.indices) {
                val line = lines[i]
                if (line.startsWith("#EXT-X-MEDIA")) {
                    if (line.contains("TYPE=AUDIO")) {
                        if (i in keep) {
                            out.append(rewriteRoute(line, masterUri, entry, "p")).append('\n')
                        }
                        continue
                    }
                    out.append(rewriteAbsolute(line, masterUri)).append('\n')
                    continue
                }
                if (line.startsWith("#EXT-X-SESSION-KEY")) {
                    out.append(rewriteRoute(line, masterUri, entry, "k")).append('\n')
                    continue
                }
                if (line.startsWith("#EXT-X-STREAM-INF")) {
                    pendingVariant = true
                    out.append(line).append('\n')
                    continue
                }
                if (pendingVariant && !line.startsWith("#")) {
                    val abs = resolveUri(line, masterUri) ?: return null
                    out.append(proxyPath(entry, abs, "p")).append('\n')
                    pendingVariant = false
                    continue
                }
                out.append(line).append('\n')
            }
            return out.toString()
        } catch (e: Exception) {
            Log.e(TAG, "master rewrite failed: ${e.message}")
            return null
        }
    }

    private fun servePlaylist(conn: Socket, entry: StreamEntry, target: String) {
        try {
            entry.servedPlaylists[target]?.let {
                sendBytes(conn, it.toByteArray(Charsets.UTF_8), HLS_TYPE)
                triggerFirstPrefetch(entry, target)
                return
            }
            val body = fetch(target) ?: run { send404(conn); return }
            val plain = FlixResolver.decryptPlaylist(body, entry.pkKey) ?: run { send404(conn); return }
            val base = URI(target.substringBefore("?"))
            val out = StringBuilder()
            val segments = mutableListOf<String>()
            for (raw in plain.split("\n")) {
                val line = raw.trim()
                if (line.isEmpty()) continue
                when {
                    line.startsWith("#EXT-X-KEY") -> {
                        out.append(rewriteRoute(line, base, entry, "k")).append('\n')
                    }
                    line.startsWith("#EXT-X-MAP") -> {
                        out.append(rewriteRoute(line, base, entry, "s")).append('\n')
                    }
                    !line.startsWith("#") -> {
                        val abs = resolveUri(line, base) ?: line
                        segments.add(abs)
                        out.append(proxyPath(entry, abs, "s")).append('\n')
                    }
                    else -> out.append(line).append('\n')
                }
            }
            val rewritten = out.toString()
            entry.playlists[target] = segments
            entry.servedPlaylists[target] = rewritten
            sendBytes(conn, rewritten.toByteArray(Charsets.UTF_8), HLS_TYPE)
            triggerFirstPrefetch(entry, target)
        } catch (e: Exception) {
            Log.e(TAG, "playlist fetch failed: ${e.message}")
            send404(conn)
        }
    }

    private fun serveSegment(conn: Socket, entry: StreamEntry, target: String) {
        triggerPrefetch(entry, target)
        val cached = cacheGet(target)
        if (cached != null) {
            sendBytes(conn, cached, MPEG_TS)
            return
        }
        try {
            client.newCall(buildRequest(target)).execute().use { resp ->
                if (!resp.isSuccessful) {
                    sendEmpty(conn, resp.code)
                    return
                }
                val body = resp.body ?: run { send404(conn); return }
                streamSegment(conn, target, body.byteStream(), body.contentLength())
            }
        } catch (e: Exception) {
            Log.e(TAG, "segment fetch failed: ${e.message}")
        }
    }

    private fun serveKey(conn: Socket, target: String) {
        val cached = cacheGet(target)
        if (cached != null) {
            sendBytes(conn, cached, "application/octet-stream")
            return
        }
        val bytes = fetchBytes(target)
        if (bytes == null) {
            send404(conn)
            return
        }
        cachePut(target, bytes)
        sendBytes(conn, bytes, "application/octet-stream")
    }

    private fun streamSegment(conn: Socket, target: String, src: InputStream, total: Long) {
        try {
            val out = conn.getOutputStream()
            val head = ByteArray(16)
            var headLen = 0
            while (headLen < 16) {
                val n = src.read(head, headLen, 16 - headLen)
                if (n < 0) break
                headLen += n
            }
            val hdr = imageHeaderLen(head)
            val xor = hdr in 1 until headLen && head[hdr] != 0x47.toByte()
            val outLen = if (total >= 0) total - hdr else -1L
            writeResponseHead(out, outLen, MPEG_TS)
            val capture = if (outLen in 0..CACHE_LIMIT_BYTES) ByteArrayOutputStream(maxOf(64, outLen.toInt())) else null
            writeChunk(out, capture, head, 0, headLen, hdr, xor, 0)
            val buf = ByteArray(64 * 1024)
            var rawPos = headLen
            while (true) {
                val n = src.read(buf)
                if (n < 0) break
                writeChunk(out, capture, buf, 0, n, hdr, xor, rawPos)
                rawPos += n
            }
            out.flush()
            val captured = capture?.toByteArray()
            if (captured != null && outLen >= 0 && captured.size.toLong() == outLen) {
                cachePut(target, captured)
            }
        } catch (e: Exception) {
            Log.e(TAG, "segment stream failed: ${e.message}")
        }
    }

    private fun writeChunk(
        out: OutputStream,
        capture: ByteArrayOutputStream?,
        buf: ByteArray,
        off: Int,
        len: Int,
        hdr: Int,
        xor: Boolean,
        rawStart: Int
    ) {
        var i = off
        var rawPos = rawStart
        val end = off + len
        while (i < end) {
            if (rawPos < hdr) {
                val skip = minOf(hdr - rawPos, end - i)
                i += skip
                rawPos += skip
                continue
            }
            if (xor) {
                for (j in i until end) {
                    buf[j] = (buf[j].toInt() xor XOR_KEY[(rawPos - hdr) and 15].toInt()).toByte()
                    rawPos++
                }
                out.write(buf, i, end - i)
                capture?.write(buf, i, end - i)
                i = end
            } else {
                out.write(buf, i, end - i)
                capture?.write(buf, i, end - i)
                rawPos += end - i
                i = end
            }
        }
    }

    private fun imageHeaderLen(data: ByteArray): Int {
        if (data.size >= 12 &&
            data[0] == 0x52.toByte() && data[1] == 0x49.toByte() && data[2] == 0x46.toByte() && data[3] == 0x46.toByte() &&
            data[8] == 0x57.toByte() && data[9] == 0x45.toByte() && data[10] == 0x42.toByte() && data[11] == 0x50.toByte()
        ) return 12
        if (data.size >= 8 &&
            data[0] == 0x89.toByte() && data[1] == 0x50.toByte() && data[2] == 0x4e.toByte() && data[3] == 0x47.toByte() &&
            data[4] == 0x0d.toByte() && data[5] == 0x0a.toByte() && data[6] == 0x1a.toByte() && data[7] == 0x0a.toByte()
        ) return 8
        return 0
    }

    private fun unwrapBytes(data: ByteArray): ByteArray {
        val hdr = imageHeaderLen(data)
        if (hdr == 0) return data
        val payload = data.copyOfRange(hdr, data.size)
        if (payload.isEmpty() || payload[0] == 0x47.toByte()) return payload
        val out = payload.copyOf()
        for (i in out.indices) {
            out[i] = (out[i].toInt() xor XOR_KEY[i and 15].toInt()).toByte()
        }
        return out
    }

    private fun triggerFirstPrefetch(entry: StreamEntry, playlistUrl: String) {
        val segments = entry.playlists[playlistUrl] ?: return
        for (i in 1..minOf(PREFETCH_COUNT, segments.size - 1)) {
            enqueuePrefetch(segments[i])
        }
    }

    private fun triggerPrefetch(entry: StreamEntry, url: String) {
        for (segments in entry.playlists.values) {
            val idx = segments.indexOf(url)
            if (idx < 0) continue
            val end = minOf(idx + PREFETCH_COUNT, segments.size - 1)
            for (i in idx + 1..end) {
                enqueuePrefetch(segments[i])
            }
            return
        }
    }

    private fun enqueuePrefetch(url: String) {
        if (cacheHas(url)) return
        if (!inFlight.add(url)) return
        pool.execute {
            try {
                val raw = fetchBytes(url) ?: return@execute
                cachePut(url, unwrapBytes(raw))
            } catch (e: Exception) {
                Log.e(TAG, "prefetch failed: ${e.message}")
            } finally {
                inFlight.remove(url)
            }
        }
    }

    private fun cacheGet(url: String): ByteArray? = synchronized(segmentCache) {
        segmentCache[url]
    }

    private fun cacheHas(url: String): Boolean = synchronized(segmentCache) {
        segmentCache.containsKey(url)
    }

    private fun cachePut(url: String, bytes: ByteArray) {
        synchronized(segmentCache) {
            if (segmentCache.containsKey(url)) return
            segmentCache[url] = bytes
            cacheBytes += bytes.size
            while (cacheBytes > CACHE_LIMIT_BYTES && segmentCache.isNotEmpty()) {
                val it = segmentCache.entries.iterator()
                if (!it.hasNext()) break
                val eldest = it.next()
                cacheBytes -= eldest.value.size
                it.remove()
            }
        }
    }

    private fun rewriteAbsolute(line: String, base: URI): String {
        val m = Regex("""URI="([^"]+)"""").find(line) ?: return line
        val abs = resolveUri(m.groupValues[1], base) ?: return line
        return line.replaceRange(m.range, """URI="$abs"""")
    }

    private fun rewriteRoute(line: String, base: URI, entry: StreamEntry, route: String): String {
        val m = Regex("""URI="([^"]+)"""").find(line) ?: return line
        val abs = resolveUri(m.groupValues[1], base) ?: return line
        return line.replaceRange(m.range, """URI="${proxyPath(entry, abs, route)}"""")
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

    private fun buildRequest(url: String): Request = Request.Builder()
        .url(url)
        .addHeader("User-Agent", FlixResolver.USER_AGENT)
        .addHeader("Referer", "$FLIX_EMBED_BASE/")
        .get()
        .build()

    private fun fetch(url: String): String? {
        return try {
            client.newCall(buildRequest(url)).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string() else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun fetchBytes(url: String): ByteArray? {
        return try {
            client.newCall(buildRequest(url)).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.bytes() else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun writeResponseHead(out: OutputStream, contentLength: Long, contentType: String) {
        val head = if (contentLength >= 0) {
            "HTTP/1.1 200 OK\r\n" +
                "Content-Type: $contentType\r\n" +
                "Content-Length: $contentLength\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Connection: close\r\n\r\n"
        } else {
            "HTTP/1.1 200 OK\r\n" +
                "Content-Type: $contentType\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Connection: close\r\n\r\n"
        }
        out.write(head.toByteArray(Charsets.ISO_8859_1))
    }

    private fun sendBytes(conn: Socket, bytes: ByteArray, contentType: String) {
        try {
            val out: OutputStream = conn.getOutputStream()
            writeResponseHead(out, bytes.size.toLong(), contentType)
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
