package com.example.androidtest.media

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.layerhub.engine.AdapterResult
import com.layerhub.engine.DownloadCancellation
import com.layerhub.engine.DownloadProgress
import com.layerhub.engine.DownloaderEngine
import com.layerhub.engine.MediaFormat
import com.layerhub.engine.MediaKind
import com.layerhub.engine.MediaMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

class MediaPreparer(
    private val downloaderEngine: DownloaderEngine = DownloaderEngine(),
    private val api: MediaAnalysisApi = BackendNotConfiguredApi()
) {
    suspend fun prepare(
        context: Context,
        input: ShareInput,
        resolution: MediaResolution,
        videoQualityId: String? = null,
        audioQualityId: String? = null,
        onDownloadProgress: (DownloadProgress) -> Unit = {}
    ): PreparedMedia = withContext(Dispatchers.IO) {
        require(resolution.canPrepare) { "This shared content does not have a supported preparation path." }
        val directory = preparedDirectory(context)
        val analysis = resolution.backendAnalysis
        if (analysis != null) {
            val allowed = when (analysis.mediaType) {
                MediaType.VIDEO -> analysis.videoQualities.any { it.id == videoQualityId } && analysis.audioQualities.any { it.id == audioQualityId }
                MediaType.AUDIO -> analysis.audioQualities.any { it.id == audioQualityId }
                MediaType.IMAGE -> true
                MediaType.UNKNOWN -> false
            }
            require(allowed) { "Select an available media quality before preparing." }
            when (val result = api.prepare(MediaPreparationRequest(analysis, videoQualityId, audioQualityId))) {
                is MediaPreparationStatus.InProgress -> throw IOException("Preparation is still in progress. Try again shortly.")
                is MediaPreparationStatus.Failed -> throw IOException(result.message.ifBlank { "Media could not be prepared." })
                is MediaPreparationStatus.Complete -> return@withContext materializeBackendResult(
                    result.file, directory, onDownloadProgress
                )
            }
        }
        val remoteUrl = resolution.remoteDownloadUrl
        if (remoteUrl != null) {
            return@withContext runInterruptible {
                downloadDirectMedia(remoteUrl, resolution, directory, onDownloadProgress)
            }
        }
        require(input.uris.isNotEmpty()) { "This shared content does not have a supported preparation path." }

        val files = mutableListOf<File>()
        try {
            input.uris.forEachIndexed { index, uri ->
                coroutineContext.ensureActive()
                val name = safeName(resolution.title, resolution.mimeType, index)
                val destination = File(directory, "${System.currentTimeMillis()}-${index + 1}-$name")
                val temporary = File(directory, "${destination.name}.part")
                try {
                    copySharedUri(context, uri, temporary)
                    if (!temporary.renameTo(destination)) {
                        throw IOException("Could not finalize the prepared media file.")
                    }
                    files += destination
                } finally {
                    temporary.delete()
                }
            }
            if (files.isEmpty()) throw IOException("No media files were prepared.")
            PreparedMedia(
                files = files,
                mimeType = resolution.mimeType ?: input.mimeType ?: "application/octet-stream",
                title = resolution.title
            )
        } catch (error: Exception) {
            files.forEach(File::delete)
            throw error
        }
    }

    private suspend fun materializeBackendResult(
        result: PreparedMediaFile,
        directory: File,
        onProgress: (DownloadProgress) -> Unit
    ): PreparedMedia {
        val local = result.localFilePath?.let(::File)
        if (local != null && local.isFile && local.length() > 0L) {
            return PreparedMedia(listOf(local), result.mimeType, result.title)
        }
        val temporaryUrl = result.temporaryDownloadUrl
            ?: throw IOException("The service returned no usable prepared media file.")
        val parsed = runCatching { URL(temporaryUrl) }.getOrNull()
            ?: throw IOException("The service returned an invalid temporary media URL.")
        if (parsed.protocol != "https" && parsed.protocol != "http") throw IOException("The temporary media URL must use HTTP or HTTPS.")
        val destination = File(directory, "${System.currentTimeMillis()}-${safeName(result.title, result.mimeType, 0)}")
        val temporary = File(directory, "${destination.name}.part")
        val connection = parsed.openConnection() as? HttpURLConnection
            ?: throw IOException("The temporary media URL is not downloadable.")
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = false
            connection.connect()
            if (connection.responseCode !in 200..299) {
                if (connection.responseCode == 403 || connection.responseCode == 404) {
                    throw IOException("The temporary media link expired. Prepare the media again.")
                }
                throw IOException("The prepared media could not be downloaded from the service.")
            }
            val total = connection.contentLengthLong.takeIf { it >= 0L }
            connection.inputStream.use { source ->
                temporary.outputStream().buffered().use { sink ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var count = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = source.read(buffer)
                        if (read < 0) break
                        sink.write(buffer, 0, read)
                        count += read
                        onProgress(DownloadProgress(count, total))
                    }
                }
            }
            if (temporary.length() <= 0L) throw IOException("The prepared media file was empty.")
            val actualMime = connection.contentType?.substringBefore(';')?.trim()
            if (actualMime != null && actualMime != result.mimeType && actualMime != "application/octet-stream") {
                throw IOException("The service returned a file with an unexpected type.")
            }
            if (!temporary.renameTo(destination)) throw IOException("The prepared media could not be finalized.")
            return PreparedMedia(listOf(destination), result.mimeType, result.title)
        } finally {
            connection.disconnect()
            temporary.delete()
        }
    }

    private fun downloadDirectMedia(
        url: String,
        resolution: MediaResolution,
        directory: File,
        onProgress: (DownloadProgress) -> Unit
    ): PreparedMedia {
        val adapter = downloaderEngine.adapterFor(url)
            ?: throw IOException("No authorized direct-media downloader is available for this URL.")
        val metadata = available(adapter.retrieveMetadata(url), "The media response could not be verified.")
        val formats = available(adapter.discoverFormats(url, metadata), "The server did not provide a supported original format.")
        val format = formats.firstOrNull()
            ?: throw IOException("The server did not provide a supported original format.")
        if (resolution.mimeType != metadata.mimeType || !matchesResolvedKind(resolution, metadata)) {
            throw IOException("The media response changed after preview. Inspect the URL again before downloading.")
        }

        val cancellation = DownloadCancellation()
        val downloaded = available(
            adapter.retrieveMedia(url, format, directory, cancellation, onProgress),
            "The media download could not be completed."
        )
        try {
            if (Thread.currentThread().isInterrupted) {
                throw IOException("The media download was cancelled.")
            }
            if (!downloaded.file.isFile || downloaded.sizeBytes <= 0L || downloaded.file.length() != downloaded.sizeBytes) {
                throw IOException("The downloaded file did not pass size verification.")
            }
            if (metadata.mediaKind == MediaKind.VIDEO) {
                val durationMs = readDuration(downloaded.file)
                directVideoDurationFailure(durationMs)?.let { throw IOException(it) }
            }
            if (Thread.currentThread().isInterrupted) {
                throw IOException("The media download was cancelled.")
            }
            return PreparedMedia(
                files = listOf(downloaded.file),
                mimeType = downloaded.mimeType,
                title = metadata.title ?: resolution.title
            )
        } catch (error: Exception) {
            downloaded.file.delete()
            throw error
        }
    }

    private fun matchesResolvedKind(resolution: MediaResolution, metadata: MediaMetadata): Boolean = when (metadata.mediaKind) {
        MediaKind.VIDEO -> resolution.mediaType == MediaType.VIDEO
        MediaKind.AUDIO -> resolution.mediaType == MediaType.AUDIO
        MediaKind.UNKNOWN -> false
    }

    private fun <T> available(result: AdapterResult<T>, fallbackMessage: String): T = when (result) {
        is AdapterResult.Available -> result.value
        is AdapterResult.Unavailable -> throw IOException(result.message.ifBlank { fallbackMessage })
    }

    private fun preparedDirectory(context: Context): File = File(context.cacheDir, "layerhub-prepared").apply {
        if (!exists() && !mkdirs()) throw IOException("Could not create temporary media storage.")
        if (!isDirectory) throw IOException("The temporary media path is not a folder.")
    }

    private suspend fun copySharedUri(context: Context, uri: Uri, destination: File) {
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: throw IOException("The shared file could not be opened.")
        inputStream.use { source ->
            destination.outputStream().buffered().use { sink ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    coroutineContext.ensureActive()
                    val read = source.read(buffer)
                    if (read < 0) break
                    sink.write(buffer, 0, read)
                }
            }
        }
        if (destination.length() == 0L) throw IOException("The shared file was empty.")
    }

    private fun safeName(title: String?, mimeType: String?, index: Int): String {
        val fallbackExtension = when {
            mimeType?.startsWith("image/") == true -> ".img"
            mimeType?.startsWith("video/") == true -> ".video"
            mimeType?.startsWith("audio/") == true -> ".audio"
            else -> ".bin"
        }
        val candidate = title.orEmpty().substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .trim('.', '_')
            .take(80)
            .ifBlank { "shared-media-${index + 1}$fallbackExtension" }
        return if (candidate.contains('.')) candidate else "$candidate$fallbackExtension"
    }

    private fun readDuration(file: File): Long? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } catch (_: RuntimeException) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }
}
