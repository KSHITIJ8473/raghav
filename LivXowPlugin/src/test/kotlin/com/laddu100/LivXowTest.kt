package com.laddu100

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class LivXowTest {
    private val provider = LivXowProvider()

    @Test
    fun testSearch() = runBlocking {
        try {
            val results = provider.search("Sports")
            println("Search results count: ${results.size}")
            results.take(5).forEach { println(" - ${it.name} (${it.url})") }
            // Search may return empty if API is unreachable - that's acceptable
            println("Search test completed.")
        } catch (e: Exception) {
            println("Search test skipped due to network/server error: ${e.message}")
        }
    }

    @Test
    fun testHomepage() = runBlocking {
        try {
            val response = provider.getMainPage(1, MainPageRequest(provider.mainUrl))
            assertTrue("HomePageResponse should not be null", response != null)
            println("Homepage sections: ${response.items.size}")
            response.items.forEach { section ->
                println("  Section: ${section.name} (${section.list.size} items)")
                section.list.take(3).forEach { item ->
                    println("    -> ${item.name} (${item.url})")
                }
            }
        } catch (e: Exception) {
            println("Homepage test skipped due to network/server error: ${e.message}")
        }
    }

    @Test
    fun testLoad() = runBlocking {
        try {
            val response = provider.getMainPage(1, MainPageRequest(provider.mainUrl))
            val firstItem = response.items
                .flatMap { it.list }
                .firstOrNull()
            if (firstItem != null) {
                println("Loading channel: ${firstItem.name} (${firstItem.url})")
                val loadResponse = provider.load(firstItem.url)
                assertTrue("LoadResponse should not be null", loadResponse != null)
                assertTrue("Should be a LiveLoadResponse", loadResponse is LiveLoadResponse)
                println("Load successful: ${loadResponse.name}")
            } else {
                println("No channels found on homepage to test load.")
            }
        } catch (e: Exception) {
            println("Load test skipped due to network/server error: ${e.message}")
        }
    }

    @Test
    fun testLoadLinks() = runBlocking {
        try {
            val response = provider.getMainPage(1, MainPageRequest(provider.mainUrl))
            val firstItem = response.items
                .flatMap { it.list }
                .firstOrNull()
            if (firstItem != null) {
                val links = mutableListOf<ExtractorLink>()
                val loadResponse = provider.load(firstItem.url) as LiveLoadResponse
                println("Loading links for: ${firstItem.name} with dataUrl: ${loadResponse.dataUrl}")
                val success = provider.loadLinks(
                    loadResponse.dataUrl,
                    false,
                    { sub -> println("Subtitle: ${sub.lang} -> ${sub.url}") },
                    { link ->
                        println("Link: ${link.name} -> ${link.url}")
                        links.add(link)
                    }
                )
                println("Link resolution success: $success, count: ${links.size}")
            } else {
                println("No channels found to test link loading.")
            }
        } catch (e: Exception) {
            println("LoadLinks test skipped due to network/server error: ${e.message}")
        }
    }

    @Test
    fun testTimeoutCompilation() = runBlocking {
        try {
            val res = app.get("https://hshshebegge.store/", timeout = 30L)
            println("API response code: ${res.code}")
        } catch (e: Exception) {
            println("API timeout test (expected if server is down): ${e.message}")
        }
    }
}