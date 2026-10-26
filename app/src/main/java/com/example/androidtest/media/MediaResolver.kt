package com.example.androidtest.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Size
import android.util.Base64
import android.provider.OpenableColumns
import com.example.androidtest.BuildConfig
import com.layerhub.engine.AdapterResult
import com.layerhub.engine.DownloaderEngine
import com.layerhub.engine.FailureReason
import com.layerhub.engine.MediaKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

interface MediaInputResolver {
    suspend fun resolve(context: Context, input: ShareInput): MediaResolution?
}

class MediaResolverRegistry(
    private val backendApi: MediaAnalysisApi = BackendNotConfiguredApi(),
    private val resolvers: List<MediaInputResolver> = listOf(
        SharedMediaUriResolver(),
        BackendUrlResolver(backendApi),
        DirectMediaUrlResolver(),
        PlatformLinkResolver(),
        UrlSourceResolver()
    )
) {
    suspend fun resolve(context: Context, input: ShareInput): MediaResolution {
        for (resolver in resolvers) {
            resolver.resolve(context, input)?.let { return it }
        }
        return MediaResolution(
            status = ResolutionStatus.INVALID,
            sourceName = "Shared content",
            message = input.message ?: "Share a media file or a supported link to see its preview."
        )
    }
}

class BackendUrlResolver(private val api: MediaAnalysisApi) : MediaInputResolver {
    override suspend fun resolve(context: Context, input: ShareInput): MediaResolution? {
        val sharedText = buildString {
            input.text?.let(::append)
            input.uris.filter { it.scheme.equals("http", true) || it.scheme.equals("https", true) }
                .forEach { append(' ').append(it) }
        }.trim()
        if (sharedText.isEmpty()) return null
        val url = extractSharedUrl(sharedText)?.toString()
            ?: sharedText.takeIf { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }
            ?: return null

        return try {
            val analysis = api.analyze(MediaAnalysisRequest(url))
            val isVideo = analysis.mediaType == MediaType.VIDEO
            val isAudio = analysis.mediaType == MediaType.AUDIO
            val durationMs = analysis.durationSeconds?.let { it * 1000L }
            val durationStatus = when {
                !isVideo && !isAudio -> VideoDurationStatus.NOT_APPLICABLE
                durationMs == null -> VideoDurationStatus.UNKNOWN
                durationMs > MAX_VIDEO_DURATION_MS -> VideoDurationStatus.EXCEEDS_LIMIT
                else -> VideoDurationStatus.WITHIN_LIMIT
            }

            val statusUpper = analysis.status?.uppercase() ?: "SUCCESS"
            val resolutionStatus = when (statusUpper) {
                "NOT_CONFIGURED" -> ResolutionStatus.NOT_CONFIGURED
                "UNSUPPORTED" -> ResolutionStatus.UNSUPPORTED
                "RESTRICTED" -> ResolutionStatus.RESTRICTED
                "RATE_LIMITED" -> ResolutionStatus.RATE_LIMITED
                "INACCESSIBLE" -> ResolutionStatus.INACCESSIBLE
                "ERROR", "INVALID" -> ResolutionStatus.INVALID
                else -> ResolutionStatus.READY
            }

            val canPrepare = when {
                resolutionStatus != ResolutionStatus.READY -> false
                analysis.mediaType == MediaType.IMAGE -> true
                isVideo || isAudio -> durationStatus == VideoDurationStatus.WITHIN_LIMIT
                else -> false
            }

            val platformName = analysis.detectedPlatform ?: identifySourcePlatform(url).displayName
            val defaultMessage = when {
                durationStatus == VideoDurationStatus.EXCEEDS_LIMIT ->
                    "Video exceeds the 3-minute limit. Please choose a video that is 180 seconds or shorter."
                resolutionStatus == ResolutionStatus.NOT_CONFIGURED ->
                    "$platformName configuration is required on the backend."
                resolutionStatus == ResolutionStatus.UNSUPPORTED ->
                    "$platformName links are not supported for media extraction."
                else -> null
            }

            MediaResolution(
                status = resolutionStatus,
                sourceName = platformName,
                title = analysis.title,
                mediaType = analysis.mediaType,
                durationMs = durationMs,
                durationStatus = durationStatus,
                thumbnail = null,
                message = analysis.message ?: defaultMessage,
                isVerified = true,
                canPrepare = canPrepare,
                videoQualities = analysis.videoQualities,
                audioQualities = analysis.audioQualities,
                backendAnalysis = analysis
            )
        } catch (_: Exception) {
            null
        }
    }
}

private class UrlSourceResolver : MediaInputResolver {
    override suspend fun resolve(context: Context, input: ShareInput): MediaResolution? {
        val sharedText = buildString {
            input.text?.let(::append)
            input.uris.filter { it.scheme.equals("http", true) || it.scheme.equals("https", true) }
                .forEach { append(' ').append(it) }
        }.trim()
        if (sharedText.isEmpty()) return null
        val platform = identifySourcePlatform(sharedText)
        return MediaResolution(
            status = ResolutionStatus.READY,
            sourceName = platform.displayName,
            mediaType = MediaType.UNKNOWN,
            isVerified = platform != SourcePlatform.UNKNOWN
        )
    }
}

private class SharedMediaUriResolver : MediaInputResolver {
    override suspend fun resolve(context: Context, input: ShareInput): MediaResolution? {
        if (input.uris.isEmpty()) return null
        if (input.uris.all { it.scheme.equals("http", true) || it.scheme.equals("https", true) }) return null
        return withContext(Dispatchers.IO) {
            try {
                val resolver = context.contentResolver
                val first = input.uris.first()
                val metadata = input.uris.map { uri ->
                    val mime = resolver.getType(uri) ?: input.mimeType?.takeIf(::isMediaMime)
                    val kind = mediaTypeForMime(mime)
                    val title = queryDisplayName(resolver, uri)
                    val duration = if (kind == MediaType.VIDEO || kind == MediaType.AUDIO) readDuration(context, uri) else null
                    Triple(kind, duration, title)
                }
                val kind = metadata.first().first
                val isConsistent = kind != MediaType.UNKNOWN && metadata.all { it.first == kind }
                val hasDurationLimit = kind == MediaType.VIDEO || kind == MediaType.AUDIO
                val duration = if (hasDurationLimit && input.uris.size == 1) {
                    metadata.first().second
                } else null
                val durationStatus = when {
                    !hasDurationLimit -> VideoDurationStatus.NOT_APPLICABLE
                    metadata.any { item ->
                        (item.first == MediaType.VIDEO || item.first == MediaType.AUDIO) && item.second?.let { it <= 0L || it > MAX_VIDEO_DURATION_MS } == true
                    } ->
                        VideoDurationStatus.EXCEEDS_LIMIT
                    metadata.all { (it.first != MediaType.VIDEO && it.first != MediaType.AUDIO) || it.second != null } ->
                        VideoDurationStatus.WITHIN_LIMIT
                    else -> VideoDurationStatus.UNKNOWN
                }
                val mime = resolver.getType(first) ?: input.mimeType?.takeIf(::isMediaMime)
                val canPrepare = isConsistent && when (kind) {
                    MediaType.IMAGE -> true
                    MediaType.VIDEO, MediaType.AUDIO -> durationStatus == VideoDurationStatus.WITHIN_LIMIT
                    MediaType.UNKNOWN -> false
                }
                val thumbnail = loadThumbnail(context, first, kind)
                val message = when {
                    !isConsistent -> "The shared items do not have one supported media type."
                    durationStatus == VideoDurationStatus.EXCEEDS_LIMIT ->
                        "This ${if (kind == MediaType.AUDIO) "audio" else "video"} must be between 1 second and 3 minutes."
                    durationStatus == VideoDurationStatus.UNKNOWN ->
                        "The media duration could not be verified, so it cannot be prepared."
                    kind == MediaType.UNKNOWN -> "The shared file type could not be verified."
                    else -> null
                }
                MediaResolution(
                    status = if (kind == MediaType.UNKNOWN) ResolutionStatus.UNSUPPORTED else ResolutionStatus.READY,
                    sourceName = "Shared media",
                    title = metadata.first().third,
                    mimeType = mime,
                    mediaType = kind,
                    durationMs = duration,
                    durationStatus = durationStatus,
                    thumbnail = thumbnail,
                    uris = input.uris,
                    message = message,
                    isVerified = isConsistent,
                    canPrepare = canPrepare
                )
            } catch (_: SecurityException) {
                MediaResolution(
                    status = ResolutionStatus.INACCESSIBLE,
                    sourceName = "Shared media",
                    uris = input.uris,
                    message = "Layer Hub cannot access this shared file. Share it again and allow file access."
                )
            } catch (_: IOException) {
                MediaResolution(
                    status = ResolutionStatus.INACCESSIBLE,
                    sourceName = "Shared media",
                    uris = input.uris,
                    message = "The shared file could not be read. Share it again or choose another file."
                )
            } catch (_: RuntimeException) {
                MediaResolution(
                    status = ResolutionStatus.INVALID,
                    sourceName = "Shared media",
                    uris = input.uris,
                    message = "The shared file could not be inspected."
                )
            }
        }
    }
}

private class PlatformLinkResolver : MediaInputResolver {
    override suspend fun resolve(context: Context, input: ShareInput): MediaResolution? {
        val link = parseMetadataSourceLink(input.text)
        if (link != null) {
            return when (link.source) {
                MetadataSource.REDDIT -> MediaResolution(
                    status = ResolutionStatus.NOT_CONFIGURED,
                    sourceName = "Reddit",
                    message = "Reddit metadata requires Layer Hub backend service connectivity. Connect to the backend to view post metadata."
                )
                MetadataSource.YOUTUBE -> MediaResolution(
                    status = ResolutionStatus.NOT_CONFIGURED,
                    sourceName = "YouTube",
                    message = "YouTube metadata requires Layer Hub backend service connectivity."
                )
            }
        }
        val host = extractSharedUrl(input.text)?.host?.lowercase(Locale.ROOT) ?: return null
        val sourceName = when {
            host == "instagram.com" || host.endsWith(".instagram.com") || host == "instagr.am" -> "Instagram"
            host == "facebook.com" || host.endsWith(".facebook.com") || host == "fb.watch" || host == "fb.com" -> "Facebook"
            host == "x.com" || host.endsWith(".x.com") || host == "twitter.com" || host.endsWith(".twitter.com") || host == "t.co" -> "X"
            host == "t.me" || host.endsWith(".t.me") || host == "telegram.me" -> "Telegram"
            host == "v.redd.it" || host.endsWith(".redditmedia.com") -> "Reddit"
            else -> return null
        }
        return MediaResolution(
            status = ResolutionStatus.NOT_CONFIGURED,
            sourceName = sourceName,
            message = "$sourceName requires Layer Hub backend service connectivity."
        )
    }
}

private data class AdapterError(val status: ResolutionStatus, val message: String)
private data class HttpResult(val statusCode: Int, val body: ByteArray)

private fun SourceMediaMetadata.toResolution(
    sourceName: String,
    thumbnail: Bitmap?,
    unavailableMessage: String
): MediaResolution {
    val durationStatus = when {
        mediaType != MediaType.VIDEO -> VideoDurationStatus.NOT_APPLICABLE
        durationMs == null -> VideoDurationStatus.UNKNOWN
        durationMs > MAX_VIDEO_DURATION_MS -> VideoDurationStatus.EXCEEDS_LIMIT
        else -> VideoDurationStatus.WITHIN_LIMIT
    }
    return MediaResolution(
        status = if (mediaType == MediaType.UNKNOWN) ResolutionStatus.UNSUPPORTED else ResolutionStatus.READY,
        sourceName = sourceName,
        title = title,
        mediaType = mediaType,
        durationMs = durationMs,
        durationStatus = durationStatus,
        thumbnail = thumbnail,
        message = if (mediaType == MediaType.UNKNOWN) {
            "$sourceName post metadata was resolved, but its media type could not be verified."
        } else unavailableMessage,
        isVerified = mediaType != MediaType.UNKNOWN,
        canPrepare = false
    )
}

private fun requestHttp(
    url: URL,
    method: String = "GET",
    userAgent: String,
    headers: Map<String, String> = emptyMap(),
    body: String? = null,
    allowedHosts: Set<String>,
    maxBytes: Int = MAX_METADATA_BYTES
): HttpResult {
    var currentUrl = url
    var currentMethod = method
    repeat(MAX_REDIRECTS + 1) { redirectCount ->
        if (currentUrl.protocol != "https" || !isAllowedHost(currentUrl.host, allowedHosts)) {
            throw IOException("Unsupported metadata host")
        }
        val connection = currentUrl.openConnection() as? HttpURLConnection ?: throw IOException("Unsupported connection")
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 8_000
            connection.readTimeout = 10_000
            connection.requestMethod = currentMethod
            connection.setRequestProperty("User-Agent", userAgent)
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            if (body != null && currentMethod != "GET") {
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            if (code in REDIRECT_CODES) {
                val location = connection.getHeaderField("Location")
                    ?: return HttpResult(code, ByteArray(0))
                if (redirectCount == MAX_REDIRECTS) throw IOException("Too many metadata redirects")
                currentUrl = URL(currentUrl, location)
                if (code == HttpURLConnection.HTTP_SEE_OTHER && currentMethod != "GET") currentMethod = "GET"
                return@repeat
            }
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val responseBody = stream?.use { readBounded(it, maxBytes) } ?: ByteArray(0)
            return HttpResult(code, responseBody)
        } finally {
            connection.disconnect()
        }
    }
    throw IOException("Too many metadata redirects")
}

private fun readBounded(stream: java.io.InputStream, maxBytes: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream(minOf(maxBytes, 16 * 1024))
    val buffer = ByteArray(8 * 1024)
    var total = 0
    while (true) {
        val read = stream.read(buffer)
        if (read < 0) break
        total += read
        if (total > maxBytes) throw IOException("Metadata response is too large")
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}

private fun loadRemoteThumbnail(url: String?, allowedHosts: Set<String>): Bitmap? {
    if (url.isNullOrBlank()) return null
    return try {
        val response = requestHttp(
            url = URL(url),
            userAgent = "LayerHub/1.0 (Android; com.example.androidtest)",
            allowedHosts = allowedHosts,
            maxBytes = MAX_THUMBNAIL_BYTES
        )
        if (response.statusCode !in 200..299 || response.body.isEmpty()) null
        else BitmapFactory.decodeByteArray(response.body, 0, response.body.size)?.scaleToFit(640, 400)
    } catch (_: IOException) {
        null
    } catch (_: RuntimeException) {
        null
    }
}

private fun isAllowedHost(host: String, allowedHosts: Set<String>): Boolean {
    val normalized = host.lowercase(Locale.ROOT)
    return allowedHosts.any { normalized == it || normalized.endsWith(".$it") }
}

private fun urlEncode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

private fun JSONObject.toValueMap(): Map<String, Any?> = keys().asSequence().associateWith { key ->
    when (val value = opt(key)) {
        JSONObject.NULL -> null
        is JSONObject -> value.toValueMap()
        is JSONArray -> List(value.length()) { index ->
            when (val item = value.opt(index)) {
                JSONObject.NULL -> null
                is JSONObject -> item.toValueMap()
                is JSONArray -> item.toValueList()
                else -> item
            }
        }
        else -> value
    }
}

private fun JSONArray.toValueList(): List<Any?> = List(length()) { index ->
    when (val item = opt(index)) {
        JSONObject.NULL -> null
        is JSONObject -> item.toValueMap()
        is JSONArray -> item.toValueList()
        else -> item
    }
}

private const val MAX_METADATA_BYTES = 1_000_000
private const val MAX_THUMBNAIL_BYTES = 2_000_000
private const val MAX_REDIRECTS = 3
private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
private val REDDIT_THUMBNAIL_HOSTS = setOf("redd.it", "redditmedia.com")

private class DirectMediaUrlResolver : MediaInputResolver {
    private val engine = DownloaderEngine()

    override suspend fun resolve(context: Context, input: ShareInput): MediaResolution? {
        val url = extractSharedUrl(input.text)?.toString() ?: return null
        val adapter = engine.adapterFor(url) ?: return null
        return withContext(Dispatchers.IO) {
            when (val result = adapter.retrieveMetadata(url)) {
                is AdapterResult.Available -> {
                    val metadata = result.value
                    val mediaType = when (metadata.mediaKind) {
                        MediaKind.VIDEO -> MediaType.VIDEO
                        MediaKind.AUDIO -> MediaType.AUDIO
                        MediaKind.UNKNOWN -> MediaType.UNKNOWN
                    }
                    MediaResolution(
                        status = if (mediaType == MediaType.UNKNOWN) ResolutionStatus.UNSUPPORTED else ResolutionStatus.READY,
                        sourceName = "Direct media link",
                        title = metadata.title,
                        mimeType = metadata.mimeType,
                        mediaType = mediaType,
                        durationStatus = if (mediaType == MediaType.VIDEO) VideoDurationStatus.UNKNOWN
                        else VideoDurationStatus.NOT_APPLICABLE,
                        message = when (mediaType) {
                            MediaType.VIDEO -> "The server verified a direct video stream. Its downloaded file will be inspected; videos over 3 minutes will be discarded."
                            MediaType.AUDIO -> "The server verified a direct audio stream. Layer Hub can download the original stream."
                            else -> "The host did not identify this URL as audio or video."
                        },
                        isVerified = mediaType != MediaType.UNKNOWN,
                        canPrepare = mediaType != MediaType.UNKNOWN,
                        remoteDownloadUrl = url
                    )
                }
                is AdapterResult.Unavailable -> MediaResolution(
                    status = result.reason.toResolutionStatus(),
                    sourceName = "Direct media link",
                    message = result.message
                )
            }
        }
    }
}

private fun FailureReason.toResolutionStatus(): ResolutionStatus = when (this) {
    FailureReason.INVALID_URL -> ResolutionStatus.INVALID
    FailureReason.UNSUPPORTED_SOURCE, FailureReason.INVALID_MIME -> ResolutionStatus.UNSUPPORTED
    FailureReason.ACCESS_DENIED -> ResolutionStatus.RESTRICTED
    FailureReason.RATE_LIMITED -> ResolutionStatus.RATE_LIMITED
    FailureReason.NOT_FOUND -> ResolutionStatus.INACCESSIBLE
    FailureReason.METADATA_UNAVAILABLE, FailureReason.HTTP_ERROR, FailureReason.SIZE_LIMIT,
    FailureReason.TIMEOUT, FailureReason.REDIRECT_REJECTED, FailureReason.CANCELLED,
    FailureReason.IO_ERROR -> ResolutionStatus.INACCESSIBLE
}

private fun extractSharedUrl(text: String?): Uri? {
    val candidate = extractHttpUrlCandidate(text) ?: return null
    val normalized = if (candidate.startsWith("www.", ignoreCase = true)) "https://$candidate" else candidate
    val uri = runCatching { Uri.parse(normalized) }.getOrNull() ?: return null
    if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank() || !uri.userInfo.isNullOrEmpty()) {
        return null
    }
    return uri
}

private fun queryDisplayName(resolver: android.content.ContentResolver, uri: Uri): String? =
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (column >= 0) cursor.getString(column) else null
        } else null
    }

private fun mediaTypeForMime(mime: String?): MediaType = when {
    mime == null -> MediaType.UNKNOWN
    mime.startsWith("image/", ignoreCase = true) -> MediaType.IMAGE
    mime.startsWith("video/", ignoreCase = true) -> MediaType.VIDEO
    mime.startsWith("audio/", ignoreCase = true) -> MediaType.AUDIO
    else -> MediaType.UNKNOWN
}

private fun isMediaMime(mime: String): Boolean =
    mime.startsWith("image/") || mime.startsWith("video/") || mime.startsWith("audio/")

private fun readDuration(context: Context, uri: Uri): Long? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(context, uri)
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
    } catch (_: RuntimeException) {
        null
    } finally {
        runCatching { retriever.release() }
    }
}

private fun loadThumbnail(context: Context, uri: Uri, mediaType: MediaType): Bitmap? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        try {
            return context.contentResolver.loadThumbnail(uri, Size(640, 400), null)
        } catch (_: IOException) {
            // Fall back to a best-effort decode for providers without thumbnail support.
        }
    }
    return when (mediaType) {
        MediaType.IMAGE -> decodeSampledImage(context, uri)
        MediaType.VIDEO, MediaType.AUDIO -> {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                if (mediaType == MediaType.AUDIO) {
                    retriever.embeddedPicture?.let { artwork ->
                        BitmapFactory.decodeByteArray(artwork, 0, artwork.size)
                    }
                } else {
                    val frame = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                        retriever.getScaledFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 640, 400)
                    } else {
                        retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    }
                    frame?.scaleToFit(640, 400)
                }
            } catch (_: RuntimeException) {
                null
            } finally {
                runCatching { retriever.release() }
            }
        }
        MediaType.UNKNOWN -> null
    }
}

private fun decodeSampledImage(context: Context, uri: Uri): Bitmap? = try {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    val sampleSize = maxOf(1, maxOf(bounds.outWidth / 640, bounds.outHeight / 400))
    val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
} catch (_: IOException) {
    null
}

private fun Bitmap.scaleToFit(maxWidth: Int, maxHeight: Int): Bitmap {
    val scale = minOf(maxWidth.toFloat() / width, maxHeight.toFloat() / height, 1f)
    if (scale >= 1f) return this
    return Bitmap.createScaledBitmap(this, (width * scale).toInt(), (height * scale).toInt(), true)
}
