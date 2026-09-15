package com.electricdog.coffeereader.reader

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class ReaderStorage(context: Context) {
    private val directory = File(context.filesDir, "coffee-reader").apply { mkdirs() }
    private val stateFile = AtomicFile(File(directory, "reader.json"))
    private val bookmarksFile = AtomicFile(File(directory, "bookmarks.json"))

    fun load(defaultTags: List<String>): ReaderState {
        val bookmarks = read(bookmarksFile)?.optJSONArray("bookmarks")?.articles().orEmpty()
        val root = read(stateFile) ?: return ReaderState(tagNames = defaultTags, bookmarks = bookmarks)
        require(root.getInt("schemaVersion") == ReaderConfig.SCHEMA_VERSION)
        val names = root.optJSONArray("tags")?.strings()?.takeIf { it.size == 5 } ?: defaultTags
        return ReaderState(
            sources = root.optJSONArray("sources")?.sources().orEmpty(),
            articles = root.optJSONArray("articles")?.articles().orEmpty(),
            bookmarks = bookmarks,
            tagNames = names,
            displayTags = root.optBoolean("displayTags", true),
            grouped = root.optBoolean("grouped", false),
            days = root.optInt("days", 0).takeIf { it in ReaderConfig.DAY_OPTIONS } ?: 0,
            defaultLimit = root.optInt("defaultLimit", 3).coerceIn(1, 20),
        )
    }

    fun save(state: ReaderState) {
        val root = JSONObject()
            .put("schemaVersion", ReaderConfig.SCHEMA_VERSION)
            .put("sources", JSONArray(state.sources.map { it.toJson() }))
            .put("articles", JSONArray(state.articles.map { it.toJson() }))
            .put("tags", JSONArray(state.tagNames))
            .put("displayTags", state.displayTags)
            .put("grouped", state.grouped)
            .put("days", state.days)
            .put("defaultLimit", state.defaultLimit)
        write(bookmarksFile, JSONObject()
            .put("schemaVersion", ReaderConfig.SCHEMA_VERSION)
            .put("bookmarks", JSONArray(state.bookmarks.map { it.toJson() })))
        write(stateFile, root)
    }

    private fun read(file: AtomicFile): JSONObject? {
        // AtomicFile.openRead also recovers a pending backup after an interrupted write.
        return try { file.openRead().bufferedReader().use { JSONObject(it.readText()) } } catch (_: java.io.FileNotFoundException) { null }
    }

    private fun write(file: AtomicFile, json: JSONObject) {
        val stream = file.startWrite()
        try {
            stream.write(json.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }
}

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

private fun FeedSource.toJson(): JSONObject = JSONObject()
    .put("url", url).put("name", name).put("tags", JSONArray(tags.sorted()))
    .put("limit", limit).put("website", website)

private fun Article.toJson(): JSONObject = JSONObject()
    .put("id", id).put("sourceUrl", sourceUrl).put("sourceName", sourceName)
    .put("url", url).put("title", title).put("publishedAt", publishedAt)
    .put("text", text).put("thumbnail", thumbnail).put("read", read)
    .put("savedAt", savedAt).put("firstSeenAt", firstSeenAt)

private fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }

private fun JSONArray.sources(): List<FeedSource> = (0 until length()).map { index ->
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

private fun JSONArray.articles(): List<Article> = (0 until length()).map { index ->
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
