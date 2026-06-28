package com.laddu100

data class NetMirrorEpisode(
    val complate: String,
    val ep: String,
    val id: String,
    val s: String,
    val t: String,
    val time: String
)

data class NetMirrorEpisodesData(
    val episodes: List<NetMirrorEpisode>?,
    val nextPage: Int,
    val nextPageSeason: String,
    val nextPageShow: Int,
)

data class NetMirrorMainPage(
    val post: List<NetMirrorPostCategory>
)

class NetMirrorPlayList : ArrayList<NetMirrorPlayListItem>()

data class NetMirrorPlayListItem(
    val sources: List<NetMirrorSource>,
    val tracks: List<NetMirrorTracks>?,
    val title: String
)

data class NetMirrorPostCategory(
    val ids: String,
    val cate: String
)

data class NetMirrorPostData(
    val desc: String?,
    val director: String?,
    val ua: String?,
    val episodes: List<NetMirrorEpisode?>,
    val genre: String?,
    val nextPage: Int?,
    val nextPageSeason: String?,
    val nextPageShow: Int?,
    val season: List<NetMirrorSeason>?,
    val title: String,
    val year: String,
    val cast: String?,
    val match: String?,
    val runtime: String?,
)

data class NetMirrorSearchData(
    val head: String,
    val searchResult: List<NetMirrorSearchResult>,
    val type: Int
)

data class NetMirrorSearchResult(
    val id: String,
    val t: String
)

data class NetMirrorSeason(
    val ep: String,
    val id: String,
    val s: String,
    val sele: String
)

data class NetMirrorSource(
    val file: String,
    val label: String,
    val type: String
)

data class NetMirrorTracks(
    val kind: String?,
    val file: String?,
    val label: String?,
)
