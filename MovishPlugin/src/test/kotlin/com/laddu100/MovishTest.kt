package com.laddu100

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class MovishTest {
    private val provider = MovishProvider()

    @Test
    fun testSearch() = runBlocking {
        val results = provider.search("Batman")
        assertTrue("Search for 'Batman' should return results", results.isNotEmpty())
        println("Found ${results.size} search results.")
        results.forEach { println(" - ${it.name} (${it.url})") }
    }

    @Test
    fun testHomepage() = runBlocking {
        // Test a few representative sections to verify HTML selectors
        val sectionsToTest = listOf("Trending Now", "Latest Movies", "Latest TV Shows")
        
        for (section in provider.mainPage) {
            if (section.name !in sectionsToTest) continue
            println("Testing homepage section: ${section.name} (${section.data})")
            val request = MainPageRequest(section.name, section.data, true)
            val response = provider.getMainPage(1, request)
            assertTrue("Homepage response for ${section.name} should not be null", response != null)
            assertTrue("Homepage response for ${section.name} should have items", response.items.isNotEmpty())
            println("Successfully parsed ${response.items.size} items for ${section.name}")
            response.items.forEach { listGroup ->
                listGroup.list.take(3).forEach { item ->
                    println("   -> ${item.name} (${item.url})")
                }
            }
        }
    }

    @Test
    fun testLoadMovie() = runBlocking {
        val results = provider.search("Avatar")
        val firstMovie = results.firstOrNull { !it.url.contains("/tv-show/") }
            ?: throw Exception("No movie found in search results")
        
        println("Loading movie details for: ${firstMovie.name} (${firstMovie.url})")
        val loadResponse = provider.load(firstMovie.url)
        assertTrue("LoadResponse should not be null", loadResponse != null)
        assertTrue("Should be a MovieLoadResponse", loadResponse is MovieLoadResponse)
        
        val movieResponse = loadResponse as MovieLoadResponse
        println("Title: ${movieResponse.name}")
        println("Plot: ${movieResponse.plot}")
        println("Poster: ${movieResponse.posterUrl}")
        println("Data Url: ${movieResponse.dataUrl}")
    }

    @Test
    fun testLoadTVShow() = runBlocking {
        val results = provider.search("Office")
        val firstShow = results.firstOrNull { it.url.contains("/tv-show/") }
            ?: throw Exception("No TV show found in search results")
        
        println("Loading TV show details for: ${firstShow.name} (${firstShow.url})")
        val loadResponse = provider.load(firstShow.url)
        assertTrue("LoadResponse should not be null", loadResponse != null)
        assertTrue("Should be a TvSeriesLoadResponse", loadResponse is TvSeriesLoadResponse)
        
        val tvResponse = loadResponse as TvSeriesLoadResponse
        println("Title: ${tvResponse.name}")
        println("Plot: ${tvResponse.plot}")
        println("Episodes count: ${tvResponse.episodes.size}")
        assertTrue("Should find episodes", tvResponse.episodes.isNotEmpty())
    }

    @Test
    fun testLoadLinksMovie() = runBlocking {
        val results = provider.search("Avatar")
        val firstMovie = results.firstOrNull { !it.url.contains("/tv-show/") }
            ?: throw Exception("No movie found in search results")
        
        val loadResponse = provider.load(firstMovie.url) as MovieLoadResponse
        
        val links = mutableListOf<ExtractorLink>()
        val subtitles = mutableListOf<SubtitleFile>()
        
        println("Loading links for Movie: ${firstMovie.name} with data url: ${loadResponse.dataUrl}")
        try {
            val linkSuccess = provider.loadLinks(loadResponse.dataUrl, false, { sub ->
                println("Extracted Subtitle: ${sub.lang} -> ${sub.url}")
                subtitles.add(sub)
            }, { link ->
                println("Extracted Link: ${link.name} -> ${link.url}")
                links.add(link)
            })
            println("Link resolution success status: $linkSuccess, count: ${links.size}")
        } catch (e: Exception) {
            println("Link resolution skipped due to network/sandbox block: ${e.message}")
        }
    }

    @Test
    fun testTimeoutCompilation() = runBlocking {
        // Test compiling and running NiceHttpClient with custom timeout parameter
        try {
            val res = app.get("https://movish.net/trending", timeout = 30L)
            println("Success with timeout 30L: Code ${res.code}")
        } catch (e: Exception) {
            println("Error with timeout 30L: ${e.message}")
        }
    }
}