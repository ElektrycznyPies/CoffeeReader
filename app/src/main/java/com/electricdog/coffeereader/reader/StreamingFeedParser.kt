package com.electricdog.coffeereader.reader

import org.jsoup.Jsoup
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException
import org.xml.sax.ext.DefaultHandler2
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.xml.parsers.SAXParserFactory

class NotAFeedException : SAXException("The response is not RSS or Atom")
private class FeedStop(val reason: FeedLimit) : SAXException()
private class FeedByteBudgetReached : IOException("Feed byte budget reached")

// The budget applies to decompressed bytes delivered to the XML parser.
// Do not close the supplied stream here: the caller disconnects HTTP first.
private class FeedBudgetStream(input: InputStream, private val limit: Int) : FilterInputStream(input) {
    var count = 0L
        private set
    override fun read(): Int {
        if (count >= limit) throw FeedByteBudgetReached()
        return super.read().also { if (it >= 0) count++ }
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (count >= limit) throw FeedByteBudgetReached()
        val allowed = minOf(length.toLong(), limit - count).toInt()
        return `in`.read(buffer, offset, allowed).also { if (it > 0) count += it }
    }
    override fun close() = Unit
}

object StreamingFeedParser {
    fun parse(input: InputStream, address: String, encoding: String? = null,
        maxEntries: Int = ReaderConfig.MAX_FEED_ENTRIES, maxBytes: Int = ReaderConfig.MAX_FEED_BYTES): ParsedFeed {
        require(maxEntries > 0 && maxBytes > 0)
        val url = canonicalUrl(address)
        val bounded = FeedBudgetStream(input, maxBytes)
        val handler = FeedHandler(url, maxEntries)
        val factory = SAXParserFactory.newInstance().apply { isNamespaceAware = true }
        val reader = factory.newSAXParser().xmlReader
        for ((name, value) in listOf(
            "http://xml.org/sax/features/external-general-entities" to false,
            "http://xml.org/sax/features/external-parameter-entities" to false,
            "http://apache.org/xml/features/nonvalidating/load-external-dtd" to false,
            "http://apache.org/xml/features/disallow-doctype-decl" to true,
            "http://javax.xml.XMLConstants/feature/secure-processing" to true,
        )) runCatching { reader.setFeature(name, value) }
        reader.contentHandler = handler
        reader.errorHandler = handler
        reader.entityResolver = handler
        runCatching { reader.setProperty("http://xml.org/sax/properties/lexical-handler", handler) }
        var stopped = FeedLimit.NONE
        try {
            reader.parse(InputSource(bounded).apply { systemId = url; this.encoding = encoding })
        } catch (stop: FeedStop) {
            stopped = stop.reason
        } catch (error: Exception) {
            if (generateSequence<Throwable>(error) { it.cause }.any { it is FeedByteBudgetReached }) {
                stopped = FeedLimit.BYTES
            } else throw error
        }
        if (!handler.validFeed) throw NotAFeedException()
        val name = handler.sourceName.ifBlank { URI(url).host }.take(150)
        return ParsedFeed(
            FeedSource(url, name, website = handler.website),
            handler.articles.distinctBy { it.id }.map { it.copy(sourceName = name) },
            stopped, bounded.count, handler.entriesRead,
        )
    }
}

private class Field(val name: String, val depth: Int, val base: String, val markup: Boolean,
    private val limit: Int) {
    private val text = StringBuilder()
    fun append(value: String) {
        val remaining = limit - text.length
        if (remaining > 0) text.append(value, 0, minOf(value.length, remaining))
    }
    fun append(value: CharArray, start: Int, length: Int) {
        val remaining = limit - text.length
        if (remaining > 0) text.append(value, start, minOf(length, remaining))
    }
    fun value(): String = text.toString().trimEnd { Character.isHighSurrogate(it) }
}

private class Entry(val base: String) {
    val fields = mutableMapOf<String, String>()
    var url = ""
    var guid = ""
    var thumbnail = ""
}

private class FeedHandler(private val feedUrl: String, private val maxEntries: Int) : DefaultHandler2() {
    var sourceName = ""
    var website = ""
    var validFeed = false
    var entriesRead = 0
    val articles = mutableListOf<Article>()
    private var depth = 0
    private var root = ""
    private var channelDepth = -1
    private var itemDepth = -1
    private var current: Entry? = null
    private var field: Field? = null
    private val bases = mutableListOf(feedUrl)
    private val now = System.currentTimeMillis()

    override fun startDTD(name: String?, publicId: String?, systemId: String?) {
        throw NotAFeedException()
    }
    override fun resolveEntity(publicId: String?, systemId: String?): InputSource {
        throw SAXException("External entities are not supported")
    }
    override fun error(error: SAXParseException) { throw error }
    override fun fatalError(error: SAXParseException) { throw error }

    override fun startElement(uri: String, localName: String, qName: String, attributes: Attributes) {
        depth++
        if (depth > 128) throw SAXException("XML nesting is too deep")
        val name = localName.ifBlank { qName.substringAfter(':') }.lowercase()
        val parentBase = bases.last()
        val base = resolveWebUrl(parentBase, attributes.getValue("http://www.w3.org/XML/1998/namespace", "base").orEmpty())
            ?: parentBase
        bases += base
        if (depth == 1) {
            root = name
            if (root !in setOf("rss", "feed", "rdf")) throw NotAFeedException()
            if (root == "feed") { channelDepth = 1; validFeed = true }
        }
        if (depth == 2 && name == "channel" && root in setOf("rss", "rdf")) {
            channelDepth = 2; validFeed = true
        }
        val isEntry = (root == "feed" && depth == 2 && name == "entry") ||
            (root == "rss" && depth == 3 && channelDepth == 2 && name == "item") ||
            (root == "rdf" && depth == 2 && name == "item")
        if (isEntry) { current = Entry(base); itemDepth = depth }

        field?.let { capture ->
            if (capture.markup && depth > capture.depth) {
                capture.append("<$name")
                for (i in 0 until minOf(attributes.length, 32)) {
                    val key = attributes.getQName(i)
                    val value = attributes.getValue(i).take(2048).replace("&", "&amp;").replace("\"", "&quot;")
                    capture.append(" $key=\"$value\"")
                }
                capture.append(">")
            }
        }

        val entry = current
        if (entry != null) {
            val imageUrl = attributes.getValue("url").orEmpty()
            val type = attributes.getValue("type").orEmpty()
            val medium = attributes.getValue("medium").orEmpty()
            if (imageUrl.isNotBlank() && (name == "thumbnail" ||
                    (name in setOf("content", "enclosure") && (type.startsWith("image/") || medium == "image")))) {
                if (entry.thumbnail.isEmpty() || name == "thumbnail") entry.thumbnail = resolveWebUrl(base, imageUrl).orEmpty()
            }
            if (depth == itemDepth + 1) {
                if (name == "link" && attributes.getValue("rel").orEmpty() in listOf("", "alternate") &&
                    attributes.getValue("type").orEmpty() in listOf("", "text/html", "application/xhtml+xml")) {
                    entry.url = resolveWebUrl(base, attributes.getValue("href").orEmpty()) ?: entry.url
                    field = Field("link", depth, base, false, 8192)
                } else if (name == "guid" && attributes.getValue("isPermaLink") != "false") {
                    field = Field("guid", depth, base, false, 8192)
                } else if (name in setOf("title", "published", "pubdate", "date", "updated", "description", "summary", "encoded", "content") &&
                    !(name == "content" && imageUrl.isNotBlank())) {
                    val markup = name in setOf("description", "summary", "encoded", "content", "title")
                    field = Field(name, depth, base, markup, if (markup) 65536 else 256)
                }
            }
        } else if (channelDepth > 0 && depth == channelDepth + 1) {
            if (name == "title") field = Field("sourceTitle", depth, base, true, 2048)
            if (name == "link" && attributes.getValue("rel").orEmpty() in listOf("", "alternate")) {
                website = resolveWebUrl(base, attributes.getValue("href").orEmpty()) ?: website
                field = Field("sourceLink", depth, base, false, 8192)
            }
        }
    }

    override fun characters(ch: CharArray, start: Int, length: Int) { field?.append(ch, start, length) }

    override fun endElement(uri: String, localName: String, qName: String) {
        val name = localName.ifBlank { qName.substringAfter(':') }.lowercase()
        field?.let { capture ->
            if (depth == capture.depth) {
                val value = capture.value()
                when (capture.name) {
                    "sourceTitle" -> sourceName = Jsoup.parseBodyFragment(value).text()
                    "sourceLink" -> website = resolveWebUrl(capture.base, value) ?: website
                    "link" -> current?.let { it.url = resolveWebUrl(capture.base, value) ?: it.url }
                    "guid" -> current?.guid = resolveWebUrl(capture.base, value).orEmpty()
                    else -> current?.fields?.set(capture.name, value)
                }
                field = null
            } else if (capture.markup && depth > capture.depth) capture.append("</$name>")
        }
        if (current != null && depth == itemDepth) {
            completeEntry(current!!)
            current = null
            itemDepth = -1
            entriesRead++
            if (entriesRead >= maxEntries) throw FeedStop(FeedLimit.ENTRIES)
        }
        // Stop at the closing root; no need to wait for EOF or consume trailing bytes.
        if (depth == 1 && name == root) throw FeedStop(FeedLimit.NONE)
        bases.removeAt(bases.lastIndex)
        depth--
    }

    private fun completeEntry(entry: Entry) {
        val url = entry.url.ifBlank { entry.guid }.takeIf { it.isNotBlank() } ?: return
        val title = Jsoup.parseBodyFragment(entry.fields["title"].orEmpty()).text()
            .ifBlank { sourceName.ifBlank { URI(feedUrl).host } }.take(500)
        val markup = listOf("encoded", "content", "description", "summary")
            .firstNotNullOfOrNull { entry.fields[it]?.takeIf(String::isNotBlank) }.orEmpty()
        val html = Jsoup.parseBodyFragment(markup, entry.base)
        html.select("script,style").remove()
        val text = html.text().replace(Regex("\\s+"), " ").trim().take(12000)
        val image = entry.thumbnail.ifBlank {
            html.selectFirst("img[src]")?.let { resolveWebUrl(entry.base, it.absUrl("src")) }.orEmpty()
        }
        val date = listOf("published", "pubdate", "date", "updated")
            .firstNotNullOfOrNull { parseFeedDate(entry.fields[it]) } ?: now
        articles += Article(url, feedUrl, sourceName, url, title, date, text, image, firstSeenAt = now)
    }
}

private fun resolveWebUrl(base: String, value: String): String? = if (value.isBlank()) null else
    runCatching { canonicalUrl(URI(base).resolve(value.trim()).toString()) }.getOrNull()

private fun parseFeedDate(value: String?): Long? {
    if (value.isNullOrBlank()) return null
    runCatching { Instant.parse(value.trim()).toEpochMilli() }.getOrNull()?.let { return it }
    runCatching { ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }
        .getOrNull()?.let { return it }
    for (format in listOf("EEE, dd MMM yyyy HH:mm:ss Z", "EEE, dd MMM yyyy HH:mm Z", "dd MMM yyyy HH:mm:ss Z", "yyyy-MM-dd'T'HH:mm:ssZ")) {
        runCatching { SimpleDateFormat(format, Locale.US).parse(value.trim())?.time }.getOrNull()?.let { return it }
    }
    return null
}
