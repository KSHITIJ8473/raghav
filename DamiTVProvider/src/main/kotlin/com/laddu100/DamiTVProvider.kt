package com.laddu100

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.fasterxml.jackson.annotation.JsonProperty
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream
import kotlin.concurrent.thread
import kotlin.math.abs
import com.raghav.donation.DonationManager

class DamiTVProvider : MainAPI() {

    override var mainUrl = "https://damitv.st"
    override var name = "DamiTV"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Live)

    private val dnsCache = ConcurrentHashMap<String, List<InetAddress>>()

    private val customDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            if (hostname == "1.1.1.1") {
                return listOf(InetAddress.getByName("1.1.1.1"))
            }
            dnsCache[hostname]?.let { return it }
            val resolved = resolveDnsDoH(hostname)
            if (resolved.isNotEmpty()) {
                dnsCache[hostname] = resolved
                return resolved
            }
            return Dns.SYSTEM.lookup(hostname)
        }
    }

    private val dohClient = OkHttpClient.Builder()
        .connectTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val dnsClient by lazy {
        app.baseClient.newBuilder()
            .dns(customDns)
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    private fun resolveDnsDoH(hostname: String): List<InetAddress> {
        try {
            val request = Request.Builder()
                .url("https://1.1.1.1/dns-query?name=$hostname&type=A")
                .header("Accept", "application/dns-json")
                .header("User-Agent", "Mozilla/5.0")
                .build()
            dohClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val responseText = response.body?.string() ?: ""
                    val ipRegex = """\"data\"\s*:\s*\"([0-9.]+)\"""".toRegex()
                    val ips = ipRegex.findAll(responseText).map { it.groupValues[1] }.toList()
                    if (ips.isNotEmpty()) {
                        return ips.map { InetAddress.getByName(it) }
                    }
                }
            }
        } catch (_: Exception) {}
        return emptyList()
    }

    private suspend fun apiGet(url: String, headers: Map<String, String>): String {
        val reqBuilder = Request.Builder().url(url)
        headers.forEach { (k, v) -> reqBuilder.header(k, v) }
        dnsClient.newCall(reqBuilder.build()).execute().use { response ->
            return response.body?.string() ?: ""
        }
    }

    private val apiHeaders: Map<String, String>
        get() = mapOf(
            "User-Agent" to CHROME_UA,
            "Accept" to "application/json, text/plain, */*",
            "Referer" to "$mainUrl/",
            "Origin" to mainUrl
        )

    // the segment cdn answers any referer with a forbidden, so these mirror the site's own player
    private val streamHeaders: Map<String, String> = mapOf(
        "User-Agent" to CHROME_UA,
        "Accept" to "*/*",
        "Accept-Language" to "en-US,en;q=0.9",
        "Origin" to "null",
        "Sec-Fetch-Site" to "cross-site",
        "Sec-Fetch-Mode" to "cors",
        "Sec-Fetch-Dest" to "empty"
    )

    data class DamiTvChannel(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("name") val name: String? = null
    )

    data class DamiTeam(
        @JsonProperty("name") val name: String?,
        @JsonProperty("badge") val badge: String?
    )

    data class DamiTeams(
        @JsonProperty("home") val home: DamiTeam?,
        @JsonProperty("away") val away: DamiTeam?
    )

    data class DamiMatch(
        @JsonProperty("id") val id: String,
        @JsonProperty("league") val league: String?,
        @JsonProperty("title") val title: String,
        @JsonProperty("category") val category: String?,
        @JsonProperty("date") val date: Long?,
        @JsonProperty("popular") val popular: Boolean?,
        @JsonProperty("poster") val poster: String?,
        @JsonProperty("ppvPoster") val ppvPoster: String? = null,
        @JsonProperty("status") val status: String?,
        @JsonProperty("viewers") val viewers: Int?,
        @JsonProperty("teams") val teams: DamiTeams? = null,
        @JsonProperty("tvChannels") val tvChannels: List<DamiTvChannel>? = null,
        @JsonProperty("ppvId") val ppvId: String? = null,
        @JsonProperty("always_live") val alwaysLive: Boolean? = null
    )

    data class TvListItem(
        @JsonProperty("id") val id: String,
        @JsonProperty("name") val name: String,
        @JsonProperty("image") val image: String? = null,
        @JsonProperty("country") val country: String? = null
    )

    data class TvListResponse(
        @JsonProperty("channels") val channels: List<TvListItem> = emptyList()
    )

    data class MsrcSource(
        @JsonProperty("url") val url: String,
        @JsonProperty("label") val label: String?
    )

    data class ExtractUrlResponse(
        @JsonProperty("success") val success: Boolean,
        @JsonProperty("hlsUrl") val hlsUrl: String? = null,
        @JsonProperty("embedUrl") val embedUrl: String? = null,
        @JsonProperty("error") val error: String? = null
    )

    data class HlsTokenResponse(
        @JsonProperty("tk") val tk: String,
        @JsonProperty("e") val e: Long
    )

    data class EventLoadData(
        val title: String,
        val url: String,
        val posterUrl: String?,
        val category: String?,
        val status: String? = null,
        val date: Long? = null,
        val league: String? = null,
        val teams: DamiTeams? = null,
        val ppvId: String? = null,
        val tvChannels: List<DamiTvChannel>? = null
    )

    data class ChannelLoadData(
        val channelId: String? = null,
        val channelName: String? = null,
        val channelPoster: String? = null,
        val channelCountry: String? = null
    )

    data class StreamLoadData(
        val title: String,
        val streams: List<StreamInfo>
    )

    data class StreamInfo(
        val name: String,
        val url: String
    )

    // each hls segment arrives as an image hiding the transport stream, the site
    // player rebuilds plain ts in javascript, these helpers do the same for exoplayer
    private object SegmentDecoder {
        fun decode(data: ByteArray): ByteArray? {
            if (data.size > 377 && data[0] == 0x47.toByte()) return data
            if (data.size > 16 && data[0] == 0x89.toByte() && data[1] == 0x50.toByte()) {
                val pixels = PngPayload.decode(data)
                if (pixels != null && pixels.size >= 16 &&
                    String(pixels, 0, 8, Charsets.ISO_8859_1) == "TIKTIKPX"
                ) {
                    val ts = gunzipAfterMagic(pixels)
                    if (ts != null && ts.size > 188 && ts[0] == 0x47.toByte()) return ts
                }
            }
            return scanForTransportStream(data)
        }

        // webp and jpeg keep the ts verbatim behind the image headers, three aligned sync bytes find it
        private fun scanForTransportStream(data: ByteArray): ByteArray? {
            if (data.size < 0x179) return null
            val isImage = (data[0] == 0x89.toByte() && data[1] == 0x50.toByte()) ||
                (data[0] == 0xff.toByte() && data[1] == 0xd8.toByte()) ||
                (data[0] == 0x52.toByte() && data[1] == 0x49.toByte())
            if (!isImage) return null
            val limit = minOf(data.size - 0x179, 0x10000)
            for (i in 0 until limit) {
                if (data[i] == 0x47.toByte() &&
                    data[i + 188] == 0x47.toByte() &&
                    data[i + 376] == 0x47.toByte()
                ) {
                    return data.copyOfRange(i, data.size)
                }
            }
            return null
        }

        private fun gunzipAfterMagic(pixels: ByteArray): ByteArray? {
            val searchEnd = minOf(pixels.size - 3, 64)
            for (i in 0 until searchEnd) {
                if (pixels[i] == 0x1f.toByte() &&
                    pixels[i + 1] == 0x8b.toByte() &&
                    pixels[i + 2] == 0x08.toByte()
                ) {
                    return try {
                        GZIPInputStream(ByteArrayInputStream(pixels, i, pixels.size - i)).readBytes()
                    } catch (_: Exception) {
                        null
                    }
                }
            }
            return null
        }
    }

    // png decoder for the one flavour the site emits, eight bit truecolor with scanline filters
    private object PngPayload {
        fun decode(data: ByteArray): ByteArray? {
            try {
                if (data.size < 57) return null
                var pos = 8
                var width = 0
                var height = 0
                var bitDepth = 0
                var colorType = 0
                var interlace = 0
                var sawEnd = false
                val idat = ByteArrayOutputStream()
                while (pos + 12 <= data.size) {
                    val length = readInt(data, pos)
                    val type = String(data, pos + 4, 4, Charsets.ISO_8859_1)
                    if (length < 0 || pos + 12 + length > data.size) return null
                    when (type) {
                        "IHDR" -> {
                            if (length < 13) return null
                            width = readInt(data, pos + 8)
                            height = readInt(data, pos + 12)
                            bitDepth = data[pos + 16].toInt() and 0xff
                            colorType = data[pos + 17].toInt() and 0xff
                            interlace = data[pos + 20].toInt() and 0xff
                        }
                        "IDAT" -> idat.write(data, pos + 8, length)
                        "IEND" -> sawEnd = true
                    }
                    pos += 12 + length
                    if (sawEnd) break
                }
                if (!sawEnd || width <= 0 || height <= 0 || interlace != 0) return null
                val channels = when (colorType) {
                    0 -> 1
                    2 -> 3
                    4 -> 2
                    6 -> 4
                    else -> return null
                }
                if (bitDepth != 8) return null
                val stride = width * channels
                if (stride <= 0 || height.toLong() * stride > MAX_PIXEL_BYTES) return null
                val inflated = InflaterInputStream(ByteArrayInputStream(idat.toByteArray())).readBytes()
                if (inflated.size < height * (stride + 1)) return null
                val out = ByteArray(height * stride)
                var src = 0
                var prevOff = -1
                for (y in 0 until height) {
                    val filter = inflated[src].toInt() and 0xff
                    src++
                    val rowOff = y * stride
                    System.arraycopy(inflated, src, out, rowOff, stride)
                    src += stride
                    when (filter) {
                        0 -> {}
                        1 -> for (i in channels until stride) {
                            out[rowOff + i] = (out[rowOff + i] + out[rowOff + i - channels]).toByte()
                        }
                        2 -> if (prevOff >= 0) {
                            for (i in 0 until stride) {
                                out[rowOff + i] = (out[rowOff + i] + out[prevOff + i]).toByte()
                            }
                        }
                        3 -> for (i in 0 until stride) {
                            val left = if (i >= channels) out[rowOff + i - channels].toInt() and 0xff else 0
                            val up = if (prevOff >= 0) out[prevOff + i].toInt() and 0xff else 0
                            out[rowOff + i] = (out[rowOff + i] + ((left + up) ushr 1)).toByte()
                        }
                        4 -> for (i in 0 until stride) {
                            val a = if (i >= channels) out[rowOff + i - channels].toInt() and 0xff else 0
                            val b = if (prevOff >= 0) out[prevOff + i].toInt() and 0xff else 0
                            val c = if (i >= channels && prevOff >= 0) out[prevOff + i - channels].toInt() and 0xff else 0
                            val p = a + b - c
                            val pa = abs(p - a)
                            val pb = abs(p - b)
                            val pc = abs(p - c)
                            val pr = if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
                            out[rowOff + i] = (out[rowOff + i] + pr).toByte()
                        }
                        else -> return null
                    }
                    prevOff = rowOff
                }
                return out
            } catch (_: Exception) {
                return null
            }
        }

        private fun readInt(data: ByteArray, offset: Int): Int =
            ((data[offset].toInt() and 0xff) shl 24) or
                ((data[offset + 1].toInt() and 0xff) shl 16) or
                ((data[offset + 2].toInt() and 0xff) shl 8) or
                (data[offset + 3].toInt() and 0xff)

        private const val MAX_PIXEL_BYTES = 96L * 1024 * 1024
    }

    // the playlist host demands a referer while the segment cdn forbids one and the
    // segments arrive as images, so the loopback proxy rewrites and rebuilds plain ts
    private object ManifestProxy {
        @Volatile private var server: ServerSocket? = null
        @Volatile private var fetchClient: OkHttpClient? = null
        @Volatile private var tk = ""
        @Volatile private var tkExpires = 0L
        private val segmentHosts = ConcurrentHashMap<String, Long>()

        fun ensure(client: OkHttpClient): Boolean {
            fetchClient = client.newBuilder()
                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            if (server != null && server?.isClosed == false) return true
            return try {
                val socket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
                server = socket
                thread(isDaemon = true, name = "damitv-manifest-proxy") { acceptLoop(socket) }
                true
            } catch (_: Exception) {
                server = null
                false
            }
        }

        fun isRunning(): Boolean = server != null && server?.isClosed == false

        fun port(): Int = server?.localPort ?: -1

        fun channelUrl(channelId: String): String {
            return "http://127.0.0.1:${port()}/c/$channelId.m3u8"
        }

        fun playlistUrl(upstream: String): String {
            return "http://127.0.0.1:${port()}/m/${encode(upstream)}"
        }

        private fun segmentUrl(upstream: String): String {
            return "http://127.0.0.1:${port()}/s/${encode(upstream)}.ts"
        }

        private fun encode(value: String): String {
            return android.util.Base64.encodeToString(
                value.toByteArray(Charsets.UTF_8),
                android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP
            )
        }

        private fun decode(value: String): String? {
            return try {
                String(android.util.Base64.decode(value, android.util.Base64.URL_SAFE), Charsets.UTF_8)
            } catch (_: Exception) {
                null
            }
        }

        private fun acceptLoop(socket: ServerSocket) {
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (_: Exception) {
                    break
                }
                thread(isDaemon = true) { serve(client) }
            }
        }

        private class Upstream(val code: Int, val body: ByteArray?)

        private fun serve(client: Socket) {
            try {
                client.soTimeout = 65000
                client.use { socket ->
                    val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.ISO_8859_1))
                    val requestLine = reader.readLine() ?: return
                    val parts = requestLine.split(" ")
                    if (parts.size < 2 || parts[0] != "GET") {
                        respond(socket, 405, "method not allowed".toByteArray())
                        return
                    }
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                    }
                    val route = parts[1].substringBefore('?')
                    when {
                        route.startsWith("/c/") -> {
                            val id = route.removePrefix("/c/")
                                .substringBefore('/')
                                .removeSuffix(".m3u8")
                            if (!id.matches(Regex("[A-Za-z0-9_-]{3,64}"))) {
                                respond(socket, 404, "not found".toByteArray())
                            } else {
                                serveChannel(socket, id)
                            }
                        }
                        route.startsWith("/m/") -> {
                            val url = decode(route.removePrefix("/m/").substringBefore('/'))
                            if (url == null || !isSiteHost(url)) {
                                respond(socket, 404, "not found".toByteArray())
                            } else {
                                serveSitePlaylist(socket, url)
                            }
                        }
                        route.startsWith("/s/") -> {
                            val url = decode(route.removePrefix("/s/").substringBefore('.'))
                            if (url == null || !isAllowedSegmentHost(url)) {
                                respond(socket, 403, "host not allowed".toByteArray())
                            } else {
                                serveSegment(socket, url)
                            }
                        }
                        else -> respond(socket, 404, "not found".toByteArray())
                    }
                }
            } catch (_: Exception) {}
        }

        private fun serveChannel(socket: Socket, id: String) {
            val referer = "$PLAY_BASE/player/hls/?v=1&url=%2Fc%2F$id.m3u8&name=live"
            var upstream = fetch("$PLAY_BASE/c/$id.m3u8?tk=${token()}&e=$tkExpires", referer)
            if (upstream.code == 403 || upstream.code == 502) {
                refreshToken()
                upstream = fetch("$PLAY_BASE/c/$id.m3u8?tk=$tk&e=$tkExpires", referer)
            }
            servePlaylistBody(socket, upstream, "$PLAY_BASE/c/$id.m3u8")
        }

        private fun serveSitePlaylist(socket: Socket, url: String) {
            var upstream = fetch(url, "$SITE/")
            if (upstream.code == 403) {
                val resigned = resignToken(url)
                if (resigned != null) upstream = fetch(resigned, "$SITE/")
            }
            servePlaylistBody(socket, upstream, url)
        }

        private fun servePlaylistBody(socket: Socket, upstream: Upstream, baseUrl: String) {
            when {
                upstream.code == 410 -> respond(socket, 410, upstream.body ?: ByteArray(0))
                upstream.code != 200 || upstream.body == null ->
                    respond(socket, 502, "upstream failed".toByteArray())
                else -> {
                    val rewritten = rewritePlaylist(String(upstream.body, Charsets.UTF_8), baseUrl)
                    if (rewritten == null) {
                        respond(socket, 502, "playlist rewrite failed".toByteArray())
                    } else {
                        respond(socket, 200, rewritten)
                    }
                }
            }
        }

        private fun serveSegment(socket: Socket, url: String) {
            val upstream = fetch(url, null, streamFetchHeaders)
            val body = upstream.body
            if (upstream.code != 200 || body == null) {
                respond(socket, 502, "upstream failed".toByteArray())
                return
            }
            val ts = SegmentDecoder.decode(body)
            if (ts == null) {
                respond(socket, 502, "segment decode failed".toByteArray())
            } else {
                respond(socket, 200, ts)
            }
        }

        private fun rewritePlaylist(body: String, baseUrl: String): ByteArray? {
            try {
                val out = StringBuilder()
                for (raw in body.split("\n")) {
                    val line = raw.trimEnd('\r')
                    if (line.isEmpty()) {
                        out.append('\n')
                        continue
                    }
                    if (line.startsWith("#")) {
                        if (line.startsWith("#EXT-X-KEY") || line.startsWith("#EXT-X-MAP")) {
                            out.append(rewriteTagUri(line, baseUrl)).append('\n')
                        } else {
                            out.append(line).append('\n')
                        }
                        continue
                    }
                    val absolute = resolveUrl(baseUrl, line) ?: continue
                    rememberSegmentHost(absolute)
                    out.append(segmentUrl(absolute)).append('\n')
                }
                return out.toString().toByteArray(Charsets.UTF_8)
            } catch (_: Exception) {
                return null
            }
        }

        private fun rewriteTagUri(line: String, baseUrl: String): String {
            val match = Regex("URI=\"([^\"]+)\"").find(line) ?: return line
            val absolute = resolveUrl(baseUrl, match.groupValues[1]) ?: return line
            rememberSegmentHost(absolute)
            return line.replaceRange(match.groups[1]!!.range, segmentUrl(absolute))
        }

        private fun resolveUrl(baseUrl: String, target: String): String? {
            return try {
                URI(baseUrl).resolve(target.trim()).toString()
            } catch (_: Exception) {
                null
            }
        }

        // only hosts seen inside a proxied playlist may be fetched, never an open relay
        private fun rememberSegmentHost(url: String) {
            try {
                val host = URI(url).host ?: return
                segmentHosts[host] = System.currentTimeMillis() + HOST_TTL_MS
            } catch (_: Exception) {}
        }

        private fun isAllowedSegmentHost(url: String): Boolean {
            val host = try {
                URI(url).host ?: return false
            } catch (_: Exception) {
                return false
            }
            val expiry = segmentHosts[host] ?: return false
            return expiry > System.currentTimeMillis()
        }

        private fun isSiteHost(url: String): Boolean {
            val host = try {
                URI(url).host ?: ""
            } catch (_: Exception) {
                ""
            }
            return host == "damitv.st" || host.endsWith(".damitv.st")
        }

        private fun token(): String {
            val now = System.currentTimeMillis() / 1000
            if (tk.isBlank() || tkExpires - now < 300) refreshToken()
            return tk
        }

        private fun refreshToken() {
            synchronized(this) {
                val upstream = fetch("$PLAY_BASE/papi/hls-token", "$PLAY_BASE/")
                if (upstream.code == 200 && upstream.body != null) {
                    try {
                        val parsed = parseJson<HlsTokenResponse>(String(upstream.body, Charsets.UTF_8))
                        if (parsed.tk.isNotBlank()) {
                            tk = parsed.tk
                            tkExpires = parsed.e
                        }
                    } catch (_: Exception) {}
                }
            }
        }

        // signed playlist urls die with their embedded token, a fresh one revives them
        private fun resignToken(url: String): String? {
            refreshToken()
            return try {
                val uri = URI(url)
                val query = uri.rawQuery ?: return null
                val kept = query.split('&').filter { !it.startsWith("tk=") && !it.startsWith("e=") }
                val base = url.substringBefore('?')
                "$base?tk=$tk&e=$tkExpires" + if (kept.isEmpty()) "" else "&${kept.joinToString("&")}"
            } catch (_: Exception) {
                null
            }
        }

        private fun fetch(
            url: String,
            referer: String?,
            extraHeaders: Map<String, String> = emptyMap()
        ): Upstream {
            return try {
                val builder = Request.Builder()
                    .url(url)
                    .header("User-Agent", CHROME_UA)
                    .header("Accept", "*/*")
                    .header("Accept-Language", "en-US,en;q=0.9")
                if (referer != null) builder.header("Referer", referer)
                extraHeaders.forEach { (k, v) -> builder.header(k, v) }
                fetchClient?.newCall(builder.build())?.execute()?.use { response ->
                    val body = if (response.isSuccessful || response.code == 410) {
                        response.body?.bytes()
                    } else {
                        null
                    }
                    Upstream(response.code, body)
                } ?: Upstream(0, null)
            } catch (_: Exception) {
                Upstream(0, null)
            }
        }

        private fun respond(socket: Socket, code: Int, body: ByteArray) {
            val reason = when (code) {
                200 -> "OK"
                403 -> "Forbidden"
                404 -> "Not Found"
                405 -> "Method Not Allowed"
                410 -> "Gone"
                else -> "Bad Gateway"
            }
            val header = "HTTP/1.1 $code $reason\r\n" +
                "Content-Type: application/vnd.apple.mpegurl\r\n" +
                "Content-Length: ${body.size}\r\n" +
                "Connection: close\r\n\r\n"
            socket.getOutputStream().use { out ->
                out.write(header.toByteArray(Charsets.ISO_8859_1))
                out.write(body)
                out.flush()
            }
        }

        private const val HOST_TTL_MS = 60L * 60 * 1000

        // the segment cdn rejects referers, the browser sends null origin and fetch metadata
        private val streamFetchHeaders: Map<String, String> = mapOf(
            "Origin" to "null",
            "Sec-Fetch-Site" to "cross-site",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Dest" to "empty"
        )
    }

    private fun formatMatchDate(timestamp: Long?): String {
        if (timestamp == null) return "soon"
        return try {
            val date = java.util.Date(timestamp)
            val sdf = java.text.SimpleDateFormat("dd MMM, HH:mm", java.util.Locale.US)
            sdf.timeZone = java.util.TimeZone.getDefault()
            sdf.format(date)
        } catch (_: Exception) {
            "soon"
        }
    }

    // one retry absorbs the transient failures damitv is known for
    private suspend fun fetchExtractUrl(id: String): ExtractUrlResponse? {
        repeat(2) {
            try {
                val text = apiGet("$mainUrl/papi/extract-url/${java.net.URLEncoder.encode(id, "UTF-8")}", apiHeaders)
                val resp = parseJson<ExtractUrlResponse>(text)
                if (resp.success && !resp.hlsUrl.isNullOrBlank()) return resp
            } catch (_: Exception) {}
        }
        return null
    }

    private fun isSiteUrl(url: String): Boolean {
        val host = try {
            URI(url).host ?: ""
        } catch (_: Exception) {
            ""
        }
        return host == "damitv.st" || host.endsWith(".damitv.st")
    }

    private fun absoluteImageUrl(poster: String?): String {
        if (poster.isNullOrBlank()) return ""
        return if (poster.startsWith("http")) poster else "$mainUrl$poster"
    }

    private fun matchPoster(match: DamiMatch): String {
        return absoluteImageUrl(
            match.poster ?: match.ppvPoster ?: match.teams?.home?.badge ?: match.teams?.away?.badge
        )
    }

    private fun matchToSearchResponse(match: DamiMatch): SearchResponse {
        val posterUrl = matchPoster(match)
        val loadData = EventLoadData(
            title = match.title,
            url = match.id,
            posterUrl = posterUrl,
            category = match.category,
            status = match.status,
            date = match.date,
            league = match.league,
            teams = match.teams,
            ppvId = match.ppvId,
            tvChannels = match.tvChannels
        )
        return newLiveSearchResponse(match.title, loadData.toJson(), TvType.Live) {
            this.posterUrl = posterUrl
        }
    }

    private fun matchToUpcomingSearchResponse(match: DamiMatch): SearchResponse {
        val dateStr = formatMatchDate(match.date)
        val title = "${match.title} [Upcoming - Starts: $dateStr]"
        val posterUrl = matchPoster(match)
        val loadData = EventLoadData(
            title = match.title,
            url = match.id,
            posterUrl = posterUrl,
            category = match.category,
            status = match.status,
            date = match.date,
            league = match.league,
            teams = match.teams,
            ppvId = match.ppvId,
            tvChannels = match.tvChannels
        )
        return newLiveSearchResponse(title, loadData.toJson(), TvType.Live) {
            this.posterUrl = posterUrl
        }
    }

    private fun channelToSearchResponse(item: TvListItem): SearchResponse {
        val loadData = ChannelLoadData(
            channelId = item.id,
            channelName = item.name,
            channelPoster = absoluteImageUrl(item.image),
            channelCountry = item.country
        )
        return newLiveSearchResponse(item.name, loadData.toJson(), TvType.Live) {
            this.posterUrl = loadData.channelPoster
        }
    }

    private fun isChannelCategory(category: String?): Boolean {
        val cat = category?.lowercase() ?: return false
        return cat == "24/7-streams" || cat == "live-tv" || cat == "channels" || cat.contains("stream")
    }

    private suspend fun refreshDomain() {
        FirebaseDomainHelper.getDomain("damitv")?.takeIf { it.contains("damitv") }?.let { mainUrl = it.trimEnd('/') }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        DonationManager.checkAndShow()
        refreshDomain()
        val lists = mutableListOf<HomePageList>()

        try {
            val tvText = apiGet("$mainUrl/papi/tv-list", apiHeaders)
            val tvList = parseJson<TvListResponse>(tvText)
            if (tvList.channels.isNotEmpty()) {
                val items = tvList.channels.map { channelToSearchResponse(it) }
                lists.add(HomePageList("Live Sports Channels", items, isHorizontalImages = true))
            }
        } catch (_: Exception) {}

        try {
            val allText = apiGet("$mainUrl/papi/matches/all", apiHeaders)
            val allMatches = parseJson<List<DamiMatch>>(allText)

            val popular = try {
                val popularText = apiGet("$mainUrl/papi/matches/all/popular", apiHeaders)
                parseJson<List<DamiMatch>>(popularText).ifEmpty {
                    allMatches.filter { it.popular == true }
                }
            } catch (_: Exception) {
                allMatches.filter { it.popular == true }
            }
            if (popular.isNotEmpty()) {
                lists.add(HomePageList("Popular Now", popular.map { matchToSearchResponse(it) }, isHorizontalImages = true))
            }

            val liveMatches = allMatches.filter { it.status?.lowercase() == "live" && !isChannelCategory(it.category) }
            if (liveMatches.isNotEmpty()) {
                lists.add(HomePageList("Live Sports Events", liveMatches.map { matchToSearchResponse(it) }, isHorizontalImages = true))
            }

            val upcomingMatches = allMatches.filter { it.status?.lowercase() == "upcoming" && !isChannelCategory(it.category) }
            if (upcomingMatches.isNotEmpty()) {
                lists.add(HomePageList("Upcoming Matches (Live soon)", upcomingMatches.map { matchToUpcomingSearchResponse(it) }, isHorizontalImages = true))
            }

            val channels = allMatches.filter { isChannelCategory(it.category) }
            if (channels.isNotEmpty()) {
                lists.add(HomePageList("24/7 Channels & Live TV", channels.map { matchToSearchResponse(it) }, isHorizontalImages = true))
            }
        } catch (_: Exception) {}

        return newHomePageResponse(lists, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        refreshDomain()
        val results = mutableListOf<SearchResponse>()
        try {
            val text = apiGet("$mainUrl/papi/matches/all", apiHeaders)
            val allMatches = parseJson<List<DamiMatch>>(text)
            results.addAll(allMatches.filter { match ->
                match.title.contains(query, ignoreCase = true) ||
                (match.league?.contains(query, ignoreCase = true) ?: false) ||
                (match.category?.contains(query, ignoreCase = true) ?: false) ||
                (match.teams?.home?.name?.contains(query, ignoreCase = true) ?: false) ||
                (match.teams?.away?.name?.contains(query, ignoreCase = true) ?: false)
            }.map { match ->
                if (match.status == "upcoming") {
                    matchToUpcomingSearchResponse(match)
                } else {
                    matchToSearchResponse(match)
                }
            })
        } catch (_: Exception) {}

        try {
            val tvText = apiGet("$mainUrl/papi/tv-list", apiHeaders)
            val tvList = parseJson<TvListResponse>(tvText)
            results.addAll(tvList.channels.filter { ch ->
                ch.name.contains(query, ignoreCase = true) ||
                (ch.country?.contains(query, ignoreCase = true) ?: false)
            }.map { channelToSearchResponse(it) })
        } catch (_: Exception) {}
        return results
    }

    // the msrc answer is a player page path whose url query param carries the playlist path
    private fun playlistPathFromPlayerUrl(playerUrl: String): String? {
        return try {
            val query = playerUrl.substringAfter('?', "")
            for (pair in query.split('&')) {
                val kv = pair.split('=', limit = 2)
                if (kv.size == 2 && kv[0] == "url") {
                    val path = java.net.URLDecoder.decode(kv[1], "UTF-8")
                    if (path.startsWith("/")) return path
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    // the channel host only answers hex ids, slug style ids belong to the ppv playlists
    private fun isChannelPlaylistPath(path: String): Boolean {
        val id = path.removePrefix("/c/").removeSuffix(".m3u8")
        return id.length in 8..12 && id.all { it.isDigit() || it in 'a'..'f' }
    }

    private suspend fun buildMatchStreams(event: EventLoadData): List<StreamInfo> {
        val streams = mutableListOf<StreamInfo>()
        val seenPaths = mutableSetOf<String>()
        val isUpcoming = event.status?.lowercase() == "upcoming"
        val dateStr = formatMatchDate(event.date)

        if (!event.ppvId.isNullOrBlank()) {
            val mainName = if (isUpcoming) "Main Stream (Live soon: $dateStr)" else "Main Stream"
            streams.add(StreamInfo(name = mainName, url = "ppv://${event.ppvId}"))
        }

        try {
            val stamp = System.currentTimeMillis()
            val text = apiGet("$mainUrl/papi/msrc-for?id=${java.net.URLEncoder.encode(event.url, "UTF-8")}&t=$stamp", apiHeaders)
            val sources = parseJson<List<MsrcSource>>(text)
            sources.forEach { src ->
                val path = playlistPathFromPlayerUrl(src.url) ?: return@forEach
                if (!path.startsWith("/c/") || !isChannelPlaylistPath(path)) return@forEach
                if (seenPaths.add(path)) {
                    streams.add(StreamInfo(name = src.label ?: "Server ${seenPaths.size}", url = "chan://$path"))
                }
            }
        } catch (_: Exception) {}

        // channel entries without an id have no playable path on the site
        event.tvChannels?.forEach { ch ->
            val id = ch.id
            if (id.isNullOrBlank() || ch.name.isNullOrBlank()) return@forEach
            if (!isChannelPlaylistPath("/c/$id.m3u8")) return@forEach
            val path = "/c/$id.m3u8"
            if (seenPaths.add(path)) {
                val chName = if (isUpcoming) "${ch.name} (Upcoming)" else ch.name
                streams.add(StreamInfo(name = chName, url = "chan://$path"))
            }
        }

        if (streams.isEmpty()) {
            val mainName = if (isUpcoming) "Main Stream (Live soon: $dateStr)" else "Main Stream"
            streams.add(StreamInfo(name = mainName, url = "ppv://${event.url}"))
        }
        return streams
    }

    override suspend fun load(url: String): LoadResponse {
        try {
            val channelData = parseJson<ChannelLoadData>(url)
            if (!channelData.channelId.isNullOrBlank()) {
                val title = channelData.channelName ?: channelData.channelId
                val streams = listOf(StreamInfo(name = "Live", url = "chan:///c/${channelData.channelId}.m3u8"))
                val streamData = StreamLoadData(title, streams)
                val plot = buildString {
                    append("Live sports channel")
                    channelData.channelCountry?.takeIf { it.isNotBlank() }?.let { append(" ($it)") }
                }
                return newLiveStreamLoadResponse(title, url, streamData.toJson()) {
                    this.posterUrl = channelData.channelPoster
                    this.plot = plot
                }
            }
        } catch (_: Exception) {}

        val eventData = parseJson<EventLoadData>(url)
        val title = eventData.title
        val isUpcoming = eventData.status?.lowercase() == "upcoming"
        val dateStr = formatMatchDate(eventData.date)

        val streams = buildMatchStreams(eventData)
        val streamData = StreamLoadData(title, streams)

        val plot = buildString {
            eventData.league?.takeIf { it.isNotBlank() }?.let { append("League: $it\n") }
            eventData.teams?.let { t ->
                t.home?.name?.takeIf { it.isNotBlank() }?.let { append("Home: $it\n") }
                t.away?.name?.takeIf { it.isNotBlank() }?.let { append("Away: $it\n") }
            }
            eventData.status?.takeIf { it.isNotBlank() }?.let {
                append("Status: ${it.replaceFirstChar { c -> c.uppercase() }}\n")
            }
            if (eventData.date != null) append("Starts: $dateStr")
        }.trim()

        return newLiveStreamLoadResponse(title, url, streamData.toJson()) {
            this.posterUrl = eventData.posterUrl
            this.plot = plot
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val streamData = try {
            parseJson<StreamLoadData>(data)
        } catch (_: Exception) {
            return false
        }

        if (streamData.streams.isEmpty()) return false

        val proxyReady = ManifestProxy.ensure(dnsClient)
        var token: HlsTokenResponse? = null
        var foundAny = false

        streamData.streams.forEach { stream ->
            try {
                val streamUrl = when {
                    stream.url.startsWith("ppv://") -> {
                        val targetId = stream.url.removePrefix("ppv://")
                        val response = fetchExtractUrl(targetId)
                        val hlsUrl = response?.hlsUrl
                        if (hlsUrl.isNullOrBlank()) return@forEach
                        if (proxyReady && isSiteUrl(hlsUrl)) {
                            ManifestProxy.playlistUrl(hlsUrl)
                        } else {
                            hlsUrl
                        }
                    }
                    stream.url.startsWith("chan://") -> {
                        val channelId = stream.url.removePrefix("chan://")
                            .removePrefix("/c/")
                            .substringBefore('/')
                            .removeSuffix(".m3u8")
                        if (proxyReady) {
                            ManifestProxy.channelUrl(channelId)
                        } else {
                            if (token == null) token = fetchHlsToken()
                            val tk = token ?: return@forEach
                            "$PLAY_BASE/c/$channelId.m3u8?tk=${tk.tk}&e=${tk.e}"
                        }
                    }
                    else -> return@forEach
                }
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = stream.name,
                        url = streamUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.headers = streamHeaders
                    }
                )
                foundAny = true
            } catch (_: Exception) {}
        }

        return foundAny
    }

    private suspend fun fetchHlsToken(): HlsTokenResponse? {
        repeat(2) {
            try {
                val text = apiGet("$PLAY_BASE/papi/hls-token", apiHeaders)
                val resp = parseJson<HlsTokenResponse>(text)
                if (resp.tk.isNotBlank()) return resp
            } catch (_: Exception) {}
        }
        return null
    }

    // fallback when the loopback server cannot bind, at the cost of the okhttp player stack
    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? {
        if (ManifestProxy.isRunning()) return null
        return Interceptor { chain ->
            val request = chain.request()
            val host = request.url.host
            val builder = request.newBuilder()
            builder.header("User-Agent", CHROME_UA)
            builder.header("Accept", "*/*")
            builder.header("Accept-Language", "en-US,en;q=0.9")
            builder.removeHeader("Referer")
            builder.removeHeader("Origin")
            builder.removeHeader("Sec-Fetch-Site")
            builder.removeHeader("Sec-Fetch-Mode")
            builder.removeHeader("Sec-Fetch-Dest")
            if (host == "damitv.st" || host.endsWith(".damitv.st") ||
                host == "ondemand.st" || host.endsWith(".ondemand.st")
            ) {
                builder.header("Referer", SITE_REFERER)
            } else {
                builder.header("Origin", "null")
                builder.header("Sec-Fetch-Site", "cross-site")
                builder.header("Sec-Fetch-Mode", "cors")
                builder.header("Sec-Fetch-Dest", "empty")
            }
            chain.proceed(builder.build())
        }
    }

    companion object {
        private const val CHROME_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/157.0.0.0 Safari/537.36"

        // the player host kept its own domain through the site renames, it never follows mainUrl
        private const val PLAY_BASE = "https://play.damitv.st"

        private const val SITE_REFERER = "https://damitv.st/"

        private const val SITE = "https://damitv.st"
    }
}
