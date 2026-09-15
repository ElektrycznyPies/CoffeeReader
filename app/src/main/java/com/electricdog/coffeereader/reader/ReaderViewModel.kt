package com.electricdog.coffeereader.reader

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.electricdog.coffeereader.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Collator

class ReaderViewModel(application: Application) : AndroidViewModel(application) {
    private val defaults = listOf(R.string.cr_tag_events, R.string.cr_tag_science,
        R.string.cr_tag_arts, R.string.cr_tag_travel, R.string.cr_tag_sports).map { application.getString(it) }
    private val storage = ReaderStorage(application)
    private val preferences = application.getSharedPreferences("coffee-reader-visits", 0)
    val visitStart: Long = preferences.getLong("lastVisit", System.currentTimeMillis() - 86_400_000L)
    var state by mutableStateOf(ReaderState(tagNames = defaults))
        private set
    var ready by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    var startupError by mutableStateOf(false)
        private set
    var activeTag by mutableStateOf<Int?>(null)
    var candidates by mutableStateOf<List<FeedCandidate>>(emptyList())
    var pendingImport by mutableStateOf<ImportData?>(null)
    var exportedUrl by mutableStateOf("")
    var availableSets by mutableStateOf<List<FeedSet>>(emptyList())
    var diagnostics by mutableStateOf<List<Pair<String, String>>>(emptyList())
    var refreshIssues by mutableStateOf<List<Pair<String, String>>>(emptyList())
    var expandedSources by mutableStateOf<Set<String>>(emptySet())
    private val messages = Channel<String>(Channel.BUFFERED)
    val events = messages.receiveAsFlow()
    private val writes = Channel<ReaderState>(Channel.CONFLATED)

    init {
        viewModelScope.launch {
            for (snapshot in writes) {
                try { withContext(Dispatchers.IO) { storage.save(snapshot) } } catch (error: CancellationException) { throw error } catch (_: Exception) { notify(R.string.cr_save_failed) }
            }
        }
        viewModelScope.launch {
            try {
                state = withContext(Dispatchers.IO) { storage.load(defaults) }
                ready = true
                if (state.sources.isNotEmpty()) refresh()
            } catch (error: CancellationException) { throw error } catch (_: Exception) { startupError = true }
        }
    }

    fun recordVisit() {
        if (ready) preferences.edit().putLong("lastVisit", System.currentTimeMillis()).apply()
    }

    fun notify(resource: Int, vararg args: Any) {
        messages.trySend(getApplication<Application>().getString(resource, *args))
    }

    private fun commit(next: ReaderState) {
        if (!ready) return
        state = next
        writes.trySend(next)
    }

    private fun task(block: suspend () -> Unit) {
        if (!ready || busy) return
        busy = true
        viewModelScope.launch {
            try { block() } catch (error: CancellationException) { throw error } catch (error: HttpFailure) { notify(R.string.cr_http_error, error.code) } catch (_: Exception) { notify(R.string.cr_operation_failed) } finally { busy = false }
        }
    }

    fun discover(address: String) = task {
        candidates = emptyList()
        candidates = withContext(Dispatchers.IO) { FeedNetwork.discover(address) }
        if (candidates.isEmpty()) notify(R.string.cr_no_feed)
    }

    fun add(candidate: FeedCandidate, tags: Set<Int>, done: () -> Unit) = task {
        if (state.sources.size >= ReaderConfig.MAX_SOURCES) {
            notify(R.string.cr_source_cap)
            return@task
        }
        val feed = withContext(Dispatchers.IO) { FeedNetwork.fetch(FeedSource(candidate.url, candidate.name)) }
        if (state.sources.any { it.url == feed.source.url || it.url == candidate.url }) {
            notify(R.string.cr_source_exists)
            return@task
        }
        val source = feed.source.copy(tags = tags, limit = state.defaultLimit)
        commit(state.copy(sources = state.sources + source,
            articles = mergeArticles(state.articles, feed.articles)))
        candidates = emptyList()
        done()
    }

    fun refresh() = task {
        val issues = mutableListOf<Pair<String, String>>()
        for (source in state.sources.toList()) {
            try {
                val feed = withContext(Dispatchers.IO) { FeedNetwork.fetch(source) }
                if (state.sources.none { it.url == source.url }) continue
                val articles = feed.articles.map { it.copy(sourceUrl = source.url, sourceName = source.name) }
                commit(state.copy(articles = mergeArticles(state.articles, articles)))
            } catch (error: CancellationException) { throw error } catch (error: Exception) {
                val description = if (error is HttpFailure) "HTTP ${error.code}"
                    else getApplication<Application>().getString(R.string.cr_temporarily_unavailable)
                issues += source.name to description
            }
        }
        refreshIssues = issues
        notify(if (issues.isEmpty()) R.string.cr_refresh_done else R.string.cr_refresh_partial)
    }

    private fun mergeArticles(old: List<Article>, incoming: List<Article>): List<Article> {
        val byKey = old.associateBy { it.sourceUrl to it.id }.toMutableMap()
        for (article in incoming) {
            val key = article.sourceUrl to article.id
            val prior = byKey[key]
            byKey[key] = article.copy(read = prior?.read ?: false,
                firstSeenAt = prior?.firstSeenAt ?: article.firstSeenAt,
                // Keep a stable date for feeds with no publication timestamp.
                publishedAt = if (prior != null && article.publishedAt == article.firstSeenAt)
                    prior.publishedAt else article.publishedAt)
        }
        val oldest = System.currentTimeMillis() - 30L * 86_400_000L
        return byKey.values.filter { maxOf(it.publishedAt, it.firstSeenAt) >= oldest }
            .sortedByDescending { it.publishedAt }
    }

    fun markRead(article: Article) = commit(state.copy(
        articles = state.articles.map { if (it.id == article.id) it.copy(read = true) else it },
        bookmarks = state.bookmarks.map { if (it.id == article.id) it.copy(read = true) else it },
    ))

    fun bookmark(article: Article) {
        if (state.bookmarks.any { it.id == article.id }) { notify(R.string.cr_already_saved); return }
        commit(state.copy(bookmarks = state.bookmarks + article.copy(savedAt = System.currentTimeMillis())))
        notify(R.string.cr_bookmark_added)
    }

    fun removeBookmark(article: Article) = commit(state.copy(bookmarks = state.bookmarks.filterNot { it.id == article.id }))
    fun restoreBookmark(article: Article) {
        if (state.bookmarks.none { it.id == article.id }) commit(state.copy(bookmarks = state.bookmarks + article))
    }
    fun setGrouped(value: Boolean) { expandedSources = emptySet(); commit(state.copy(grouped = value)) }
    fun setDays(value: Int) { expandedSources = emptySet(); commit(state.copy(days = value)) }
    fun toggleTag(value: Int) { activeTag = if (activeTag == value) null else value; expandedSources = emptySet() }
    fun setTags(names: List<String>, display: Boolean) {
        if (!display) activeTag = null
        commit(state.copy(tagNames = names, displayTags = display))
    }
    fun updateSource(source: FeedSource) {
        commit(state.copy(sources = state.sources.map { if (it.url == source.url) source else it },
            articles = state.articles.map { if (it.sourceUrl == source.url) it.copy(sourceName = source.name) else it }))
    }
    fun removeSource(source: FeedSource) = commit(state.copy(
        sources = state.sources.filterNot { it.url == source.url },
        articles = state.articles.filterNot { it.sourceUrl == source.url },
    ))
    fun setDefaultLimit(limit: Int, applyToExisting: Boolean) = commit(state.copy(defaultLimit = limit,
        sources = if (applyToExisting) state.sources.map { it.copy(limit = limit) } else state.sources))

    fun export() = task {
        exportedUrl = withContext(Dispatchers.IO) { FeedNetwork.export(state) }
    }
    fun previewUrl(url: String) = task {
        pendingImport = null
        pendingImport = withContext(Dispatchers.IO) { FeedNetwork.importUrl(url) }
    }
    fun previewFile(load: () -> String) = task {
        pendingImport = null
        pendingImport = withContext(Dispatchers.IO) { parseImport(load()) }
    }

    fun applyImport(mapping: List<Int>) {
        val data = pendingImport ?: return
        val current = state.sources.map { it.url }.toSet()
        val additions = data.sources.filterNot { it.url in current }.map { source ->
            source.copy(tags = source.tags.mapNotNull { mapping.getOrNull(it)?.takeIf { tag -> tag in 0..4 } }.toSet())
        }
        if (state.sources.size + additions.size > ReaderConfig.MAX_SOURCES) { notify(R.string.cr_source_cap); return }
        val saved = state.bookmarks.map { it.id }.toSet()
        val bookmarks = data.bookmarks.filterNot { it.id in saved }
        commit(state.copy(sources = state.sources + additions, bookmarks = state.bookmarks + bookmarks))
        pendingImport = null
        notify(R.string.cr_import_done, additions.size, bookmarks.size)
        if (additions.isNotEmpty()) refresh()
    }

    fun loadSets() = task {
        if (ReaderConfig.FEED_SETS_INDEX_URL.isBlank()) return@task
        availableSets = withContext(Dispatchers.IO) { FeedNetwork.feedSets() }
    }

    fun checkSets() = task {
        diagnostics = emptyList()
        val sets = withContext(Dispatchers.IO) { FeedNetwork.feedSets() }
        val sources = mutableListOf<FeedSource>()
        for (set in sets) sources += withContext(Dispatchers.IO) { FeedNetwork.importUrl(set.url).sources }
        for (source in sources.distinctBy { it.url }) {
            val result = try {
                val feed = withContext(Dispatchers.IO) { FeedNetwork.fetch(source) }
                when {
                    feed.source.url != source.url -> getApplication<Application>().getString(R.string.cr_redirected, feed.source.url)
                    feed.articles.isEmpty() -> getApplication<Application>().getString(R.string.cr_empty_feed)
                    feed.articles.maxOf { it.publishedAt } < System.currentTimeMillis() - 30L * 86_400_000L ->
                        getApplication<Application>().getString(R.string.cr_stale_feed)
                    else -> getApplication<Application>().getString(R.string.cr_feed_ok)
                }
            } catch (error: CancellationException) { throw error } catch (error: Exception) {
                if (error is HttpFailure) "HTTP ${error.code}" else getApplication<Application>().getString(R.string.cr_check_failed)
            }
            diagnostics = diagnostics + (source.name to result)
        }
    }

    fun sortedSources(): List<FeedSource> {
        val collator = Collator.getInstance()
        return state.sources.sortedWith { a, b -> collator.compare(a.name, b.name) }
    }
}
