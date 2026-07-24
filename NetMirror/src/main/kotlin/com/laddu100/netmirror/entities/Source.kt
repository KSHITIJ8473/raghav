package com.laddu100.netmirror.entities

/**
 * JW Player-style source from playlist.php.
 * `file` is a URL path (relative to mainUrl) that points to the actual m3u8 stream.
 * `label` is a quality label like "360p", "720p", etc.
 * `type` is the stream type, typically "m3u8".
 */
data class Source(
    val file: String,
    val label: String,
    val type: String
)
