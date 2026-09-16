@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.electricdog.coffeereader.reader

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.electricdog.coffeereader.BuildConfig
import com.electricdog.coffeereader.R
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

private enum class Panel { None, Add, Bookmarks, Export, Import, Tags, Sets, Sources, Help, Diagnostics }
private val TagColors = listOf(0xFFFFDEAD, 0xFFFFC77B, 0xFFF8AE54, 0xFFE99137, 0xFFD97725).map { Color(it) }
private val TagIcons = listOf(R.drawable.ic_cr_events, R.drawable.ic_cr_science,
    R.drawable.ic_cr_arts, R.drawable.ic_cr_travel, R.drawable.ic_cr_sports)

@Composable
fun CoffeeReaderApp(vm: ReaderViewModel) {
    val dark = isSystemInDarkTheme()
    val palette = if (dark) darkColorScheme(primary = Color(0xFFFFBA70),
        background = Color(0xFF191613), surface = Color(0xFF24201B), secondaryContainer = Color(0xFF49321D))
    else lightColorScheme(primary = Color(0xFF8F4D1F), background = Color(0xFFFAF6EF),
        surface = Color(0xFFFFFCF6), secondaryContainer = Color(0xFFFFD9AD))
    MaterialTheme(colorScheme = palette) { ReaderContent(vm) }
}

@Composable
private fun ReaderContent(vm: ReaderViewModel) {
    val state = vm.state
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var panel by rememberSaveable { mutableStateOf(Panel.None) }
    var drawer by rememberSaveable { mutableStateOf(false) }
    var focusedSourceUrl by rememberSaveable { mutableStateOf<String?>(null) }
    val focusedSource = state.sources.firstOrNull { it.url == focusedSourceUrl }
    val flowListState = rememberLazyListState()
    val sourceListState = rememberLazyListState()
    val sourceBackground = Color(0xFFEDF7E8)
    val sourceForeground = Color(0xFF173521)
    LaunchedEffect(vm.ready, focusedSourceUrl, focusedSource?.url) {
        // A removed source must not leave the screen in an empty focus mode.
        if (vm.ready && focusedSourceUrl != null && focusedSource == null) focusedSourceUrl = null
    }
    var editSource by remember { mutableStateOf<FeedSource?>(null) }
    var deleteSource by remember { mutableStateOf<FeedSource?>(null) }
    var swipeHint by remember { mutableStateOf<SwipeHint?>(null) }
    val onSwipeHint: (String, SwipeAction?) -> Unit = { owner, action ->
        if (action != null) swipeHint = SwipeHint(owner, action)
        else if (swipeHint?.owner == owner) swipeHint = null
    }
    LaunchedEffect(panel) { swipeHint = null }
    var tagSource by remember { mutableStateOf<FeedSource?>(null) }
    var importAddress by rememberSaveable { mutableStateOf("") }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.previewFile {
            context.contentResolver.openInputStream(uri)!!.use { readLimited(it).toString(Charsets.UTF_8) }
        }
    }
    val saveFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            val snapshot = state
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri, "wt")!!.bufferedWriter().use { it.write(exportJson(snapshot)) }
                    }
                    vm.notify(R.string.cr_file_saved)
                } catch (error: CancellationException) { throw error } catch (_: Exception) { vm.notify(R.string.cr_save_failed) }
            }
        }
    }
    LaunchedEffect(vm) { vm.events.collect { snackbar.showSnackbar(it) } }
    BackHandler(drawer) { drawer = false }
    BackHandler(focusedSource != null && panel == Panel.None && !drawer &&
            editSource == null && deleteSource == null && tagSource == null) { focusedSourceUrl = null }

    fun openArticle(article: Article) {
        if (openInBrowser(context, article.url)) vm.markRead(article)
        else vm.notify(R.string.cr_no_browser)
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = if (focusedSource != null) sourceBackground else MaterialTheme.colorScheme.background,
            contentColor = if (focusedSource != null) sourceForeground else MaterialTheme.colorScheme.onBackground,
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                Surface(shadowElevation = 2.dp) {
                    Column(Modifier.statusBarsPadding().padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            SquareAction(R.drawable.ic_cr_add, stringResource(R.string.cr_add_source),
                                Modifier.size(80.dp), vm.ready) { vm.candidates = emptyList(); panel = Panel.Add }
                            SquareAction(
                                if (focusedSource != null || !state.grouped) R.drawable.ic_cr_flow else R.drawable.ic_cr_group,
                                stringResource(if (focusedSource != null) R.string.cr_back_to_flow
                                else if (state.grouped) R.string.cr_group_by_source else R.string.cr_flow),
                                Modifier.size(72.dp), vm.ready,
                                containerColor = if (focusedSource != null) Color(0xFF287443) else MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = if (focusedSource != null) Color.White else MaterialTheme.colorScheme.onSecondaryContainer,
                            ) {
                                if (focusedSource != null) focusedSourceUrl = null else vm.setGrouped(!state.grouped)
                            }
                            SwipeLegend(
                                modifier = Modifier.weight(1f),
                                leftLabel = if (focusedSource == null) {
                                    stringResource(R.string.cr_swipe_show_more)
                                } else null,
                                leftColor = SwipeAction.More.color,
                                rightLabel = stringResource(R.string.cr_swipe_add_bookmark),
                                rightColor = SwipeAction.Bookmark.color,
                                compact = true,
                            )
                            IconButton(onClick = { drawer = true }, enabled = vm.ready, modifier = Modifier.size(52.dp)) {
                                CrIcon(R.drawable.ic_cr_menu, stringResource(R.string.cr_menu))
                            }
                        }
                        if (focusedSource != null) {
                            Text(stringResource(R.string.cr_single_source_hint), Modifier.padding(top = 12.dp),
                                style = MaterialTheme.typography.bodySmall)
                            Text(stringResource(R.string.cr_all_downloaded), Modifier.padding(top = 6.dp),
                                style = MaterialTheme.typography.labelLarge)
                        } else {
                            var slider by remember(state.days) {
                                mutableFloatStateOf(ReaderConfig.DAY_OPTIONS.indexOf(state.days).toFloat())
                            }
                            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.cr_time_depth), style = MaterialTheme.typography.labelMedium)
                                Spacer(Modifier.weight(1f))
                                Text(if (slider.roundToInt() == 0) stringResource(R.string.cr_since_last_visit)
                                else if (slider.roundToInt() == 1) stringResource(R.string.cr_one_day)
                                else stringResource(R.string.cr_days, ReaderConfig.DAY_OPTIONS[slider.roundToInt()]),
                                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            }
                            Slider(value = slider, onValueChange = { slider = it }, valueRange = 0f..6f,
                                steps = 5, enabled = vm.ready,
                                onValueChangeFinished = { vm.setDays(ReaderConfig.DAY_OPTIONS[slider.roundToInt()]) },
                                modifier = Modifier.fillMaxWidth().height(34.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                listOf("↶", "1", "3", "5", "7", "14", "30").forEach {
                                    Text(it, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                        if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                    }
                }
            },
            bottomBar = {
                if (state.displayTags && focusedSource == null) Surface(shadowElevation = 4.dp) {
                    Row(Modifier.navigationBarsPadding().padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        state.tagNames.forEachIndexed { index, name ->
                            Surface(onClick = { vm.toggleTag(index) }, enabled = vm.ready,
                                color = TagColors[index], contentColor = Color(0xFF301C0D),
                                border = if (vm.activeTag == index) BorderStroke(2.dp, MaterialTheme.colorScheme.onSurface) else null,
                                shape = RoundedCornerShape(10.dp), modifier = Modifier.weight(1f)) {
                                Column(Modifier.heightIn(min = 60.dp).padding(vertical = 7.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    CrIcon(TagIcons[index], name, Modifier.size(22.dp))
                                    Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 10.sp)
                                }
                            }
                        }
                    }
                }
            },
        ) { insets ->
            Column(Modifier.padding(insets).fillMaxSize()) {
                if (vm.startupError) Text(stringResource(R.string.cr_load_failed), Modifier.padding(24.dp))
                else if (!vm.ready) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                else {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(focusedSource?.name ?: vm.activeTag?.let { state.tagNames[it] } ?: stringResource(R.string.cr_all_sources),
                            style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        if (focusedSource == null && vm.activeTag != null) TextButton(onClick = { vm.activeTag = null }) { Text(stringResource(R.string.cr_all)) }
                        IconButton(onClick = { vm.refresh() }, enabled = !vm.busy && state.sources.isNotEmpty()) {
                            CrIcon(R.drawable.ic_cr_refresh, stringResource(R.string.cr_refresh))
                        }
                    }
                    if (vm.refreshIssues.isNotEmpty()) TextButton(onClick = { panel = Panel.Diagnostics }) {
                        Text(stringResource(R.string.cr_refresh_errors, vm.refreshIssues.size))
                    }
                    // Focus mode shows every locally available article from this RSS URL.
                    // Normal time/tag filters and collapsed groups stay untouched for the return.
                    val rows = if (focusedSource != null) {
                        state.articles.filter { it.sourceUrl == focusedSource.url }
                            .sortedByDescending { it.publishedAt }.map { FeedRow.Item(it) }
                    } else buildRows(filteredArticles(state, vm.activeTag, vm.visitStart, System.currentTimeMillis()), vm)
                    val hiddenCounts = rows.filterIsInstance<FeedRow.More>()
                        .filterNot { it.expanded }
                        .associate { it.source.url to it.count }
                    if (rows.isEmpty()) {
                        Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(stringResource(if (focusedSource != null) R.string.cr_source_no_articles
                            else if (state.sources.isEmpty()) R.string.cr_empty_title else R.string.cr_no_articles),
                                style = MaterialTheme.typography.headlineSmall)
                            Text(stringResource(if (focusedSource != null) R.string.cr_source_empty_hint
                            else if (state.sources.isEmpty()) R.string.cr_empty_hint else R.string.cr_depth_hint),
                                Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodyMedium)
                            if (state.sources.isEmpty()) Button(onClick = { panel = Panel.Add }, Modifier.padding(top = 20.dp)) {
                                Text(stringResource(R.string.cr_add_source))
                            }
                        }
                    } else LazyColumn(Modifier.fillMaxSize(),
                        state = if (focusedSource != null) sourceListState else flowListState,
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(rows, key = { it.key }) { row ->
                            when (row) {
                                is FeedRow.Heading -> Text(row.name, Modifier.padding(top = 10.dp, bottom = 2.dp),
                                    style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                                is FeedRow.More -> TextButton(onClick = {
                                    vm.expandedSources = if (row.expanded) vm.expandedSources - row.source.url else vm.expandedSources + row.source.url
                                }, modifier = Modifier.fillMaxWidth()) {
                                    Text(if (row.expanded) stringResource(R.string.cr_show_fewer, row.source.name)
                                    else stringResource(R.string.cr_show_more, row.count, row.source.name))
                                }
                                is FeedRow.Item -> {
                                    val moreCount = hiddenCounts[row.article.sourceUrl] ?: 0
                                    ArticleCard(
                                        row.article,
                                        saved = state.bookmarks.any { it.id == row.article.id },
                                        onOpen = { openArticle(row.article) },
                                        onRight = { vm.bookmark(row.article) },
                                        onLeft = {
                                            vm.expandedSources = vm.expandedSources + row.article.sourceUrl
                                        },
                                        onLongPress = { vm.bookmark(row.article) },
                                        leftAction = if (moreCount > 0) SwipeAction.More else null,
                                        onHint = { owner, action ->
                                            if (action == SwipeAction.More) {
                                                swipeHint = SwipeHint(owner, action, moreCount)
                                            } else {
                                                onSwipeHint(owner, action)
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        AnimatedVisibility(drawer, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)).clickable { drawer = false })
        }
        AnimatedVisibility(drawer, modifier = Modifier.align(Alignment.CenterEnd),
            enter = slideInHorizontally { it }, exit = slideOutHorizontally { it }) {
            Surface(Modifier.width(280.dp).fillMaxHeight(), shadowElevation = 12.dp) {
                Column(Modifier.systemBarsPadding().padding(16.dp)) {
                    Text(stringResource(R.string.cr_app_name), style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.padding(vertical = 20.dp))
                    listOf(R.string.cr_discover_sets to Panel.Sets, R.string.cr_bookmarks to Panel.Bookmarks,
                        R.string.cr_export_feeds to Panel.Export, R.string.cr_import_feeds to Panel.Import,
                        R.string.cr_tags to Panel.Tags, R.string.cr_all_sources to Panel.Sources,
                        R.string.cr_help_about to Panel.Help).forEach { (label, target) ->
                        TextButton(onClick = {
                            drawer = false; panel = target
                            if (target == Panel.Import || target == Panel.Sets) vm.pendingImport = null
                            if (target == Panel.Sets) vm.loadSets()
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(label), modifier = Modifier.fillMaxWidth())
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Text(stringResource(R.string.cr_swipe_reading_help), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (panel == Panel.None && !drawer) SwipeLabel(swipeHint,
            Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 6.dp))
    }

    if (panel != Panel.None) ReaderDialog(stringResource(when (panel) {
        Panel.Add -> R.string.cr_add_source; Panel.Bookmarks -> R.string.cr_bookmarks
        Panel.Export -> R.string.cr_export_feeds; Panel.Import -> R.string.cr_import_feeds
        Panel.Tags -> R.string.cr_tags; Panel.Sets -> R.string.cr_discover_sets
        Panel.Sources -> R.string.cr_all_sources; Panel.Help -> R.string.cr_help_about
        Panel.Diagnostics -> R.string.cr_diagnostics; else -> R.string.cr_app_name
    }), onClose = { swipeHint = null; panel = Panel.None }, swipeHint = swipeHint) {
        if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        when (panel) {
            Panel.Add -> AddSourcePanel(vm, onAdded = { panel = Panel.None })
            Panel.Sources -> AllSourcesPanel(vm, onHint = onSwipeHint,
                onOpen = { source ->
                    focusedSourceUrl = source.url
                    swipeHint = null
                    panel = Panel.None
                    scope.launch { sourceListState.scrollToItem(0) }
                }, onEdit = { editSource = it }, onDelete = { deleteSource = it })
            Panel.Help -> HelpPanel()
            Panel.Tags -> TagsPanel(vm) { panel = Panel.None }
            Panel.Bookmarks -> {
                val bookmarkRemovedMessage = stringResource(R.string.cr_bookmark_removed)
                val undoLabel = stringResource(R.string.cr_undo)
                var byTime by rememberSaveable { mutableStateOf(true) }
                SwipeLegend(
                    leftLabel = stringResource(R.string.cr_swipe_remove_bookmark),
                    leftColor = SwipeAction.DeleteBookmark.color,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                TextButton(onClick = { byTime = !byTime }) {
                    Text(stringResource(if (byTime) R.string.cr_by_time else R.string.cr_by_name))
                }
                val articles = if (byTime) state.bookmarks.sortedByDescending { it.savedAt }
                else state.bookmarks.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.sourceName })
                if (articles.isEmpty()) Text(stringResource(R.string.cr_no_bookmarks), Modifier.padding(vertical = 24.dp))
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(articles, key = { it.id }) { article ->
                        ArticleCard(article, true, onOpen = { openArticle(article) },
                            onRight = {}, onLongPress = {}, onHint = onSwipeHint,
                            rightAction = null, leftAction = SwipeAction.DeleteBookmark, onLeft = {
                                vm.removeBookmark(article)
                                scope.launch {
                                    val result = snackbar.showSnackbar(
                                        message = bookmarkRemovedMessage,
                                        actionLabel = undoLabel,
                                        duration = SnackbarDuration.Short,
                                    )
                                    if (result == SnackbarResult.ActionPerformed) vm.restoreBookmark(article)
                                }
                            })
                    }
                }
                // Keep undo reachable while the bookmarks dialog is open.
                SnackbarHost(snackbar)
            }
            Panel.Export -> ExportPanel(vm, onSaveFile = { saveFile.launch("coffee-reader.json") })
            Panel.Import -> {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    OutlinedTextField(importAddress, { importAddress = it; vm.pendingImport = null },
                        label = { Text(stringResource(R.string.cr_paste_link)) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        TextButton(onClick = { vm.pendingImport = null; vm.previewUrl(importAddress) }, enabled = !vm.busy && importAddress.isNotBlank()) {
                            Text(stringResource(R.string.cr_preview))
                        }
                        TextButton(onClick = {
                            GmsBarcodeScanning.getClient(context).startScan()
                                .addOnSuccessListener { barcode ->
                                    barcode.rawValue?.let { importAddress = it; vm.previewUrl(it) }
                                }.addOnFailureListener { vm.notify(R.string.cr_scan_failed) }
                        }, enabled = !vm.busy) { Text(stringResource(R.string.cr_scan_qr)) }
                        TextButton(onClick = { filePicker.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }, enabled = !vm.busy) {
                            Text(stringResource(R.string.cr_choose_file))
                        }
                    }
                    ImportPreview(vm) { panel = Panel.None }
                }
                SnackbarHost(snackbar)
            }
            Panel.Sets -> {
                if (ReaderConfig.FEED_SETS_INDEX_URL.isBlank()) Text(stringResource(R.string.cr_sets_not_ready), Modifier.padding(vertical = 20.dp))
                else {
                    if (BuildConfig.DEBUG) TextButton(onClick = { vm.checkSets(); panel = Panel.Diagnostics }, enabled = !vm.busy) {
                        Text(stringResource(R.string.cr_check_sets))
                    }
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        vm.availableSets.forEach { set ->
                            Text(set.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
                            Text(set.description, style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = { vm.pendingImport = null; vm.previewUrl(set.url) }, enabled = !vm.busy) { Text(stringResource(R.string.cr_preview)) }
                        }
                        ImportPreview(vm) { panel = Panel.None }
                    }
                }
                SnackbarHost(snackbar)
            }
            Panel.Diagnostics -> LazyColumn(Modifier.weight(1f)) {
                items((vm.diagnostics.ifEmpty { vm.refreshIssues })) { (name, result) ->
                    Text(name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
                    Text(result, style = MaterialTheme.typography.bodySmall)
                }
            }
            else -> Unit
        }
        if (panel in listOf(Panel.Add, Panel.Export, Panel.Tags)) SnackbarHost(snackbar)
    }
    editSource?.let { source -> SourceEditor(source, vm, onClose = { editSource = null }) }
    deleteSource?.let { source ->
        AlertDialog(onDismissRequest = { deleteSource = null },
            title = { Text(stringResource(R.string.cr_delete_source)) },
            text = { Text(stringResource(R.string.cr_delete_source_hint, source.name)) },
            confirmButton = { TextButton(onClick = { vm.removeSource(source); deleteSource = null }) {
                Text(stringResource(R.string.cr_delete), color = MaterialTheme.colorScheme.error)
            } },
            dismissButton = { TextButton(onClick = { deleteSource = null }) { Text(stringResource(R.string.cr_cancel)) } })
    }
    tagSource?.let { source -> TagPickerDialog(source, vm, onClose = { tagSource = null }) }
}

private sealed class FeedRow(val key: String) {
    class Item(val article: Article) : FeedRow("article:${article.sourceUrl}:${article.id}")
    class Heading(val name: String, url: String) : FeedRow("heading:$url")
    class More(val source: FeedSource, val count: Int, val expanded: Boolean) : FeedRow("more:${source.url}")
}

private fun buildRows(all: List<Article>, vm: ReaderViewModel): List<FeedRow> {
    val visible = limitedArticles(all, vm.state.sources, vm.expandedSources)
    val counts = all.groupingBy { it.sourceUrl }.eachCount()
    val sources = vm.state.sources.associateBy { it.url }
    val last = visible.groupBy { it.sourceUrl }.mapValues { it.value.last().id }
    val rows = mutableListOf<FeedRow>()
    fun append(article: Article) {
        rows += FeedRow.Item(article)
        val source = sources[article.sourceUrl] ?: return
        val count = counts[source.url] ?: 0
        if (last[source.url] == article.id && count > source.limit) {
            rows += FeedRow.More(source, count - source.limit, source.url in vm.expandedSources)
        }
    }
    if (vm.state.grouped) {
        for (source in vm.sortedSources()) {
            val articles = visible.filter { it.sourceUrl == source.url }
            if (articles.isNotEmpty()) {
                rows += FeedRow.Heading(source.name, source.url)
                articles.forEach { append(it) }
            }
        }
    } else visible.forEach { append(it) }
    return rows
}

@Composable
private fun CrIcon(id: Int, description: String?, modifier: Modifier = Modifier) {
    Icon(painterResource(id), contentDescription = description, modifier = modifier.size(26.dp))
}

@Composable
private fun SquareAction(icon: Int, label: String, modifier: Modifier, enabled: Boolean,
                         containerColor: Color = MaterialTheme.colorScheme.secondaryContainer,
                         contentColor: Color = MaterialTheme.colorScheme.onSecondaryContainer, action: () -> Unit) {
    Surface(onClick = action, enabled = enabled, modifier = modifier, shape = RoundedCornerShape(12.dp),
        color = containerColor, contentColor = contentColor) {
        Column(Modifier.padding(5.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            CrIcon(icon, label, Modifier.size(28.dp))
            Text(label, fontSize = 11.sp, maxLines = 2, lineHeight = 13.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

@Composable
private fun ArticleCard(article: Article, saved: Boolean, onOpen: () -> Unit,
                        onRight: () -> Unit, onLeft: () -> Unit, onLongPress: () -> Unit,
                        onHint: (String, SwipeAction?) -> Unit,
                        rightAction: SwipeAction? = SwipeAction.Bookmark, leftAction: SwipeAction? = null) {
    var expanded by rememberSaveable(article.sourceUrl, article.id) { mutableStateOf(false) }
    val foreground = if (article.read) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.48f) else MaterialTheme.colorScheme.onSurface
    val compact = remember(article.text) { excerpt(article.text, ReaderConfig.COLLAPSED_TEXT_LIMIT, true) }
    val full = remember(article.text) { excerpt(article.text, ReaderConfig.EXPANDED_TEXT_LIMIT) }
    var image by remember(article.thumbnail) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(article.thumbnail) {
        image = if (article.thumbnail.isEmpty()) null else withContext(Dispatchers.IO) { ThumbnailCache.load(article.thumbnail) }
    }
    SwipeCard(key = "article:${article.sourceUrl}:${article.id}",
        rightAction = rightAction, leftAction = leftAction, onHint = onHint,
        onRight = onRight, onLeft = onLeft, onClick = onOpen, onLongPress = onLongPress) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(article.sourceName, color = if (article.read) foreground else MaterialTheme.colorScheme.primary,
                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f))
                if (saved) CrIcon(R.drawable.ic_cr_bookmark, stringResource(R.string.cr_saved), Modifier.size(16.dp))
                Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(article.publishedAt)),
                    fontSize = 10.sp, color = foreground, modifier = Modifier.padding(start = 8.dp))
            }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(article.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = foreground)
                    if (!expanded && compact.isNotBlank()) Text(compact, Modifier.padding(top = 7.dp),
                        style = MaterialTheme.typography.bodyMedium, color = foreground)
                }
                image?.let {
                    Image(it.asImageBitmap(), null, Modifier.size(74.dp).clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Crop, alpha = if (article.read) 0.5f else 1f)
                }
            }
            AnimatedVisibility(expanded, enter = expandVertically(), exit = shrinkVertically()) {
                Text(full, Modifier.padding(top = 9.dp), style = MaterialTheme.typography.bodyMedium, color = foreground)
            }
            if (full.isNotBlank() && full != compact) IconButton(onClick = { expanded = !expanded },
                modifier = Modifier.align(Alignment.End).size(44.dp)) {
                CrIcon(if (expanded) R.drawable.ic_cr_collapse else R.drawable.ic_cr_expand,
                    stringResource(if (expanded) R.string.cr_collapse else R.string.cr_expand))
            }
        }
    }
}

@Composable
private fun ReaderDialog(title: String, onClose: () -> Unit, swipeHint: SwipeHint? = null,
                         content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(0.96f).fillMaxHeight(0.92f), shape = RoundedCornerShape(20.dp)) {
            Box {
                Column(Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                        IconButton(onClick = onClose) { CrIcon(R.drawable.ic_cr_close, stringResource(R.string.cr_close)) }
                    }
                    Spacer(Modifier.height(12.dp))
                    content()
                }
                SwipeLabel(swipeHint, Modifier.align(Alignment.TopCenter).padding(top = 6.dp))
            }
        }
    }
}

@Composable
private fun TagChoices(names: List<String>, selected: Set<Int>, onChange: (Set<Int>) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        names.forEachIndexed { index, name ->
            FilterChip(selected = index in selected, onClick = {
                onChange(if (index in selected) selected - index else selected + index)
            }, label = { Text(name) })
        }
    }
}

@Composable
private fun ColumnScope.AddSourcePanel(vm: ReaderViewModel, onAdded: () -> Unit) {
    var address by rememberSaveable { mutableStateOf("") }
    var selectedTags by remember { mutableStateOf<Set<Int>>(emptySet()) }
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        OutlinedTextField(address, { address = it; vm.candidates = emptyList() },
            label = { Text(stringResource(R.string.cr_website_url)) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
        Button(onClick = { vm.discover(address) }, enabled = !vm.busy && address.isNotBlank(), modifier = Modifier.padding(top = 12.dp)) {
            Text(stringResource(R.string.cr_find_feed))
        }
        Text(stringResource(R.string.cr_source_tags), Modifier.padding(top = 16.dp), style = MaterialTheme.typography.labelLarge)
        TagChoices(vm.state.tagNames, selectedTags) { selectedTags = it }
        vm.candidates.forEach { candidate ->
            Surface(Modifier.padding(top = 10.dp).fillMaxWidth(), shape = RoundedCornerShape(10.dp), tonalElevation = 2.dp) {
                Column(Modifier.padding(12.dp)) {
                    Text(candidate.name, style = MaterialTheme.typography.titleSmall)
                    Text(candidate.url, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Button(onClick = { vm.add(candidate, selectedTags, onAdded) }, enabled = !vm.busy) {
                        Text(stringResource(R.string.cr_add_source))
                    }
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.AllSourcesPanel(vm: ReaderViewModel, onHint: (String, SwipeAction?) -> Unit,
                                        onOpen: (FeedSource) -> Unit, onEdit: (FeedSource) -> Unit, onDelete: (FeedSource) -> Unit) {
    Text(stringResource(R.string.cr_tap_source_hint), Modifier.padding(bottom = 6.dp),
        style = MaterialTheme.typography.bodySmall)
    SwipeLegend(
        leftLabel = stringResource(R.string.cr_delete_source),
        leftColor = SwipeAction.DeleteSource.color,
        rightLabel = stringResource(R.string.cr_limits_tags),
        rightColor = SwipeAction.Settings.color,
        modifier = Modifier.padding(bottom = 16.dp),
    )
    val sources = vm.sortedSources()
    if (sources.isEmpty()) Text(stringResource(R.string.cr_no_sources), Modifier.padding(vertical = 24.dp))
    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(sources, key = { it.url }) { source ->
            SwipeCard(key = "source:${source.url}", rightAction = SwipeAction.Settings,
                leftAction = SwipeAction.DeleteSource, onHint = onHint,
                onRight = { onEdit(source) }, onLeft = { onDelete(source) }, onClick = { onOpen(source) }) {
                Column(Modifier.padding(14.dp)) {
                    Text(source.name, style = MaterialTheme.typography.titleMedium)
                    Text(source.url, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                    Text(stringResource(R.string.cr_source_limit, source.limit),
                        color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(top = 10.dp))
                    if (source.tags.isNotEmpty()) Text(source.tags.sorted().joinToString(" · ") { vm.state.tagNames[it] },
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.HelpPanel() {
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        Text(stringResource(R.string.cr_version, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.labelLarge)
        Text(stringResource(R.string.cr_about), Modifier.padding(top = 12.dp))
        listOf(R.string.cr_help_reading to R.string.cr_help_reading_body,
            R.string.cr_help_gestures to R.string.cr_swipe_reading_help,
            R.string.cr_all_sources to R.string.cr_help_sources_body,
            R.string.cr_all_downloaded to R.string.cr_help_single_source,
            R.string.cr_tags to R.string.cr_help_tags_body,
            R.string.cr_bookmarks to R.string.cr_help_bookmarks_body,
            R.string.cr_help_transfer to R.string.cr_help_transfer_body,
            R.string.cr_help_feed_limits to R.string.cr_help_feed_limits_body).forEach { (title, body) ->
            Text(stringResource(title), Modifier.padding(top = 20.dp, bottom = 6.dp),
                style = MaterialTheme.typography.titleMedium)
            Text(stringResource(body), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ColumnScope.TagsPanel(vm: ReaderViewModel, onDone: () -> Unit) {
    var names by remember { mutableStateOf(vm.state.tagNames) }
    var display by remember { mutableStateOf(vm.state.displayTags) }
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        names.forEachIndexed { index, name ->
            Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(color = TagColors[index], shape = RoundedCornerShape(8.dp), modifier = Modifier.size(40.dp)) {
                    Box(contentAlignment = Alignment.Center) { CrIcon(TagIcons[index], null) }
                }
                OutlinedTextField(name, { value -> names = names.toMutableList().also { it[index] = value.take(24) } },
                    modifier = Modifier.padding(start = 10.dp).weight(1f), singleLine = true)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 16.dp)) {
            Checkbox(display, { display = it })
            Text(stringResource(R.string.cr_display_tags))
        }
    }
    Button(onClick = { vm.setTags(names.map { it.trim() }, display); onDone() },
        enabled = names.all { it.isNotBlank() } && names.map { it.trim().lowercase() }.distinct().size == 5,
        modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.cr_save)) }
}

@Composable
private fun TagPickerDialog(source: FeedSource, vm: ReaderViewModel, onClose: () -> Unit) {
    var tags by remember(source.url) { mutableStateOf(source.tags) }
    AlertDialog(onDismissRequest = onClose, title = { Text(source.name) },
        text = {
            Column {
                Text(stringResource(R.string.cr_tags_apply_source))
                vm.state.tagNames.forEachIndexed { index, name ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(index in tags, { tags = if (it) tags + index else tags - index })
                        Text(name)
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = {
            vm.state.sources.firstOrNull { it.url == source.url }?.let { vm.updateSource(it.copy(tags = tags)) }
            onClose()
        }) { Text(stringResource(R.string.cr_save)) } },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.cr_cancel)) } })
}

@Composable
private fun SourceEditor(source: FeedSource, vm: ReaderViewModel, onClose: () -> Unit) {
    var name by remember(source.url) { mutableStateOf(source.name) }
    var tags by remember(source.url) { mutableStateOf(source.tags) }
    var limit by remember(source.url) { mutableFloatStateOf(source.limit.toFloat()) }
    var showTags by rememberSaveable(source.url) { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onClose, title = { Text(stringResource(R.string.cr_limits_tags)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(name, { name = it.take(150) },
                    label = { Text(stringResource(R.string.cr_name)) }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !showTags, onClick = { showTags = false },
                        label = { Text(stringResource(R.string.cr_limits)) })
                    FilterChip(selected = showTags, onClick = { showTags = true },
                        label = { Text(stringResource(R.string.cr_tags)) })
                }
                if (showTags) {
                    Text(stringResource(R.string.cr_tags_apply_source))
                    vm.state.tagNames.forEachIndexed { index, name ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(index in tags, { tags = if (it) tags + index else tags - index })
                            Text(name)
                        }
                    }
                } else {
                    Text(stringResource(R.string.cr_source_limit, limit.roundToInt()))
                    Slider(limit, { limit = it }, valueRange = 1f..20f, steps = 18)
                    Text(stringResource(R.string.cr_source_limit_hint), style = MaterialTheme.typography.bodySmall)
                }
            }
        }, confirmButton = { TextButton(onClick = {
            vm.state.sources.firstOrNull { it.url == source.url }?.let {
                vm.updateSource(it.copy(name = name.trim(), tags = tags, limit = limit.roundToInt()))
            }
            onClose()
        }, enabled = name.isNotBlank()) { Text(stringResource(R.string.cr_save)) } },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.cr_cancel)) } })
}

@Composable
private fun ColumnScope.ExportPanel(vm: ReaderViewModel, onSaveFile: () -> Unit) {
    val context = LocalContext.current
    var qr by remember(vm.exportedUrl) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(vm.exportedUrl) {
        qr = if (vm.exportedUrl.isBlank()) null else withContext(Dispatchers.Default) { qrBitmap(vm.exportedUrl) }
    }
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.cr_export_explanation))
        Button(onClick = { vm.export() }, enabled = !vm.busy && (vm.state.sources.isNotEmpty() || vm.state.bookmarks.isNotEmpty()),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF287443), contentColor = Color.White),
            modifier = Modifier.padding(vertical = 18.dp)) { Text(stringResource(R.string.cr_export)) }
        if (vm.exportedUrl.isNotBlank()) {
            SelectionContainer { Text(vm.exportedUrl, color = MaterialTheme.colorScheme.primary) }
            qr?.let { Image(it.asImageBitmap(), stringResource(R.string.cr_export_qr),
                Modifier.padding(12.dp).size(216.dp).background(Color.White)) }
            Row {
                TextButton(onClick = {
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("Coffee Reader", vm.exportedUrl))
                    vm.notify(R.string.cr_link_copied)
                }) { Text(stringResource(R.string.cr_copy_link)) }
                TextButton(onClick = { shareExport(context, vm.exportedUrl) }) { Text(stringResource(R.string.cr_share)) }
            }
        }
        TextButton(onClick = onSaveFile, enabled = !vm.busy) { Text(stringResource(R.string.cr_save_file)) }
    }
}

@Composable
private fun ImportPreview(vm: ReaderViewModel, onDone: () -> Unit) {
    val data = vm.pendingImport ?: return
    val existing = vm.state.sources.map { it.url }.toSet()
    val saved = vm.state.bookmarks.map { it.id }.toSet()
    val additions = data.sources.filterNot { it.url in existing }
    val usedTags = additions.flatMap { it.tags }.toSet().sorted()
    var mapping by remember(data) {
        mutableStateOf(data.tagNames.map { remote -> vm.state.tagNames.indexOfFirst { it.equals(remote, true) } })
    }
    Column(Modifier.padding(top = 14.dp)) {
        Text(stringResource(R.string.cr_import_preview, additions.size, data.bookmarks.count { it.id !in saved }),
            style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.cr_import_add_only), Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall)
        if (usedTags.any { mapping[it] < 0 } || data.tagNames != vm.state.tagNames) {
            Text(stringResource(R.string.cr_map_tags), Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleSmall)
            usedTags.forEach { index ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(data.tagNames[index], Modifier.weight(1f))
                    TextButton(onClick = {
                        mapping = mapping.toMutableList().also { it[index] = if (it[index] >= 4) -1 else it[index] + 1 }
                    }) { Text(if (mapping[index] < 0) stringResource(R.string.cr_no_tag) else vm.state.tagNames[mapping[index]]) }
                }
            }
        }
        Button(onClick = { vm.applyImport(mapping); if (vm.pendingImport == null) onDone() },
            enabled = !vm.busy, modifier = Modifier.padding(top = 14.dp)) { Text(stringResource(R.string.cr_import)) }
    }
}
