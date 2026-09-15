package com.electricdog.coffeereader.reader

import org.junit.Assert.*
import org.junit.Test

class SourceChangesTest {
    private val tags = listOf("Events", "Science", "Arts", "Travel", "Sports")
    private val news = FeedSource("https://example.org/feed?topic=news", "Publisher", setOf(0), 7)
    private val arts = news.copy(url = "https://example.org/feed?topic=arts", tags = setOf(2), limit = 2)
    private fun article(source: FeedSource, path: String) = Article(
        "https://example.org/$path", source.url, source.name, "https://example.org/$path",
        "Saved article", 1000L, "A short description.", savedAt = 2000L,
    )

    @Test fun deletionRemovesOnlyTheSelectedFeedsArticlesAndBookmarks() {
        val state = ReaderState(sources = listOf(news, arts), tagNames = tags,
            articles = listOf(article(news, "one"), article(arts, "two")),
            bookmarks = listOf(article(news, "one"), article(arts, "two")))
        val result = state.withoutSource(news.url)
        assertEquals(listOf(arts), result.sources)
        assertEquals(listOf(article(arts, "two").id), result.articles.map { it.id })
        assertEquals(listOf(article(arts, "two").id), result.bookmarks.map { it.id })
        assertEquals(tags, result.tagNames)
        assertEquals(result, result.withoutSource(news.url))
        assertEquals(2, state.bookmarks.size)
    }

    @Test fun exportPreservesPerSourceLimitsTagsAndBookmarks() {
        val saved = article(news, "one")
        val state = ReaderState(sources = listOf(news, arts), bookmarks = listOf(saved), tagNames = tags)
        val restored = parseImport(exportJson(state))
        assertEquals(state.sources, restored.sources)
        assertEquals(state.bookmarks, restored.bookmarks)
        assertEquals(tags, restored.tagNames)
    }
}
