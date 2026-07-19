package com.laddu100.netmirror.entities

data class PlayListItem(
    val image: String? = null,
    val image2: String? = null,
    val sources: List<Source>? = null,
    val tracks: List<Tracks>? = null,
    val title: String? = null
)
