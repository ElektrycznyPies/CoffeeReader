package com.electricdog.coffeereader.reader

import org.jsoup.Jsoup
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.Charset
import android.util.Log
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
    fun get(address: String): HttpText = request(address)

    private fun request(address: String, body: String? = null): HttpText =
        responseStream(address, body) { stream, url, contentType ->
            val bytes = readLimited(stream)
            val declared = declaredEncoding(contentType)
            val charset = runCatching { Charset.forName(declared ?: "UTF-8") }.getOrDefault(Charsets.UTF_8)
            HttpText(bytes.toString(charset), url)
        }

    private fun <T> responseStream(address: String, body: String? = null,
        consume: (InputStream, String, String) -> T): T {
        var current = canonicalUrl(address)
        repeat(6) {
            val connection = URL(current).openConnection() as HttpURLConnection
            var stream: InputStream? = null
            try {
                connection.connectTimeout = 12000
                connection.readTimeout = 15000
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("User-Agent", "CoffeeReader/1.1 (Android RSS reader)")
                connection.setRequestProperty("Accept", "application/rss+xml, application/atom+xml, application/json, text/xml, text/html, text/plain, */*")
                connection.setRequestProperty("Accept-Encoding", "gzip")
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
                    val raw = connection.inputStream
                    stream = raw
                    val decoded = if (connection.contentEncoding.equals("gzip", true)) GZIPInputStream(raw) else raw
                    stream = decoded
                    return consume(decoded, current, connection.contentType.orEmpty())
                }
            } finally {
                // Cancel the HTTP response before closing its stream to avoid draining the rest.
                connection.disconnect()
                runCatching { stream?.close() }
            }
        }
        error("Too many redirects")
    }

    private fun declaredEncoding(contentType: String): String? =
        Regex("charset=[\"']?([^;\\s\"']+)", RegexOption.IGNORE_CASE).find(contentType)?.groupValues?.get(1)

    private fun fetchAddress(address: String): ParsedFeed {
        try {
            return responseStream(address) { stream, url, contentType ->
                StreamingFeedParser.parse(
                    stream,
                    url,
                    declaredEncoding(contentType),
                )
            }
        } catch (error: Exception) {
            Log.e("CoffeeFeed", "Failed to load feed: $address", error)
            throw error
        }
    }
    fun discover(address: String): List<FeedCandidate> {
        val supplied = address.trim().let { if ("://" in it) it else "https://$it" }
        runCatching { fetchAddress(supplied) }.getOrNull()?.let {
            return listOf(FeedCandidate(it.source.url, it.source.name))
        }
        val response = get(supplied)
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
            val parsed = runCatching { fetchAddress(origin.resolve(path).toString()) }.getOrNull()
            if (parsed != null) return listOf(FeedCandidate(parsed.source.url, parsed.source.name))
        }
        return emptyList()
    }

    fun fetch(source: FeedSource): ParsedFeed = fetchAddress(source.url)

    fun parseFeed(response: HttpText): ParsedFeed =
        StreamingFeedParser.parse(response.text.byteInputStream(Charsets.UTF_8), response.url, "UTF-8")

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

}
