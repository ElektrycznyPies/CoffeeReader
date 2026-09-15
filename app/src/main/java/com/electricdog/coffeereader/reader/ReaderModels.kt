package com.electricdog.coffeereader.reader

import java.net.URI
import java.text.BreakIterator
import java.util.Locale

object ReaderConfig {
    const val COLLAPSED_TEXT_LIMIT = 180
    const val EXPANDED_TEXT_LIMIT = 900
    const val DEFAULT_SOURCE_LIMIT = 3
    const val SCHEMA_VERSION = 1
    const val MAX_DOWNLOAD_BYTES = 4 * 1024 * 1024
    const val MAX_FEED_BYTES = 6 * 1024 * 1024
    const val MAX_FEED_ENTRIES = 100
    const val MAX_SOURCES = 500
    val DAY_OPTIONS = listOf(0, 1, 3, 5, 7, 14, 30)

    // Set this to the raw GitHub URL of feed-sets/index.json when the repository exists.
    const val FEED_SETS_INDEX_URL = ""
}

data class FeedSource(
    val url: String,
    val name: String,
    val tags: Set<Int> = emptySet(),
    val limit: Int = ReaderConfig.DEFAULT_SOURCE_LIMIT,
    val website: String = "",
)

data class Article(
    val id: String,
    val sourceUrl: String,
    val sourceName: String,
    val url: String,
    val title: String,
    val publishedAt: Long,
    val text: String,
    val thumbnail: String = "",
    val read: Boolean = false,
    val savedAt: Long = 0L,
    val firstSeenAt: Long = System.currentTimeMillis(),
)

data class ReaderState(
    val sources: List<FeedSource> = emptyList(),
    val articles: List<Article> = emptyList(),
    val bookmarks: List<Article> = emptyList(),
    val tagNames: List<String>,
    val displayTags: Boolean = true,
    val grouped: Boolean = false,
    val days: Int = 0,
    val defaultLimit: Int = ReaderConfig.DEFAULT_SOURCE_LIMIT,
)

data class FeedCandidate(val url: String, val name: String)
enum class FeedLimit { NONE, ENTRIES, BYTES }
data class ParsedFeed(val source: FeedSource, val articles: List<Article>,
    val limit: FeedLimit = FeedLimit.NONE, val bytesRead: Long = 0, val entriesRead: Int = articles.size)
data class ImportData(val sources: List<FeedSource>, val bookmarks: List<Article>, val tagNames: List<String>)
data class FeedSet(val name: String, val description: String, val url: String)

// Keep query parameters: distinct topic feeds sometimes differ only by query string.
fun canonicalUrl(value: String): String {
    val uri = URI(value.trim()).normalize()
    require(uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank())
    require(uri.rawUserInfo == null)
    val scheme = uri.scheme.lowercase()
    val host = uri.host.lowercase()
    val port = if (uri.port == -1 || (scheme == "https" && uri.port == 443) ||
        (scheme == "http" && uri.port == 80)) "" else ":${uri.port}"
    val path = uri.rawPath.orEmpty().ifEmpty { "/" }
    val query = uri.rawQuery?.let { "?$it" }.orEmpty()
    return "$scheme://$host$port$path$query"
}

fun safeWebUrl(value: String): String? = runCatching { canonicalUrl(value) }.getOrNull()

// Use Unicode code points so an ellipsis never splits an emoji surrogate pair.
fun excerpt(text: String, limit: Int, firstSentence: Boolean = false): String {
    val normalized = text.replace(Regex("\\s+"), " ").trim()
    if (normalized.isEmpty()) return ""
    val candidate = if (firstSentence) {
        val iterator = BreakIterator.getSentenceInstance(Locale.getDefault())
        iterator.setText(normalized)
        val end = iterator.next()
        if (end == BreakIterator.DONE) normalized else normalized.substring(0, end).trim()
    } else normalized
    if (candidate.codePointCount(0, candidate.length) <= limit) return candidate
    val cut = candidate.offsetByCodePoints(0, (limit - 1).coerceAtLeast(0))
    return candidate.substring(0, cut).trimEnd() + "…"
}

fun filteredArticles(state: ReaderState, tag: Int?, visitStart: Long, now: Long): List<Article> {
    val allowed = state.sources.filter { tag == null || tag in it.tags }.map { it.url }.toSet()
    val cutoff = if (state.days == 0) visitStart else now - state.days * 86_400_000L
    return state.articles.filter {
        it.sourceUrl in allowed && it.publishedAt >= cutoff
    }.sortedByDescending { it.publishedAt }
}

fun limitedArticles(articles: List<Article>, sources: List<FeedSource>, expanded: Set<String>): List<Article> {
    val limits = sources.associate { it.url to it.limit }
    val counts = mutableMapOf<String, Int>()
    return articles.filter { article ->
        val count = counts.getOrDefault(article.sourceUrl, 0) + 1
        counts[article.sourceUrl] = count
        article.sourceUrl in expanded || count <= (limits[article.sourceUrl] ?: ReaderConfig.DEFAULT_SOURCE_LIMIT)
    }
}

// Match by feed URL, not publisher name: separate topic feeds remain independent.
fun ReaderState.withoutSource(url: String): ReaderState = copy(
    sources = sources.filterNot { it.url == url },
    articles = articles.filterNot { it.sourceUrl == url },
    bookmarks = bookmarks.filterNot { it.sourceUrl == url },
)
