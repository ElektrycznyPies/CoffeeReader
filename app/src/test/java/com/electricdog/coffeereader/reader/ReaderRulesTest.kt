package com.electricdog.coffeereader.reader

import org.junit.Assert.*
import org.junit.Test

class ReaderRulesTest {
    private val tags = listOf("Events", "Science", "Arts", "Travel", "Sports")
    private fun article(source: String, number: Int, time: Long = number.toLong()) = Article(
        id = "https://example.org/$source/$number", sourceUrl = "https://example.org/$source/rss",
        sourceName = source, url = "https://example.org/$source/$number", title = "Article $number",
        publishedAt = time, text = "First sentence. Another sentence with more detail.",
    )

    @Test fun busySourceDoesNotHideNicheSource() {
        val news = (1..25).map { article("daily", it) } + article("niche", 1)
        val sources = listOf(FeedSource("https://example.org/daily/rss", "Daily"),
            FeedSource("https://example.org/niche/rss", "Niche"))
        val shown = limitedArticles(news.sortedByDescending { it.publishedAt }, sources, emptySet())
        assertEquals(4, shown.size)
        assertEquals(1, shown.count { it.sourceName == "niche" })
        assertEquals(26, news.size)
    }

    @Test fun expandingOneSourceKeepsOtherLimits() {
        val news = (1..10).map { article("a", it) } + (1..10).map { article("b", it) }
        val sources = listOf(FeedSource("https://example.org/a/rss", "A"), FeedSource("https://example.org/b/rss", "B"))
        val shown = limitedArticles(news, sources, setOf(sources.first().url))
        assertEquals(10, shown.count { it.sourceName == "a" })
        assertEquals(3, shown.count { it.sourceName == "b" })
    }

    @Test fun readItemsRemainInTheFlow() {
        val item = article("a", 1, 1000L).copy(read = true)
        val state = ReaderState(sources = listOf(FeedSource(item.sourceUrl, "A", setOf(1, 2))),
            articles = listOf(item), tagNames = tags)
        assertEquals(listOf(item), filteredArticles(state, 2, 500L, 2000L))
        assertTrue(filteredArticles(state, 0, 500L, 2000L).isEmpty())
    }

    @Test fun sinceLastVisitAndExplicitDayRangeAreIndependent() {
        val now = 40L * 86_400_000L
        val old = article("a", 1, now - 2L * 86_400_000L)
        val recent = article("a", 2, now - 1000L)
        val state = ReaderState(sources = listOf(FeedSource(old.sourceUrl, "A")),
            articles = listOf(old, recent), tagNames = tags)
        assertEquals(listOf(recent), filteredArticles(state, null, now - 2000L, now))
        assertEquals(listOf(recent, old), filteredArticles(state.copy(days = 3), null, now - 2000L, now))
    }

    @Test fun queryParametersKeepTopicFeedsDistinct() {
        assertNotEquals(canonicalUrl("https://example.org/feed?topic=arts"), canonicalUrl("https://example.org/feed?topic=sport"))
        assertEquals("https://example.org/feed?q=a%20b", canonicalUrl("HTTPS://EXAMPLE.ORG:443/feed?q=a%20b#top"))
        assertNull(safeWebUrl("javascript:alert(1)"))
        assertNull(safeWebUrl("file:///tmp/news"))
    }

    @Test fun excerptsRespectUnicodeAndSentenceBoundaries() {
        assertEquals("First sentence.", excerpt("First sentence. Another sentence.", 180, true))
        val clipped = excerpt("😀".repeat(300), 180)
        assertEquals(180, clipped.codePointCount(0, clipped.length))
        assertTrue(clipped.endsWith("…"))
        assertFalse(clipped.contains('\uFFFD'))
    }

    @Test fun rssCdataMediaAndDateAreParsed() {
        val feed = FeedNetwork.parseFeed(HttpText("""
            <rss version="2.0" xmlns:media="http://search.yahoo.com/mrss/">
              <channel><title>Daily</title><link>https://example.org</link>
                <item><title>A headline</title><link>https://example.org/story</link>
                  <pubDate>Tue, 15 Sep 2026 12:00:00 +0000</pubDate>
                  <description><![CDATA[<p>First sentence.</p><p>Second sentence.</p>]]></description>
                  <media:thumbnail url="https://example.org/image.jpg" />
                </item>
              </channel>
            </rss>
        """.trimIndent(), "https://example.org/rss"))
        assertEquals("Daily", feed.source.name)
        assertEquals(1, feed.articles.size)
        assertEquals("First sentence. Second sentence.", feed.articles.single().text)
        assertEquals("https://example.org/image.jpg", feed.articles.single().thumbnail)
        assertEquals(1789473600000L, feed.articles.single().publishedAt)
    }

    @Test fun atomAlternateLinkAndHtmlSummaryAreParsed() {
        val feed = FeedNetwork.parseFeed(HttpText("""
            <feed xmlns="http://www.w3.org/2005/Atom"><title>Notebook</title>
              <entry><title>Essay</title><link rel="self" href="/api/entry/1" />
                <link rel="alternate" href="/essay" /><published>2026-09-15T12:00:00Z</published>
                <summary type="html">&lt;p&gt;A short introduction.&lt;/p&gt;</summary>
              </entry>
            </feed>
        """.trimIndent(), "https://example.org/atom.xml"))
        assertEquals("https://example.org/essay", feed.articles.single().url)
        assertEquals("A short introduction.", feed.articles.single().text)
    }

    @Test fun htmlErrorPageIsNotAnEmptyValidFeed() {
        val failure = runCatching { FeedNetwork.parseFeed(HttpText("<html><body>Service unavailable</body></html>", "https://example.org/feed")) }
        assertTrue(failure.isFailure)
    }
}
