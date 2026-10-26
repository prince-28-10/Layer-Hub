package com.layerhub.engine

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class DirectMediaSourceAdapterTest {
    private val temporaryRoot = Files.createTempDirectory("layerhub-engine-test").toFile()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply { start() }
    private val adapter = DirectMediaSourceAdapter(
        safetyPolicy = object : UrlSafetyPolicy {
            override fun allows(uri: URI): Boolean = uri.host == "127.0.0.1"
        },
        allowedPorts = setOf(server.address.port),
        maxBytes = 128,
        maxDurationMs = 5_000,
        connectTimeoutMs = 1_000,
        readTimeoutMs = 1_000
    )

    @After
    fun tearDown() {
        server.stop(0)
        temporaryRoot.deleteRecursively()
    }

    @Test
    fun classifiesKnownSourcesAndGenericDirectCandidatesWithoutTrustingFileSuffixes() {
        assertEquals(SourceKind.YOUTUBE, SourceClassifier.classify("https://youtu.be/abcdefghijk").source)
        assertEquals(SourceKind.REDDIT, SourceClassifier.classify("https://old.reddit.com/r/test/comments/a/title").source)
        assertEquals(SourceKind.INSTAGRAM, SourceClassifier.classify("https://instagram.com/reel/abc/").source)
        assertEquals(SourceKind.FACEBOOK, SourceClassifier.classify("https://fb.watch/abc/").source)
        assertEquals(SourceKind.X, SourceClassifier.classify("https://x.com/user/status/123").source)
        assertEquals(SourceKind.TELEGRAM, SourceClassifier.classify("https://t.me/channel/2").source)
        assertEquals(SourceKind.YOUTUBE, SourceClassifier.classify("https://r1.googlevideo.com/videoplayback").source)
        assertEquals(SourceKind.REDDIT, SourceClassifier.classify("https://v.redd.it/clip/DASH_720.mp4").source)
        assertEquals(SourceKind.X, SourceClassifier.classify("https://pbs.twimg.com/media/video.mp4").source)
        assertEquals(SourceKind.DIRECT_MEDIA, SourceClassifier.classify("https://cdn.example.test/watch").source)
        assertEquals(SourceKind.DIRECT_MEDIA, SourceClassifier.classify("https://youtube.com.evil.test/watch?v=x").source)
        assertEquals(SourceKind.INVALID, SourceClassifier.classify("http://user@example.test/video.mp4").source)
        assertEquals(SourceKind.INVALID, SourceClassifier.classify("file:///tmp/video.mp4").source)
        assertEquals(SourceKind.DIRECT_MEDIA, SourceClassifier.classify("https://example.test:8443/video.mp4").source)
    }

    @Test
    fun metadataUsesVerifiedMimeAndContentDispositionRatherThanTheUrlPath() {
        server.createContext("/unknown-path") { exchange ->
            exchange.responseHeaders.add("Content-Type", "video/mp4; codecs=avc1")
            exchange.responseHeaders.add("Content-Disposition", "attachment; filename*=UTF-8''genuine%20clip.mp4")
            respondRangeAware(exchange, MP4_BYTES)
        }

        val result = adapter.retrieveMetadata(url("/unknown-path"))

        assertTrue(result is AdapterResult.Available)
        val metadata = (result as AdapterResult.Available).value
        assertEquals("genuine clip.mp4", metadata.title)
        assertEquals("video/mp4", metadata.mimeType)
        assertEquals(MediaKind.VIDEO, metadata.mediaKind)
        assertEquals(MP4_BYTES.size.toLong(), metadata.sizeBytes)
        assertNull(metadata.thumbnailUrl)
    }

    @Test
    fun rejectsHtmlAndNonMediaMimeEvenWhenUrlLooksLikeVideo() {
        server.createContext("/html.mp4") { exchange ->
            exchange.responseHeaders.add("Content-Type", "video/mp4")
            val body = "<!doctype html><html>login</html>".toByteArray()
            respond(exchange, 200, body)
        }
        server.createContext("/text.mp4") { exchange ->
            exchange.responseHeaders.add("Content-Type", "text/html")
            respond(exchange, 200, "not media".toByteArray())
        }

        val htmlResult = downloadUrl(url("/html.mp4"))
        val textResult = adapter.retrieveMetadata(url("/text.mp4"))

        assertEquals(FailureReason.INVALID_MIME, (htmlResult as AdapterResult.Unavailable).reason)
        assertEquals(FailureReason.INVALID_MIME, (textResult as AdapterResult.Unavailable).reason)
        assertTrue(temporaryRoot.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun followsSafeRedirectAndReportsBytesActuallyWritten() {
        server.createContext("/redirect") { exchange ->
            exchange.responseHeaders.add("Location", "/media")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.createContext("/media") { exchange ->
            exchange.responseHeaders.add("Content-Type", "video/mp4")
            exchange.responseHeaders.add("Content-Disposition", "attachment; filename=clip.mp4")
            respondRangeAware(exchange, MP4_BYTES)
        }
        val progress = CopyOnWriteArrayList<DownloadProgress>()

        val result = downloadUrl(url("/redirect"), progress)

        assertTrue(result is AdapterResult.Available)
        val downloaded = (result as AdapterResult.Available).value
        assertTrue(downloaded.file.isFile)
        assertEquals(MP4_BYTES.size.toLong(), downloaded.file.length())
        assertEquals(MP4_BYTES.toList(), downloaded.file.readBytes().toList())
        assertTrue(progress.isNotEmpty())
        assertEquals(MP4_BYTES.size.toLong(), progress.last().downloadedBytes)
        assertEquals(MP4_BYTES.size.toLong(), progress.last().totalBytes)
    }

    @Test
    fun rejectsOversizedMediaBeforeWritingAndCleansPartialFilesOnCancellation() {
        server.createContext("/large") { exchange ->
            exchange.responseHeaders.add("Content-Type", "video/mp4")
            respondRangeAware(exchange, ByteArray(256) { 1 })
        }
        val inspected = adapter.retrieveMetadata(url("/large"))
        assertEquals(FailureReason.SIZE_LIMIT, (inspected as AdapterResult.Unavailable).reason)

        server.createContext("/cancel") { exchange ->
            exchange.responseHeaders.add("Content-Type", "video/mp4")
            respondRangeAware(exchange, ByteArray(80) { 3 })
        }
        val metadata = (adapter.retrieveMetadata(url("/cancel")) as AdapterResult.Available).value
        val format = (adapter.discoverFormats(url("/cancel"), metadata) as AdapterResult.Available).value.single()
        val cancellation = DownloadCancellation()
        val cancelled = adapter.retrieveMedia(url("/cancel"), format, temporaryRoot, cancellation) { cancellation.cancel() }

        assertEquals(FailureReason.CANCELLED, (cancelled as AdapterResult.Unavailable).reason)
        assertTrue(temporaryRoot.listFiles().orEmpty().none { it.name.endsWith(".part") })
    }

    @Test
    fun rejectsMimeChangesAndRedirectsToLocalHosts() {
        val requests = AtomicInteger()
        server.createContext("/changes-mime") { exchange ->
            exchange.responseHeaders.add("Content-Type", if (exchange.requestHeaders.getFirst("Range") != null) "video/mp4" else "application/octet-stream")
            respondRangeAware(exchange, MP4_BYTES)
        }
        server.createContext("/local-redirect") { exchange ->
            exchange.responseHeaders.add("Location", "http://localhost:${server.address.port}/media")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
            requests.incrementAndGet()
        }
        server.createContext("/platform-redirect") { exchange ->
            exchange.responseHeaders.add("Location", "https://r1.googlevideo.com/videoplayback")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.createContext("/media") { exchange ->
            exchange.responseHeaders.add("Content-Type", "video/mp4")
            respondRangeAware(exchange, MP4_BYTES)
        }

        val metadata = (adapter.retrieveMetadata(url("/changes-mime")) as AdapterResult.Available).value
        val format = (adapter.discoverFormats(url("/changes-mime"), metadata) as AdapterResult.Available).value.single()
        val changedMime = adapter.retrieveMedia(url("/changes-mime"), format, temporaryRoot, DownloadCancellation()) {}
        val redirected = adapter.retrieveMetadata(url("/local-redirect"))
        val platformRedirect = adapter.retrieveMetadata(url("/platform-redirect"))

        assertEquals(FailureReason.INVALID_MIME, (changedMime as AdapterResult.Unavailable).reason)
        assertEquals(FailureReason.REDIRECT_REJECTED, (redirected as AdapterResult.Unavailable).reason)
        assertEquals(FailureReason.UNSUPPORTED_SOURCE, (platformRedirect as AdapterResult.Unavailable).reason)
        assertEquals(1, requests.get())
        assertTrue(temporaryRoot.listFiles().orEmpty().isEmpty())
        assertFalse(PublicInternetUrlPolicy.allows(URI("http://127.0.0.1/video.mp4")))
    }

    @Test
    fun downloaderRegistryDoesNotRoutePlatformLinksToDirectRetrieval() {
        val engine = DownloaderEngine(listOf(adapter))

        assertNotNull(engine.adapterFor(url("/media.mp4")))
        assertNull(engine.adapterFor("https://youtube.com/watch?v=abcdefghijk"))
    }

    private fun downloadUrl(value: String, progress: MutableList<DownloadProgress> = mutableListOf()): AdapterResult<DownloadedMedia> {
        val metadata = adapter.retrieveMetadata(value)
        if (metadata !is AdapterResult.Available) return AdapterResult.Unavailable(
            (metadata as AdapterResult.Unavailable).reason,
            metadata.message
        )
        val format = (adapter.discoverFormats(value, metadata.value) as AdapterResult.Available).value.single()
        return adapter.retrieveMedia(value, format, temporaryRoot, DownloadCancellation()) { progress.add(it) }
    }

    private fun url(path: String) = "http://127.0.0.1:${server.address.port}$path"

    private fun respondRangeAware(exchange: HttpExchange, body: ByteArray) {
        if (exchange.requestHeaders.getFirst("Range") != null) {
            exchange.responseHeaders.add("Content-Range", "bytes 0-0/${body.size}")
            exchange.responseHeaders.add("Content-Length", "1")
            exchange.sendResponseHeaders(206, 1)
            exchange.responseBody.use { it.write(body, 0, 1) }
        } else {
            respond(exchange, 200, body)
        }
    }

    private fun respond(exchange: HttpExchange, status: Int, body: ByteArray) {
        exchange.sendResponseHeaders(status, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    }

    companion object {
        private val MP4_BYTES = byteArrayOf(
            0, 0, 0, 24, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(),
            'i'.code.toByte(), 's'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(), 0, 0, 0, 0,
            'm'.code.toByte(), 'p'.code.toByte(), '4'.code.toByte(), '2'.code.toByte()
        )
    }
}
