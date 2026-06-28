package com.laddu100.netmirror.entities

data class SearchData(
    val head: String,
    val searchResult: List<SearchResult>,
    val type: Int
)
