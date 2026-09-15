package com.electricdog.coffeereader.reader

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

data class HttpText(val text: String, val url: String)

class HttpFailure(val code: Int) : Exception("HTTP $code")

fun readLimited(stream: InputStream, maxBytes: Int = ReaderConfig.MAX_DOWNLOAD_BYTES): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    var count = stream.read(buffer)
    while (count != -1) {
        require(out.size() + count <= maxBytes)
        out.write(buffer, 0, count)
        count = stream.read(buffer)
    }
    return out.toByteArray()
}

object FeedNetwork {
    // Full-content RSS feeds can contain hundreds of entries and exceed 4 MiB.
    private const val MAX_FEED_DOWNLOAD_BYTES = 16 * 1024 * 1024

    fun get(address: String, maxBytes: Int = ReaderConfig.MAX_DOWNLOAD_BYTES): HttpText =
        request(address, maxBytes = maxBytes)

    private fun request(address: String, body: String? = null,
                        maxBytes: Int = ReaderConfig.MAX_DOWNLOAD_BYTES): HttpText {
        var current = canonicalUrl(address)
        repeat(6) {
            val connection = URL(current).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 12000
                connection.readTimeout = 15000
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("User-Agent", "CoffeeReader/0.1 (Android RSS reader)")
                connection.setRequestProperty("Accept", "application/rss+xml, application/atom+xml, application/json, text/xml, text/html, text/plain, */*")
                if (body != null) {
                    connection.requestMethod = "POST"
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                    connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
                val status = connection.responseCode
                if (status in listOf(301, 302, 303, 307, 308) && body == null) {
                    current = canonicalUrl(URI(current).resolve(connection.getHeaderField("Location")).toString())
                } else {
                    if (status !in 200..299) throw HttpFailure(status)
                    require(status != 206)
                    val bytes = connection.inputStream.use { readLimited(it, maxBytes) }
                    val declared = Regex("charset=[\"']?([^;\\s\"']+)", RegexOption.IGNORE_CASE)
                        .find(connection.contentType.orEmpty())?.groupValues?.get(1)
                    val xmlEncoding = Regex("encoding=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
                        .find(bytes.take(200).toByteArray().toString(Charsets.ISO_8859_1))?.groupValues?.get(1)
                    val charset = runCatching { Charset.forName(declared ?: xmlEncoding ?: "UTF-8") }
                        .getOrDefault(Charsets.UTF_8)
                    return HttpText(bytes.toString(charset), current)
                }
            } finally { connection.disconnect() }
        }
        error("Too many redirects")
    }

    fun discover(address: String): List<FeedCandidate> {
        val supplied = address.trim().let { if ("://" in it) it else "https://$it" }
        val response = get(supplied, MAX_FEED_DOWNLOAD_BYTES)
        runCatching { parseFeed(response) }.getOrNull()?.let {
            return listOf(FeedCandidate(it.source.url, it.source.name))
        }
        val page = Jsoup.parse(response.text, response.url)
        val advertised = page.select("link[href]").filter {
            it.attr("type").lowercase().let { type -> "rss" in type || "atom" in type }
        }.mapNotNull { element ->
            safeWebUrl(element.absUrl("href"))?.let { FeedCandidate(it, element.attr("title").ifBlank { it }) }
        }.distinctBy { it.url }
        if (advertised.isNotEmpty()) return advertised.take(20)
        // Fall back to a small, bounded set of common feed paths.
        val origin = URI(response.url).resolve("/")
        val candidates = listOf("feed/", "rss", "feed", "rss.xml", "atom.xml")
        for (path in candidates) {
            val parsed = runCatching { parseFeed(get(origin.resolve(path).toString(), MAX_FEED_DOWNLOAD_BYTES)) }.getOrNull()
            if (parsed != null) return listOf(FeedCandidate(parsed.source.url, parsed.source.name))
        }
        return emptyList()
    }

    fun fetch(source: FeedSource): ParsedFeed = parseFeed(get(source.url, MAX_FEED_DOWNLOAD_BYTES))

    fun parseFeed(response: HttpText): ParsedFeed {
        val doc = Jsoup.parse(response.text, response.url, Parser.xmlParser())
        val root = doc.children().firstOrNull() ?: error("Empty feed")
        val rootName = root.tagName().substringAfter(':').lowercase()
        require(rootName in setOf("rss", "feed", "rdf"))
        val channel = if (rootName == "rss") root.child("channel") ?: error("Missing channel")
        else if (rootName == "rdf") root.child("channel") ?: root else root
        val sourceName = channel.child("title")?.text()?.trim().orEmpty().ifBlank { URI(response.url).host }
        val website = channel.children().firstOrNull {
            it.localName() == "link" && it.attr("rel") in listOf("", "alternate")
        }?.let { resolveUrl(response.url, it.attr("href").ifBlank { it.text() }) }.orEmpty()
        val nodes = if (rootName == "feed") root.children().filter { it.localName() == "entry" }
        else (if (rootName == "rdf") root else channel).children().filter { it.localName() == "item" }
        val now = System.currentTimeMillis()
        val articles = nodes.take(1000).mapNotNull { item ->
            val link = item.children().firstOrNull {
                it.localName() == "link" && it.attr("rel") in listOf("", "alternate") &&
                        it.attr("type") in listOf("", "text/html", "application/xhtml+xml")
            }
            val permalink = link?.attr("href")?.ifBlank { link.text() }.orEmpty()
                .ifBlank { item.child("guid")?.takeIf { it.attr("isPermaLink") != "false" }?.text().orEmpty() }
            val url = resolveUrl(response.url, permalink) ?: return@mapNotNull null
            val title = Jsoup.parseBodyFragment(item.child("title")?.text().orEmpty()).text().ifBlank { sourceName }
            val content = item.children().firstOrNull { it.tagName().equals("content:encoded", true) }
                ?: item.children().firstOrNull { it.localName() == "content" && !it.hasAttr("url") }
                ?: item.child("description") ?: item.child("summary")
            val markup = content?.let { if (it.children().isNotEmpty()) it.html() else it.text() }.orEmpty()
            val parsedText = Jsoup.parseBodyFragment(markup, url)
            parsedText.select("script,style").remove()
            val text = parsedText.text().replace(Regex("\\s+"), " ").trim().take(12000)
            val media = item.getAllElements().firstOrNull {
                it.localName() == "thumbnail" && it.hasAttr("url")
            } ?: item.getAllElements().firstOrNull {
                it.localName() in setOf("content", "enclosure") && it.hasAttr("url") &&
                        (it.attr("type").startsWith("image/") || it.attr("medium") == "image")
            }
            val image = resolveUrl(url, media?.attr("url").orEmpty())
                ?: parsedText.selectFirst("img[src]")?.let { resolveUrl(url, it.absUrl("src")) }
            val dateText = listOf("published", "pubdate", "date", "updated")
                .firstNotNullOfOrNull { item.child(it)?.text()?.takeIf { value -> value.isNotBlank() } }
            val date = parseDate(dateText) ?: now
            Article(url, response.url, sourceName.take(150), url, title.take(500), date,
                text, image.orEmpty(), firstSeenAt = now)
        }.distinctBy { it.id }
        return ParsedFeed(FeedSource(response.url, sourceName.take(150), website = website), articles)
    }

    fun export(state: ReaderState): String {
        val body = "content=" + URLEncoder.encode(exportJson(state), "UTF-8") + "&expiry_days=1"
        val url = request("https://dpaste.com/api/v2/", body).text.trim().trim('"')
        require(URI(url).host == "dpaste.com" && URI(url).scheme == "https")
        return canonicalUrl(url)
    }

    fun importUrl(address: String): ImportData {
        var url = canonicalUrl(address)
        if (URI(url).host == "dpaste.com") {
            val path = URI(url).path.trimEnd('/')
            require(Regex("/[A-Za-z0-9]+(?:\\.txt)?").matches(path))
            url = "https://dpaste.com" + path + if (path.endsWith(".txt")) "" else ".txt"
        }
        return parseImport(get(url).text)
    }

    fun feedSets(): List<FeedSet> {
        require(ReaderConfig.FEED_SETS_INDEX_URL.isNotBlank())
        val index = get(ReaderConfig.FEED_SETS_INDEX_URL)
        val root = JSONObject(index.text)
        require(root.getInt("schemaVersion") == 1)
        val sets = root.getJSONArray("sets")
        return (0 until sets.length()).map { i ->
            val item = sets.getJSONObject(i)
            FeedSet(item.getString("name"), item.optString("description"),
                canonicalUrl(URI(index.url).resolve(item.getString("url")).toString()))
        }
    }

    private fun Element.localName() = tagName().substringAfter(':').lowercase()
    private fun Element.child(name: String): Element? = children().firstOrNull { it.localName() == name }
    private fun resolveUrl(base: String, value: String): String? = if (value.isBlank()) null else
        runCatching { canonicalUrl(URI(base).resolve(value.trim()).toString()) }.getOrNull()

    private fun parseDate(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()?.let { return it }
        runCatching { ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }
            .getOrNull()?.let { return it }
        for (format in listOf("EEE, dd MMM yyyy HH:mm:ss Z", "EEE, dd MMM yyyy HH:mm Z", "dd MMM yyyy HH:mm:ss Z", "yyyy-MM-dd'T'HH:mm:ssZ")) {
            runCatching { SimpleDateFormat(format, Locale.US).parse(value)?.time }.getOrNull()?.let { return it }
        }
        return null
    }
}
