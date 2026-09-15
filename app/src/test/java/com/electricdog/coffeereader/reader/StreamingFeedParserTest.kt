package com.electricdog.coffeereader.reader

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.io.ByteArrayOutputStream

class StreamingFeedParserTest {
    private val address = "https://example.org/feed"
    private val header = "<rss><channel><title>Small press</title>"
    private fun item(number: Int) = "<item><title>Story $number</title><link>https://example.org/$number</link></item>"
    private fun parse(text: String) = StreamingFeedParser.parse(text.byteInputStream(), address)

    private class CountingStream(input: InputStream) : FilterInputStream(input) {
        var bytes = 0L
        var closed = false
        override fun read(): Int = `in`.read().also { if (it >= 0) bytes++ }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            `in`.read(buffer, offset, length).also { if (it > 0) bytes += it }
        override fun close() { closed = true; super.close() }
    }

    @Test fun stopsReadingAtOneHundredEntries() {
        val xml = header + (1..10000).joinToString("") { item(it) } + "</channel></rss>"
        val input = CountingStream(xml.byteInputStream())
        val feed = StreamingFeedParser.parse(input, address)
        assertEquals(100, feed.articles.size)
        assertEquals(100, feed.entriesRead)
        assertEquals(FeedLimit.ENTRIES, feed.limit)
        assertEquals("https://example.org/100", feed.articles.last().url)
        assertTrue("Do not consume the remainder after entry 100", input.bytes < 32 * 1024)
        assertEquals(input.bytes, feed.bytesRead)
        assertFalse("The HTTP owner must disconnect before closing", input.closed)
    }

    @Test fun sixMegabytesRetainsOnlyCompleteEntries() {
        val xml = header + item(1) + "<item><link>https://example.org/2</link><description><![CDATA[" +
            "x".repeat(ReaderConfig.MAX_FEED_BYTES + 1024) + "]]></description></item></channel></rss>"
        val input = CountingStream(xml.byteInputStream())
        val feed = StreamingFeedParser.parse(input, address)
        assertEquals(FeedLimit.BYTES, feed.limit)
        assertEquals(ReaderConfig.MAX_FEED_BYTES.toLong(), input.bytes)
        assertEquals(1, feed.entriesRead)
        assertEquals(listOf("https://example.org/1"), feed.articles.map { it.url })
    }

    @Test fun giantFirstEntryReturnsAnEmptyPartialFeed() {
        val feed = parse(header + "<item><description>" + "x".repeat(ReaderConfig.MAX_FEED_BYTES + 1))
        assertEquals("Small press", feed.source.name)
        assertEquals(FeedLimit.BYTES, feed.limit)
        assertTrue(feed.articles.isEmpty())
    }

    @Test fun gzipBudgetCountsDecompressedBytes() {
        val xml = header + item(1) + "<item><description>" + "x".repeat(ReaderConfig.MAX_FEED_BYTES)
        val compressed = ByteArrayOutputStream()
        GZIPOutputStream(compressed).use { it.write(xml.toByteArray()) }
        assertTrue(compressed.size() < 16384)
        val feed = GZIPInputStream(ByteArrayInputStream(compressed.toByteArray())).use {
            StreamingFeedParser.parse(it, address)
        }
        assertEquals(FeedLimit.BYTES, feed.limit)
        assertEquals(ReaderConfig.MAX_FEED_BYTES.toLong(), feed.bytesRead)
        assertEquals(1, feed.articles.size)
    }

    @Test fun shortFeedCompletesWithoutLimit() {
        val feed = parse(header + item(1) + "</channel></rss>")
        assertEquals(FeedLimit.NONE, feed.limit)
        assertEquals(1, feed.articles.size)
    }

    @Test fun noUrlAndDuplicateEntriesStillCountTowardBudget() {
        val feed = parse(header + (1..100).joinToString("") {
            if (it % 2 == 0) item(1) else "<item><title>No link</title></item>"
        } + item(2) + "</channel></rss>")
        assertEquals(100, feed.entriesRead)
        assertEquals(1, feed.articles.size)
        assertEquals(FeedLimit.ENTRIES, feed.limit)
    }

    @Test fun recognizesUtf16XmlDeclaration() {
        val xml = "<?xml version=\"1.0\" encoding=\"UTF-16\"?>" +
            header.replace("Small press", "Łódź i świat") + item(1) + "</channel></rss>"
        val feed = StreamingFeedParser.parse(xml.toByteArray(Charsets.UTF_16).inputStream(), address)
        assertEquals("Łódź i świat", feed.source.name)
    }

    @Test fun atomXhtmlAndXmlBaseRemainUsable() {
        val feed = parse("""
            <feed xmlns="http://www.w3.org/2005/Atom" xml:base="https://example.org/news/">
              <title>Notebook</title><entry xml:base="arts/">
                <title>Żółć &amp; ☕</title><link href="essay" rel="alternate"/>
                <content type="xhtml"><div xmlns="http://www.w3.org/1999/xhtml">
                  <p>First paragraph.</p><p>Second paragraph.</p><img src="picture.jpg"/>
                </div></content>
              </entry>
            </feed>
        """.trimIndent())
        val article = feed.articles.single()
        assertEquals("Żółć & ☕", article.title)
        assertEquals("First paragraph. Second paragraph.", article.text)
        assertEquals("https://example.org/news/arts/essay", article.url)
        assertEquals("https://example.org/news/arts/picture.jpg", article.thumbnail)
    }

    @Test fun rdfItemsOutsideChannelAreRecognized() {
        val feed = parse("""
            <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" xmlns="http://purl.org/rss/1.0/">
              <channel><title>Archive</title></channel>
              <item><title>A story</title><link>https://example.org/1</link></item>
            </rdf:RDF>
        """.trimIndent())
        assertEquals("Archive", feed.source.name)
        assertEquals(1, feed.articles.size)
    }

    @Test fun malformedXmlIsNotReportedAsALimit() {
        assertTrue(runCatching { parse(header + item(1) + "<item><bad></item></channel></rss>") }.isFailure)
    }

    @Test fun externalEntitiesAreRejected() {
        val xml = "<!DOCTYPE rss [<!ENTITY external SYSTEM 'file:///not-a-real-file'>]>" +
            header + "<item><title>&external;</title><link>https://example.org/1</link></item></channel></rss>"
        assertTrue(runCatching { parse(xml) }.isFailure)
    }
}
