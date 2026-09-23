package com.gotaku

import com.lagradost.api.Log
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.runBlocking
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

object GoTakuProxy {

    private const val TAG = "GoTaku"
    private const val MAX_STREAMS = 10
    private const val HLS_TYPE = "application/vnd.apple.mpegurl"

    private var serverSocket: ServerSocket? = null
    private var serverPort = 0
    @Volatile
    private var serverRunning = false

    private val pool: ExecutorService = Executors.newCachedThreadPool()

    class StreamSession(
        val id: String,
        val manifestBase: String,
        val stamp: String,
        @Volatile var masterUrl: String,
        val keySeed: ByteArray,
        val segmentBytes: Int
    ) {
        @Volatile
        var token: String = ""

        @Volatile
        var expiresAt: Long = 0

        @Volatile
        var refreshPending: Boolean = false

        val playlistCache = ConcurrentHashMap<String, String>()
    }

    private val sessions = ConcurrentHashMap<String, StreamSession>()
    private val order = ArrayDeque<String>()
    private val orderLock = Any()

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
    fun register(
        manifestBase: String,
        stamp: String,
        token: String,
        expiresAt: Long,
        keySeed: ByteArray,
        segmentBytes: Int,
        masterUrl: String
    ): String? {
        val port = ensureServerRunning()
        if (port == 0) return null
        val id = MessageDigest.getInstance("MD5")
            .digest((manifestBase + stamp).toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(10)
        synchronized(orderLock) {
            if (!sessions.containsKey(id)) {
                order.addLast(id)
            }
            while (order.size > MAX_STREAMS) {
                order.removeFirstOrNull()?.let { sessions.remove(it) }
            }
            val session = StreamSession(id, manifestBase, stamp, masterUrl, keySeed, segmentBytes)
            session.token = token
            session.expiresAt = expiresAt
            sessions[id] = session
        }
        return "http://127.0.0.1:$port/$id"
    }

    private fun handleRequest(conn: Socket) {
        try {
            conn.soTimeout = 30000
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
            val session = sessions[segments[0]]
            if (session == null) {
                send404(conn)
                return
            }

            when {
                segments[1] == "m" && segments.size >= 3 -> serveMaster(conn, session, segments[2].toIntOrNull() ?: -1)
                segments[1] == "p" -> {
                    val target = decodeUrl(segments.drop(2).joinToString("/"))
                    if (target == null) send404(conn) else servePlaylist(conn, session, target)
                }
                segments[1] == "s" -> {
                    val target = decodeUrl(segments.drop(2).joinToString("/"))
                    if (target == null) send404(conn) else serveSegment(conn, session, target, requestHeaders["range"])
                }
                else -> send404(conn)
            }
        } catch (e: Exception) {
            Log.e(TAG, "proxy request failed: ${e.message}")
        } finally {
            try { conn.close() } catch (e: Exception) {
                Log.d(TAG, "socket close failed: ${e.message}")
            }
        }
    }

    // keep only the chosen variant so the player cannot switch levels
    private fun serveMaster(conn: Socket, session: StreamSession, variantIndex: Int) {
        val body = fetchPlaylist(session, session.masterUrl)
        if (body == null) {
            send404(conn)
            return
        }
        val lines = body.split("\n").map { it.trim() }.filter { it.isNotEmpty() }

        val variantBlocks = mutableListOf<Pair<Int, Int>>()
        var pending = -1
        for (i in lines.indices) {
            if (lines[i].startsWith("#EXT-X-STREAM-INF")) {
                pending = i
            } else if (pending >= 0 && !lines[i].startsWith("#")) {
                variantBlocks.add(pending to i)
                pending = -1
            }
        }

        val base = URI(session.masterUrl.substringBefore("?"))
        val out = StringBuilder("#EXTM3U\n")
        if (variantBlocks.isEmpty()) {
            for (line in lines) {
                if (line == "#EXTM3U") continue
                if (!line.startsWith("#")) {
                    val abs = resolveUri(line, base) ?: continue
                    out.append(proxyPath(session, abs, "p")).append('\n')
                } else {
                    out.append(line).append('\n')
                }
            }
        } else {
            val chosen = variantBlocks.getOrNull(variantIndex) ?: variantBlocks.last()
            for (i in lines.indices) {
                val line = lines[i]
                when {
                    i == chosen.first -> out.append(line).append('\n')
                    i == chosen.second -> {
                        val abs = resolveUri(line, base) ?: continue
                        out.append(proxyPath(session, abs, "p")).append('\n')
                    }
                    line.startsWith("#EXT-X-MEDIA") -> {
                        out.append(rewriteAttrUri(line, base, session, "p")).append('\n')
                    }
                    line.startsWith("#EXT-X-SESSION-KEY") -> {
                        out.append(rewriteAttrUri(line, base, session, "s")).append('\n')
                    }
                }
            }
        }
        sendBytes(conn, out.toString().toByteArray(Charsets.UTF_8), HLS_TYPE)
    }

    private fun servePlaylist(conn: Socket, session: StreamSession, target: String) {
        session.playlistCache[target]?.let {
            sendBytes(conn, it.toByteArray(Charsets.UTF_8), HLS_TYPE)
            return
        }
        val body = fetchPlaylist(session, target) ?: run { send404(conn); return }

        val out = StringBuilder()
        val base = URI(target.substringBefore("?"))
        for (raw in body.split("\n")) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            when {
                line.startsWith("#EXT-X-KEY") || line.startsWith("#EXT-X-MAP") || line.startsWith("#EXT-X-SESSION-KEY") -> {
                    out.append(rewriteAttrUri(line, base, session, "s")).append('\n')
                }
                line.startsWith("#EXT-X-MEDIA") -> {
                    out.append(rewriteAttrUri(line, base, session, "p")).append('\n')
                }
                !line.startsWith("#") -> {
                    val abs = resolveUri(line, base) ?: line
                    out.append(proxyPath(session, abs, "s")).append('\n')
                }
                else -> out.append(line).append('\n')
            }
        }
        val rewritten = out.toString()
        session.playlistCache[target] = rewritten
        sendBytes(conn, rewritten.toByteArray(Charsets.UTF_8), HLS_TYPE)
    }

    private val uriAttr = Regex("""URI="([^"]+)"""")

    private fun rewriteAttrUri(line: String, base: URI, session: StreamSession, route: String): String {
        val m = uriAttr.find(line) ?: return line
        val abs = resolveUri(m.groupValues[1], base) ?: return line
        return line.replaceRange(m.range, """URI="${proxyPath(session, abs, route)}"""")
    }

    private fun serveSegment(conn: Socket, session: StreamSession, target: String, range: String?) {
        try {
            val response = runBlocking { app.get(target, headers = cdnHeaders(target)) }
            if (!response.isSuccessful) {
                Log.e(TAG, "segment ${response.code}: ${shortUrl(target)}")
                send404(conn)
                return
            }
            val bytes = response.body.bytes()
            val path = URI(target).path
            val plain = GoTakuCrypto.decryptSegment(bytes, path, session.keySeed, session.segmentBytes)
            serveByteRange(conn, plain, range)
        } catch (e: Exception) {
            Log.e(TAG, "segment fetch failed: ${shortUrl(target)}: ${e.message}")
            send404(conn)
        }
    }

    private fun serveByteRange(conn: Socket, bytes: ByteArray, range: String?) {
        val start: Int
        val endInclusive: Int
        if (range != null) {
            val m = Regex("""bytes=(\d+)-(\d*)""").find(range)
            if (m != null) {
                start = m.groupValues[1].toInt()
                endInclusive = if (m.groupValues[2].isEmpty()) bytes.size - 1 else minOf(m.groupValues[2].toInt(), bytes.size - 1)
            } else {
                start = 0
                endInclusive = bytes.size - 1
            }
        } else {
            start = 0
            endInclusive = bytes.size - 1
        }
        if (start > endInclusive || start >= bytes.size) {
            sendStatus(conn, 416)
            return
        }
        val slice = bytes.copyOfRange(start, endInclusive + 1)
        val out: OutputStream = conn.getOutputStream()
        val head = "HTTP/1.1 ${if (range != null) "206 Partial Content" else "200 OK"}\r\n" +
            "Content-Type: video/mp2t\r\n" +
            "Content-Length: ${slice.size}\r\n" +
            "Content-Range: bytes $start-$endInclusive/${bytes.size}\r\n" +
            "Accept-Ranges: bytes\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "Connection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        out.write(slice)
        out.flush()
    }

    // the cdn token sits in the path and expires, refresh fetches a new manifest
    private fun rewriteWithToken(url: String, token: String, expiresAt: Long): String {
        return try {
            val uri = URI(url)
            val parts = uri.path.split("/").filter { it.isNotEmpty() }.toMutableList()
            if (parts.size > 3 && parts[0] == "p") {
                parts[2] = expiresAt.toString()
                parts[3] = token
            }
            val port = if (uri.port > 0) ":${uri.port}" else ""
            "https://${uri.host}$port/${parts.joinToString("/")}"
        } catch (e: Exception) {
            url
        }
    }

    private fun refreshSession(session: StreamSession) {
        if (session.refreshPending) return
        synchronized(session) {
            if (session.refreshPending) return
            session.refreshPending = true
            try {
                val fresh = runBlocking { GoTakuApi.fetchManifest(session.manifestBase, session.stamp) }
                if (fresh != null) {
                    session.token = fresh.token
                    session.expiresAt = fresh.expiresAt
                    session.playlistCache.clear()
                    Log.d(TAG, "token refreshed for ${session.id}")
                }
            } finally {
                session.refreshPending = false
            }
        }
    }

    private fun fetchPlaylist(session: StreamSession, target: String): String? {
        var url = rewriteWithToken(target, session.token, session.expiresAt)
        for (attempt in 0 until 3) {
            try {
                val response = runBlocking { app.get(url, headers = cdnHeaders(url)) }
                if (response.isSuccessful) {
                    val bytes = response.body.bytes()
                    val key = GoTakuCrypto.playlistKey(session.keySeed, tokenFromUrl(url, session))
                    val plain = GoTakuCrypto.decryptPlaylist(key, bytes)
                    if (plain != null && plain.startsWith("#EXTM3U")) {
                        return plain
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "playlist attempt ${attempt + 1} failed: ${shortUrl(url)}: ${e.message}")
            }
            refreshSession(session)
            url = rewriteWithToken(target, session.token, session.expiresAt)
        }
        return null
    }

    private fun tokenFromUrl(url: String, session: StreamSession): String {
        return try {
            val parts = URI(url).path.split("/").filter { it.isNotEmpty() }
            if (parts.size > 3 && parts[0] == "p") parts[3] else session.token
        } catch (e: Exception) {
            session.token
        }
    }

    private fun cdnHeaders(url: String): Map<String, String> {
        val headers = GoTakuApi.browserHeaders.toMutableMap()
        headers["Referer"] = "https://gotaku.to/"
        headers["Origin"] = "https://gotaku.to"
        return headers
    }

    private fun proxyPath(session: StreamSession, abs: String, route: String): String {
        val enc = URLEncoder.encode(abs, "UTF-8")
        return "/${session.id}/$route/$enc"
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
            URLDecoder.decode(seg, "UTF-8")
        } catch (e: Exception) {
            null
        }
    }

    private fun shortUrl(url: String): String {
        return try {
            val uri = URI(url)
            "${uri.host}${uri.path.substringBeforeLast('/')}/"
        } catch (e: Exception) {
            url.take(60)
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
        } catch (e: Exception) {
            Log.d(TAG, "socket write failed: ${e.message}")
        }
    }

    private fun sendStatus(conn: Socket, code: Int) {
        try {
            val out: OutputStream = conn.getOutputStream()
            out.write("HTTP/1.1 $code Status\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
            out.flush()
        } catch (e: Exception) {
            Log.d(TAG, "status write failed: ${e.message}")
        }
    }

    private fun send404(conn: Socket) {
        sendStatus(conn, 404)
    }
}
