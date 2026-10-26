package com.layerhub.engine

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

enum class SourceKind {
    DIRECT_MEDIA, REDDIT, YOUTUBE, INSTAGRAM, FACEBOOK, X, TELEGRAM, INVALID
}

enum class MediaKind { VIDEO, AUDIO, UNKNOWN }

enum class FailureReason {
    INVALID_URL,
    UNSUPPORTED_SOURCE,
    METADATA_UNAVAILABLE,
    ACCESS_DENIED,
    NOT_FOUND,
    RATE_LIMITED,
    HTTP_ERROR,
    INVALID_MIME,
    SIZE_LIMIT,
    TIMEOUT,
    REDIRECT_REJECTED,
    CANCELLED,
    IO_ERROR
}

sealed class AdapterResult<out T> {
    data class Available<T>(val value: T) : AdapterResult<T>()
    data class Unavailable(val reason: FailureReason, val message: String) : AdapterResult<Nothing>()
}

data class SourceClassification(val source: SourceKind, val normalizedUrl: String? = null)

/**
 * Classifies only a validated HTTP(S) URL. A generic web host is a direct-media candidate;
 * the response Content-Type must still prove that the resource is audio or video.
 */
object SourceClassifier {
    fun classify(value: String?): SourceClassification {
        val candidate = value?.trim()?.trimEnd('.', ',', '!', '?', ')', ']', '}', '"', '\'')
            ?: return SourceClassification(SourceKind.INVALID)
        val uri = parseHttpUri(candidate) ?: return SourceClassification(SourceKind.INVALID)
        val host = uri.host.orEmpty().removePrefix("[").removeSuffix("]").lowercase(Locale.ROOT)
        val source = when {
            host.isHostOrSubdomainOf("youtube.com") || host.isHostOrSubdomainOf("youtube-nocookie.com") ||
                host.isHostOrSubdomainOf("googlevideo.com") || host == "youtu.be" -> SourceKind.YOUTUBE
            host.isHostOrSubdomainOf("reddit.com") || host.isHostOrSubdomainOf("redditmedia.com") ||
                host.isHostOrSubdomainOf("redd.it") -> SourceKind.REDDIT
            host.isHostOrSubdomainOf("instagram.com") || host.isHostOrSubdomainOf("cdninstagram.com") ||
                host == "instagr.am" -> SourceKind.INSTAGRAM
            host.isHostOrSubdomainOf("facebook.com") || host.isHostOrSubdomainOf("fb.com") ||
                host.isHostOrSubdomainOf("fbcdn.net") || host == "fb.watch" -> SourceKind.FACEBOOK
            host.isHostOrSubdomainOf("x.com") || host.isHostOrSubdomainOf("twitter.com") ||
                host.isHostOrSubdomainOf("twimg.com") || host == "t.co" -> SourceKind.X
            host.isHostOrSubdomainOf("t.me") || host.isHostOrSubdomainOf("telegram.me") ||
                host.isHostOrSubdomainOf("telegram.dog") -> SourceKind.TELEGRAM
            else -> SourceKind.DIRECT_MEDIA
        }
        return SourceClassification(source, uri.toASCIIString())
    }
}

data class MediaMetadata(
    val title: String?,
    val thumbnailUrl: String?,
    val mimeType: String,
    val mediaKind: MediaKind,
    val sizeBytes: Long?,
    val resolvedUrl: String
)

data class MediaFormat(
    val id: String,
    val label: String,
    val mimeType: String,
    val sizeBytes: Long?
)

data class DownloadProgress(val downloadedBytes: Long, val totalBytes: Long?) {
    val fraction: Double?
        get() = totalBytes?.takeIf { it > 0 }?.let { (downloadedBytes.toDouble() / it).coerceIn(0.0, 1.0) }
}

data class DownloadedMedia(val file: File, val mimeType: String, val sizeBytes: Long)

class DownloadCancellation {
    private val cancelled = AtomicBoolean(false)
    fun cancel() = cancelled.set(true)
    fun isCancelled(): Boolean = cancelled.get()
}

/** Each source adapter owns its identity, metadata, format, and retrieval stages independently. */
interface SourceAdapter {
    val source: SourceKind
    fun identifies(url: String): Boolean
    fun retrieveMetadata(url: String): AdapterResult<MediaMetadata>
    fun discoverFormats(url: String, metadata: MediaMetadata): AdapterResult<List<MediaFormat>>
    fun retrieveMedia(
        url: String,
        format: MediaFormat,
        outputDirectory: File,
        cancellation: DownloadCancellation,
        onProgress: (DownloadProgress) -> Unit
    ): AdapterResult<DownloadedMedia>
}

class DownloaderEngine(
    val adapters: List<SourceAdapter> = listOf(DirectMediaSourceAdapter())
) {
    fun adapterFor(url: String): SourceAdapter? = adapters.firstOrNull { it.identifies(url) }
}

/**
 * The initial real retrieval adapter. It accepts direct audio/video responses only; it does not
 * extract platform streams or infer media from a URL's domain or file suffix.
 */
class DirectMediaSourceAdapter(
    private val safetyPolicy: UrlSafetyPolicy = PublicInternetUrlPolicy,
    private val allowedPorts: Set<Int> = setOf(80, 443),
    private val maxBytes: Long = DEFAULT_MAX_DOWNLOAD_BYTES,
    private val maxDurationMs: Long = DEFAULT_MAX_TRANSFER_TIME_MS,
    private val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS
) : SourceAdapter {
    override val source: SourceKind = SourceKind.DIRECT_MEDIA

    override fun identifies(url: String): Boolean = SourceClassifier.classify(url).source == source

    override fun retrieveMetadata(url: String): AdapterResult<MediaMetadata> = safely {
        val connection = openFinalConnection(url, range = "bytes=0-0")
        try {
            val status = connection.responseCode
            checkHttpStatus(status)
            if (status !in 200..299) fail(FailureReason.HTTP_ERROR, "The media host returned HTTP $status.")
            val mime = normalizeMime(connection.contentType)
                ?: fail(FailureReason.INVALID_MIME, "The host did not provide a usable media Content-Type.")
            val kind = mediaKindFor(mime)
            if (kind == MediaKind.UNKNOWN) {
                fail(FailureReason.INVALID_MIME, "The response is not identified as audio or video (Content-Type: $mime).")
            }
            val size = responseSize(connection, status)
            if (size != null && size > maxBytes) {
                fail(FailureReason.SIZE_LIMIT, "This media is larger than the ${formatBytes(maxBytes)} download limit.")
            }
            val resolved = connection.url.toURI().toASCIIString()
            AdapterResult.Available(
                MediaMetadata(
                    title = filenameFromHeaders(connection.getHeaderField("Content-Disposition"))
                        ?: filenameFromUri(connection.url.toURI()),
                    thumbnailUrl = null,
                    mimeType = mime,
                    mediaKind = kind,
                    sizeBytes = size,
                    resolvedUrl = resolved
                )
            )
        } finally {
            connection.disconnect()
        }
    }

    override fun discoverFormats(url: String, metadata: MediaMetadata): AdapterResult<List<MediaFormat>> {
        if (!identifies(url)) return unavailable(FailureReason.UNSUPPORTED_SOURCE, "This source is not a direct media URL.")
        val checked = retrieveMetadata(url)
        if (checked !is AdapterResult.Available) return checked.castUnavailable()
        val actual = checked.value
        if (actual.mediaKind != metadata.mediaKind || actual.mimeType != metadata.mimeType) {
            return unavailable(FailureReason.METADATA_UNAVAILABLE, "The media response changed since it was inspected. Inspect it again.")
        }
        return AdapterResult.Available(
            listOf(MediaFormat(id = ORIGINAL_FORMAT_ID, label = "Original", mimeType = actual.mimeType, sizeBytes = actual.sizeBytes))
        )
    }

    override fun retrieveMedia(
        url: String,
        format: MediaFormat,
        outputDirectory: File,
        cancellation: DownloadCancellation,
        onProgress: (DownloadProgress) -> Unit
    ): AdapterResult<DownloadedMedia> = safely {
        if (!identifies(url)) fail(FailureReason.UNSUPPORTED_SOURCE, "This source does not have a direct-media download adapter.")
        if (format.id != ORIGINAL_FORMAT_ID) fail(FailureReason.UNSUPPORTED_SOURCE, "Only the verified original stream is available for this URL.")
        cancellation.throwIfCancelled()

        val connection = openFinalConnection(url, range = null)
        var temporary: File? = null
        try {
            val status = connection.responseCode
            checkHttpStatus(status)
            if (status !in 200..299) fail(FailureReason.HTTP_ERROR, "The media host returned HTTP $status.")
            val mime = normalizeMime(connection.contentType)
                ?: fail(FailureReason.INVALID_MIME, "The download response did not include a media Content-Type.")
            val kind = mediaKindFor(mime)
            if (kind == MediaKind.UNKNOWN) fail(FailureReason.INVALID_MIME, "The download response is not audio or video (Content-Type: $mime).")
            if (mime != format.mimeType) fail(FailureReason.INVALID_MIME, "The media type changed after inspection. Inspect the URL again.")

            val expectedBytes = responseSize(connection, status)
            if (expectedBytes != null && expectedBytes > maxBytes) {
                fail(FailureReason.SIZE_LIMIT, "This media is larger than the ${formatBytes(maxBytes)} download limit.")
            }
            if (!outputDirectory.exists() && !outputDirectory.mkdirs()) {
                fail(FailureReason.IO_ERROR, "Layer Hub could not create its temporary download folder.")
            }
            if (!outputDirectory.isDirectory) fail(FailureReason.IO_ERROR, "The temporary download path is not a folder.")
            temporary = File.createTempFile(".layerhub-", ".part", outputDirectory)

            val startedAt = System.nanoTime()
            var downloaded = 0L
            connection.inputStream.buffered().use { input ->
                BufferedOutputStream(FileOutputStream(temporary)).use { output ->
                    val prefix = ByteArray(MIME_SNIFF_BYTES)
                    var prefixSize = 0
                    while (prefixSize < prefix.size) {
                        cancellation.throwIfCancelled()
                        checkElapsed(startedAt)
                        val count = input.read(prefix, prefixSize, prefix.size - prefixSize)
                        if (count < 0) break
                        if (count == 0) continue
                        prefixSize += count
                    }
                    if (prefixSize == 0) fail(FailureReason.INVALID_MIME, "The media response was empty.")
                    if (looksLikeHtml(prefix, prefixSize)) {
                        fail(FailureReason.INVALID_MIME, "The host returned an HTML page instead of the advertised media file.")
                    }
                    output.write(prefix, 0, prefixSize)
                    downloaded += prefixSize
                    if (downloaded > maxBytes) fail(FailureReason.SIZE_LIMIT, "The download exceeded the ${formatBytes(maxBytes)} size limit.")
                    safelyReport(onProgress, DownloadProgress(downloaded, expectedBytes))

                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        cancellation.throwIfCancelled()
                        checkElapsed(startedAt)
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        downloaded += count
                        if (downloaded > maxBytes) fail(FailureReason.SIZE_LIMIT, "The download exceeded the ${formatBytes(maxBytes)} size limit.")
                        output.write(buffer, 0, count)
                        safelyReport(onProgress, DownloadProgress(downloaded, expectedBytes))
                    }
                }
            }
            cancellation.throwIfCancelled()
            if (expectedBytes != null && downloaded != expectedBytes) {
                fail(FailureReason.IO_ERROR, "The media download ended early (${formatBytes(downloaded)} of ${formatBytes(expectedBytes)} received).")
            }
            if (downloaded == 0L) fail(FailureReason.INVALID_MIME, "The media response was empty.")

            val finalUrl = connection.url.toURI()
            val baseName = filenameFromHeaders(connection.getHeaderField("Content-Disposition"))
                ?: filenameFromUri(finalUrl)
                ?: "layerhub-media"
            val destination = uniqueDestination(outputDirectory, safeFilename(baseName))
            if (!temporary.renameTo(destination)) {
                fail(FailureReason.IO_ERROR, "Layer Hub could not finalize the downloaded file.")
            }
            temporary = null
            AdapterResult.Available(DownloadedMedia(destination, mime, downloaded))
        } finally {
            connection.disconnect()
            temporary?.delete()
        }
    }

    private fun openFinalConnection(value: String, range: String?): HttpURLConnection {
        var current = parseHttpUri(value) ?: fail(FailureReason.INVALID_URL, "Enter a valid HTTP or HTTPS URL.")
        val initiallySecure = current.scheme.equals("https", ignoreCase = true)
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            if (!isPermittedUrl(current)) fail(FailureReason.REDIRECT_REJECTED, "This URL points to a local, private, or unsupported network address.")
            if (SourceClassifier.classify(current.toASCIIString()).source != SourceKind.DIRECT_MEDIA) {
                fail(FailureReason.UNSUPPORTED_SOURCE, "This URL redirects to a platform source without an authorized direct-media adapter.")
            }
            if (!safetyPolicy.allows(current)) fail(FailureReason.REDIRECT_REJECTED, "The media URL could not be verified as a public address.")
            val connection = current.toURL().openConnection() as? HttpURLConnection
                ?: fail(FailureReason.INVALID_URL, "Only HTTP and HTTPS media URLs are supported.")
            connection.instanceFollowRedirects = false
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "LayerHub-Downloader/1.0 (Android)")
            connection.setRequestProperty("Accept", "video/*, audio/*;q=0.9, */*;q=0.1")
            if (range != null) connection.setRequestProperty("Range", range)
            val status = try {
                connection.responseCode
            } catch (error: SocketTimeoutException) {
                connection.disconnect()
                fail(FailureReason.TIMEOUT, "The media host took too long to respond.", error)
            } catch (error: IOException) {
                connection.disconnect()
                fail(FailureReason.IO_ERROR, "The media host could not be reached. Check the URL and connection.", error)
            }
            if (status !in REDIRECT_CODES) return connection
            val location = connection.getHeaderField("Location")
            connection.disconnect()
            if (location.isNullOrBlank() || redirectCount >= MAX_REDIRECTS) {
                fail(FailureReason.REDIRECT_REJECTED, "The media host returned an invalid or excessive redirect chain.")
            }
            val next = runCatching { current.resolve(location) }.getOrNull()
                ?: fail(FailureReason.REDIRECT_REJECTED, "The media host returned an invalid redirect.")
            if (initiallySecure && next.scheme.equals("http", ignoreCase = true)) {
                fail(FailureReason.REDIRECT_REJECTED, "The media host tried to redirect from HTTPS to an unencrypted connection.")
            }
            current = next
        }
        fail(FailureReason.REDIRECT_REJECTED, "The media host returned too many redirects.")
    }

    private fun isPermittedUrl(uri: URI): Boolean {
        val normalized = parseHttpUri(uri.toASCIIString()) ?: return false
        val host = normalized.host.orEmpty().removePrefix("[").removeSuffix("]")
        if (normalized.rawUserInfo != null) return false
        if (normalized.port != -1 && normalized.port !in allowedPorts) return false
        return host.isNotBlank()
    }

    private fun checkElapsed(startedAt: Long) {
        if ((System.nanoTime() - startedAt) / 1_000_000 > maxDurationMs) {
            fail(FailureReason.TIMEOUT, "The download exceeded the ${maxDurationMs / 60_000}-minute time limit.")
        }
    }

    private inline fun <T> safely(block: () -> AdapterResult<T>): AdapterResult<T> = try {
        block()
    } catch (error: EngineFailure) {
        AdapterResult.Unavailable(error.reason, error.message ?: "The media operation could not be completed.")
    } catch (_: SocketTimeoutException) {
        unavailable(FailureReason.TIMEOUT, "The media host took too long to respond.")
    } catch (_: SecurityException) {
        unavailable(FailureReason.ACCESS_DENIED, "The media host denied access to this URL.")
    } catch (_: IOException) {
        unavailable(FailureReason.IO_ERROR, "The media transfer failed. Check the network and try again.")
    } catch (_: IllegalArgumentException) {
        unavailable(FailureReason.INVALID_URL, "Enter a valid HTTP or HTTPS URL.")
    }

    private fun unavailable(reason: FailureReason, message: String): AdapterResult.Unavailable =
        AdapterResult.Unavailable(reason, message)

    private fun checkHttpStatus(status: Int) {
        when (status) {
            401, 403 -> fail(FailureReason.ACCESS_DENIED, "The server requires access that Layer Hub does not have.")
            404, 410 -> fail(FailureReason.NOT_FOUND, "The media file is no longer available at this URL.")
            429 -> fail(FailureReason.RATE_LIMITED, "The media host is rate-limiting requests. Try again later.")
        }
    }

    private fun responseSize(connection: HttpURLConnection, status: Int): Long? {
        if (status == HttpURLConnection.HTTP_PARTIAL) {
            val total = CONTENT_RANGE_REGEX.find(connection.getHeaderField("Content-Range").orEmpty())
                ?.groupValues?.getOrNull(1)?.toLongOrNull()
            if (total != null && total >= 0) return total
            return null
        }
        return connection.getHeaderFieldLong("Content-Length", -1L).takeIf { it >= 0 }
    }

    private fun filenameFromHeaders(header: String?): String? {
        if (header.isNullOrBlank()) return null
        val encoded = Regex("(?i)(?:^|;)\\s*filename\\*\\s*=\\s*(?:UTF-8''|\")?([^;\"]+)")
            .find(header)?.groupValues?.getOrNull(1)?.trim()
        val regular = Regex("(?i)(?:^|;)\\s*filename\\s*=\\s*\"?([^;\"]+)\"?")
            .find(header)?.groupValues?.getOrNull(1)?.trim()
        val value = (encoded ?: regular)?.let { runCatching { URLDecoder.decode(it.replace("+", "%2B"), StandardCharsets.UTF_8.name()) }.getOrNull() }
        return value?.substringAfterLast('/')?.substringAfterLast('\\')?.takeIf(String::isNotBlank)
    }

    private fun filenameFromUri(uri: URI): String? = uri.path?.substringAfterLast('/')
        ?.takeIf(String::isNotBlank)
        ?.let { runCatching { URLDecoder.decode(it.replace("+", "%2B"), StandardCharsets.UTF_8.name()) }.getOrNull() }
        ?.takeIf(String::isNotBlank)

    private fun safeFilename(value: String): String = value.substringAfterLast('/').substringAfterLast('\\')
        .replace(Regex("[^A-Za-z0-9._-]"), "_")
        .trim('.', '_')
        .take(100)
        .ifBlank { "layerhub-media" }

    private fun uniqueDestination(directory: File, name: String): File {
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        val stem = name.substring(0, dot)
        val extension = name.substring(dot)
        var candidate = File(directory, name)
        var suffix = 1
        while (candidate.exists()) {
            candidate = File(directory, "$stem-$suffix$extension")
            suffix++
        }
        return candidate
    }

    private fun looksLikeHtml(bytes: ByteArray, size: Int): Boolean {
        var index = 0
        if (size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) index = 3
        while (index < size && bytes[index].toInt().toChar().isWhitespace()) index++
        val prefix = String(bytes, index, (size - index).coerceAtMost(64), StandardCharsets.US_ASCII)
            .lowercase(Locale.ROOT)
        return prefix.startsWith("<!doctype html") || prefix.startsWith("<html") ||
            prefix.startsWith("<head") || prefix.startsWith("<body")
    }

    private fun safelyReport(listener: (DownloadProgress) -> Unit, progress: DownloadProgress) {
        runCatching { listener(progress) }
    }

    private fun DownloadCancellation.throwIfCancelled() {
        if (isCancelled() || Thread.currentThread().isInterrupted) {
            fail(FailureReason.CANCELLED, "The download was cancelled.")
        }
    }

    private fun mediaKindFor(mime: String): MediaKind = when {
        mime.startsWith("video/") -> MediaKind.VIDEO
        mime.startsWith("audio/") -> MediaKind.AUDIO
        else -> MediaKind.UNKNOWN
    }

    private fun normalizeMime(value: String?): String? = value?.substringBefore(';')?.trim()
        ?.lowercase(Locale.ROOT)?.takeIf { it.contains('/') && it.length <= 128 }

    private fun formatBytes(bytes: Long): String = if (bytes >= 1024L * 1024L) {
        "${bytes / (1024L * 1024L)} MB"
    } else {
        "${bytes / 1024L} KB"
    }

    private fun <T> AdapterResult<MediaMetadata>.castUnavailable(): AdapterResult<T> = when (this) {
        is AdapterResult.Unavailable -> this
        is AdapterResult.Available -> error("Cannot cast an available metadata result")
    }

    private fun fail(reason: FailureReason, message: String, cause: Throwable? = null): Nothing =
        throw EngineFailure(reason, message, cause)

    private class EngineFailure(val reason: FailureReason, message: String, cause: Throwable? = null) : IOException(message, cause)
}

interface UrlSafetyPolicy {
    fun allows(uri: URI): Boolean
}

/** Rejects local/private destinations before every request and redirect hop. */
object PublicInternetUrlPolicy : UrlSafetyPolicy {
    override fun allows(uri: URI): Boolean {
        val parsed = parseHttpUri(uri.toASCIIString()) ?: return false
        val host = parsed.host.orEmpty().removePrefix("[").removeSuffix("]").lowercase(Locale.ROOT)
        if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local") || host.endsWith(".internal")) return false
        return try {
            val addresses = InetAddress.getAllByName(host)
            addresses.isNotEmpty() && addresses.all(::isPublicAddress)
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    private fun isPublicAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress) return false
        val bytes = address.address.map { it.toInt() and 0xff }
        if (address is Inet4Address) {
            val first = bytes[0]
            val second = bytes[1]
            return when {
                first == 0 || first >= 224 -> false
                first == 100 && second in 64..127 -> false
                first == 169 && second == 254 -> false
                first == 192 && second == 0 -> false
                first == 192 && second == 168 -> false
                first == 198 && second in setOf(18, 19, 51) -> false
                first == 203 && second == 0 && bytes[2] == 113 -> false
                first == 172 && second in 16..31 -> false
                first == 192 && second == 88 -> false
                else -> true
            }
        }
        if (address is Inet6Address) {
            val first = bytes[0]
            val second = bytes[1]
            if ((first and 0xfe) == 0xfc) return false // unique-local fc00::/7
            if (first == 0x20 && second == 0x01 && bytes[2] == 0x0d && bytes[3] == 0xb8) return false // documentation
            if (first == 0x20 && second == 0x01 && bytes[2] == 0x00 && bytes[3] == 0x00) return false // special-purpose
        }
        return true
    }
}

private fun parseHttpUri(value: String): URI? {
    val uri = runCatching { URI(value) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase(Locale.ROOT)
    if (uri.isOpaque || scheme !in setOf("http", "https") || uri.rawUserInfo != null) return null
    val host = uri.host?.removePrefix("[")?.removeSuffix("]") ?: return null
    if (host.isBlank()) return null
    return uri
}

private fun String.isHostOrSubdomainOf(domain: String): Boolean = this == domain || endsWith(".$domain")

private const val ORIGINAL_FORMAT_ID = "original"
private const val DEFAULT_MAX_DOWNLOAD_BYTES = 512L * 1024 * 1024
private const val DEFAULT_MAX_TRANSFER_TIME_MS = 20L * 60 * 1000
private const val DEFAULT_CONNECT_TIMEOUT_MS = 10_000
private const val DEFAULT_READ_TIMEOUT_MS = 30_000
private const val MAX_REDIRECTS = 5
private const val MIME_SNIFF_BYTES = 512
private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
private val CONTENT_RANGE_REGEX = Regex("^bytes\\s+\\d+-\\d+/(\\d+)$", RegexOption.IGNORE_CASE)
