package com.playfy

object PlayFyProviderManager {
    private val providers = listOf(
        mapOf("id" to 1, "title" to "India TV", "image" to "", "catLink" to "https://iptv-org.github.io/iptv/countries/in.m3u"),
        mapOf("id" to 2, "title" to "Hindi Channels", "image" to "", "catLink" to "https://iptv-org.github.io/iptv/languages/hin.m3u"),
        mapOf("id" to 3, "title" to "Sports", "image" to "", "catLink" to "https://iptv-org.github.io/iptv/categories/sports.m3u"),
        mapOf("id" to 4, "title" to "Movies", "image" to "", "catLink" to "https://iptv-org.github.io/iptv/categories/movies.m3u"),
        mapOf("id" to 5, "title" to "Entertainment", "image" to "", "catLink" to "https://iptv-org.github.io/iptv/categories/entertainment.m3u"),
        mapOf("id" to 6, "title" to "News", "image" to "", "catLink" to "https://iptv-org.github.io/iptv/categories/news.m3u"),
        mapOf("id" to 7, "title" to "Kids", "image" to "", "catLink" to "https://iptv-org.github.io/iptv/categories/kids.m3u"),
        mapOf("id" to 8, "title" to "Music", "image" to "", "catLink" to "https://iptv-org.github.io/iptv/categories/music.m3u"),
        mapOf("id" to 9, "title" to "All Channels", "image" to "", "catLink" to "https://iptv-org.github.io/iptv/index.m3u"),
        mapOf("id" to 10, "title" to "USA TV", "image" to "", "catLink" to "https://iptv-org.github.io/iptv/countries/us.m3u"),
        mapOf("id" to 11, "title" to "UK TV", "image" to "", "catLink" to "https://iptv-org.github.io/iptv/countries/uk.m3u"),
        mapOf("id" to 12, "title" to "Bangladesh TV", "image" to "", "catLink" to "https://iptv-org.github.io/iptv/countries/bd.m3u"),
        mapOf("id" to 13, "title" to "Pakistan TV", "image" to "", "catLink" to "https://iptv-org.github.io/iptv/countries/pk.m3u"),
        mapOf("id" to 14, "title" to "Sri Lanka TV", "image" to "", "catLink" to "https://iptv-org.github.io/iptv/countries/lk.m3u"),
        mapOf("id" to 15, "title" to "Nepal TV", "image" to "", "catLink" to "https://iptv-org.github.io/iptv/countries/np.m3u"),
        mapOf("id" to 16, "title" to "Arabic TV", "image" to "", "catLink" to "https://iptv-org.github.io/iptv/languages/ara.m3u"),
        mapOf("id" to 17, "title" to "Tamil TV", "image" to "", "catLink" to "https://iptv-org.github.io/iptv/languages/tam.m3u"),
        mapOf("id" to 18, "title" to "Telugu TV", "image" to "", "catLink" to "https://iptv-org.github.io/iptv/languages/tel.m3u"),
        mapOf("id" to 19, "title" to "Bengali TV", "image" to "", "catLink" to "https://iptv-org.github.io/iptv/languages/ben.m3u"),
        mapOf("id" to 20, "title" to "Urdu TV", "image" to "", "catLink" to "https://iptv-org.github.io/iptv/languages/urd.m3u")
    )

    suspend fun fetchProviders(): List<Map<String, Any>> {
        return providers
    }
}
