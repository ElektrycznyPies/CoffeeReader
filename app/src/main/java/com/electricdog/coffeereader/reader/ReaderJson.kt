package com.electricdog.coffeereader.reader

import org.json.JSONArray
import org.json.JSONObject

fun exportJson(state: ReaderState): String = JSONObject()
    .put("format", "coffee-reader")
    .put("schemaVersion", ReaderConfig.SCHEMA_VERSION)
    .put("tags", JSONArray(state.tagNames))
    .put("sources", JSONArray(state.sources.map { it.toJson() }))
    .put("bookmarks", JSONArray(state.bookmarks.map { it.toJson() }))
    .toString(2)

fun parseImport(text: String): ImportData {
    require(text.toByteArray(Charsets.UTF_8).size <= ReaderConfig.MAX_DOWNLOAD_BYTES)
    val root = JSONObject(text)
    require(root.optString("format") == "coffee-reader")
    require(root.optInt("schemaVersion", -1) == ReaderConfig.SCHEMA_VERSION)
    require(root.has("sources"))
    val names = root.getJSONArray("tags").strings()
    require(names.size == 5 && names.all { it.isNotBlank() && it.length <= 24 })
    val sources = root.getJSONArray("sources").sources().distinctBy { it.url }
    require(sources.size <= ReaderConfig.MAX_SOURCES)
    val bookmarks = root.optJSONArray("bookmarks")?.articles().orEmpty().distinctBy { it.id }
    require(bookmarks.size <= 5000)
    return ImportData(sources, bookmarks, names)
}

internal fun FeedSource.toJson(): JSONObject = JSONObject()
    .put("url", url).put("name", name).put("tags", JSONArray(tags.sorted()))
    .put("limit", limit).put("website", website)

internal fun Article.toJson(): JSONObject = JSONObject()
    .put("id", id).put("sourceUrl", sourceUrl).put("sourceName", sourceName)
    .put("url", url).put("title", title).put("publishedAt", publishedAt)
    .put("text", text).put("thumbnail", thumbnail).put("read", read)
    .put("savedAt", savedAt).put("firstSeenAt", firstSeenAt)

internal fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }

internal fun JSONArray.sources(): List<FeedSource> = (0 until length()).map { index ->
    val item = getJSONObject(index)
    val tags = item.optJSONArray("tags") ?: JSONArray()
    FeedSource(
        url = canonicalUrl(item.getString("url")),
        name = item.getString("name").trim().take(150).also { require(it.isNotEmpty()) },
        tags = (0 until tags.length()).map { tags.getInt(it).also { tag -> require(tag in 0..4) } }.toSet(),
        limit = item.optInt("limit", 3).coerceIn(1, 20),
        website = safeWebUrl(item.optString("website")).orEmpty(),
    )
}

internal fun JSONArray.articles(): List<Article> = (0 until length()).map { index ->
    val item = getJSONObject(index)
    val url = canonicalUrl(item.getString("url"))
    Article(
        id = url,
        sourceUrl = canonicalUrl(item.getString("sourceUrl")),
        sourceName = item.getString("sourceName").take(150),
        url = url,
        title = item.getString("title").take(500),
        publishedAt = item.optLong("publishedAt", 0).coerceAtLeast(0),
        text = item.optString("text").take(12000),
        thumbnail = safeWebUrl(item.optString("thumbnail")).orEmpty(),
        read = item.optBoolean("read", false),
        savedAt = item.optLong("savedAt", 0).coerceAtLeast(0),
        firstSeenAt = item.optLong("firstSeenAt", item.optLong("publishedAt", 0)),
    )
}
