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

