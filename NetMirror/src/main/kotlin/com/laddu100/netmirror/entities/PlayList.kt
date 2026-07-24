package com.laddu100.netmirror.entities

/**
 * Response from playlist.php — a JSON array of PlayListItem objects.
 * Each item contains `sources` (list of stream URLs with quality labels) and
 * optional `tracks` (subtitle files).
 */
class PlayList : ArrayList<PlayListItem>()
