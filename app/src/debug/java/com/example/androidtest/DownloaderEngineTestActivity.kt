package com.example.androidtest

import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.androidtest.media.MediaResolution
import com.example.androidtest.media.MediaPreparer
import com.example.androidtest.media.MediaResolverRegistry
import com.example.androidtest.media.ResolutionStatus
import com.example.androidtest.media.MediaType
import com.example.androidtest.media.ShareInput
import com.example.androidtest.media.VideoDurationStatus
import com.layerhub.engine.AdapterResult
import com.layerhub.engine.DirectMediaSourceAdapter
import com.layerhub.engine.DownloadProgress
import com.layerhub.engine.DownloaderEngine
import com.layerhub.engine.FailureReason
import com.layerhub.engine.MediaFormat
import com.layerhub.engine.MediaKind
import com.layerhub.engine.MediaMetadata
import com.layerhub.engine.SourceClassification
import com.layerhub.engine.SourceClassifier
import com.layerhub.engine.SourceKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DownloaderEngineTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    DownloaderEngineTestScreen()
                }
            }
        }
    }
}

private data class EngineInspection(
    val classification: SourceClassification,
    val metadata: MediaMetadata? = null,
    val sharedMetadata: MediaResolution? = null,
    val format: MediaFormat? = null,
    val canDownload: Boolean = false,
    val message: String
)

@Composable
private fun DownloaderEngineTestScreen() {
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    val scope = rememberCoroutineScope()
    val engine = remember { DownloaderEngine() }
    val preparer = remember { MediaPreparer() }
    val mediaResolver = remember { MediaResolverRegistry() }
    var url by remember { mutableStateOf("") }
    var inspection by remember { mutableStateOf<EngineInspection?>(null) }
    var inspecting by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<DownloadProgress?>(null) }
    var downloadJob by remember { mutableStateOf<Job?>(null) }
    var resultMessage by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Downloader engine test", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text("Inspect real metadata and response headers. Only download direct audio/video URLs you are authorized to save; platform extraction is not enabled.")
        OutlinedTextField(
            value = url,
            onValueChange = { url = it; inspection = null; resultMessage = null },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("URL") },
            singleLine = true
        )
        Button(
            onClick = {
                inspecting = true
                downloading = false
                downloadJob?.cancel()
                progress = null
                inspection = null
                resultMessage = null
                scope.launch {
                    inspection = withContext(Dispatchers.IO) {
                        inspectUrl(url, context, engine, mediaResolver)
                    }
                    inspecting = false
                }
            },
            enabled = url.isNotBlank() && !inspecting && !downloading
        ) {
            Text("Inspect URL")
        }

        if (inspecting) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text("Checking the source and its actual response…")
            }
        }

        inspection?.let { checked ->
            InspectionCard(checked)
            if (checked.canDownload && checked.format != null && checked.metadata != null) {
                Text("Format: ${checked.format.label} · ${checked.format.mimeType}")
                Button(
                    onClick = {
                        downloading = true
                        progress = null
                        resultMessage = null
                        val metadata = checked.metadata
                        val mediaType = when (metadata.mediaKind) {
                            MediaKind.VIDEO -> MediaType.VIDEO
                            MediaKind.AUDIO -> MediaType.AUDIO
                            MediaKind.UNKNOWN -> MediaType.UNKNOWN
                        }
                        val resolution = MediaResolution(
                            status = ResolutionStatus.READY,
                            sourceName = "Direct media link",
                            title = metadata.title,
                            mimeType = metadata.mimeType,
                            mediaType = mediaType,
                            durationStatus = if (mediaType == MediaType.VIDEO) VideoDurationStatus.UNKNOWN
                            else VideoDurationStatus.NOT_APPLICABLE,
                            isVerified = true,
                            canPrepare = true,
                            remoteDownloadUrl = url
                        )
                        downloadJob = scope.launch {
                            try {
                                val prepared = preparer.prepare(
                                    context.applicationContext,
                                    ShareInput(text = url),
                                    resolution
                                ) { actualProgress ->
                                    activity?.runOnUiThread { progress = actualProgress }
                                }
                                val file = prepared.files.single()
                                resultMessage = "Downloaded and verified ${file.name} (${formatBytes(file.length())}) to the app cache."
                            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                resultMessage = "Download cancelled; temporary files were removed."
                                throw cancelled
                            } catch (error: Exception) {
                                resultMessage = error.message ?: "The media download could not be completed."
                            } finally {
                                downloading = false
                                downloadJob = null
                            }
                        }
                    },
                    enabled = !downloading
                ) {
                    Text("Download verified original")
                }
            }
        }

        if (downloading) {
            val current = progress
            Text("Downloading actual response bytes…")
            if (current?.fraction != null) {
                LinearProgressIndicator(progress = { current.fraction!!.toFloat() }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Text(current?.let { "${formatBytes(it.downloadedBytes)}${it.totalBytes?.let { total -> " of ${formatBytes(total)}" }.orEmpty()}" } ?: "Waiting for the server response")
            OutlinedButton(onClick = { downloadJob?.cancel() }) { Text("Cancel download") }
        }
        resultMessage?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
private fun InspectionCard(inspection: EngineInspection) {
    val sourceName = sourceLabel(inspection.classification.source)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(sourceName, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
            val shared = inspection.sharedMetadata
            val media = inspection.metadata
            val bitmap: Bitmap? = shared?.thumbnail
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Resolved media thumbnail",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().height(190.dp)
                )
            } else {
                Text("No thumbnail was provided by this source.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                text = media?.title?.takeIf(String::isNotBlank)
                    ?: shared?.title?.takeIf(String::isNotBlank)
                    ?: "No title available",
                fontWeight = FontWeight.Medium
            )
            Text("Media type: ${media?.mediaKind?.displayName() ?: shared?.mediaType?.displayName() ?: "Unverified"}")
            val mime = media?.mimeType ?: shared?.mimeType
            if (!mime.isNullOrBlank()) Text("MIME type: $mime")
            val duration = shared?.durationMs
            if (duration != null) Text("Duration: ${formatDuration(duration)}")
            else if (shared?.mediaType == MediaType.VIDEO || media?.mediaKind == MediaKind.VIDEO) {
                Text("Duration is unavailable; the 3-minute limit has not been checked.")
            }
            media?.sizeBytes?.let { Text("Server-reported size: ${formatBytes(it)}") }
            Text("Source status: ${inspection.capabilityStatus()}")
            Text(inspection.message, color = if (inspection.canDownload) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
        }
    }
}

private suspend fun inspectUrl(
    url: String,
    context: android.content.Context,
    engine: DownloaderEngine,
    mediaResolver: MediaResolverRegistry
): EngineInspection {
    val classification = SourceClassifier.classify(url)
    if (classification.source == SourceKind.INVALID) {
        return EngineInspection(classification, message = "Enter a valid HTTP or HTTPS URL without embedded credentials.")
    }
    val directAdapter = engine.adapterFor(url)
    if (directAdapter != null) {
        return when (val metadata = directAdapter.retrieveMetadata(url)) {
            is AdapterResult.Available -> when (val formats = directAdapter.discoverFormats(url, metadata.value)) {
                is AdapterResult.Available -> EngineInspection(
                    classification = classification,
                    metadata = metadata.value,
                    format = formats.value.firstOrNull(),
                    canDownload = formats.value.isNotEmpty(),
                    message = if (formats.value.isNotEmpty()) {
                        "The server returned an audio/video MIME type. Metadata verification does not claim that third-party source links are downloadable."
                    } else "The server did not provide a downloadable original format."
                )
                is AdapterResult.Unavailable -> EngineInspection(classification, metadata = metadata.value, message = formats.message)
            }
            is AdapterResult.Unavailable -> EngineInspection(classification, message = metadata.message)
        }
    }

    val resolved = mediaResolver.resolve(context, ShareInput(text = url))
    val message = when (classification.source) {
        SourceKind.REDDIT -> if (resolved.status.name == "READY") {
            "Metadata only. Reddit metadata access does not grant media download rights, and no authorized Reddit retrieval adapter is configured."
        } else resolved.message ?: "Reddit metadata is not available."
        SourceKind.YOUTUBE -> if (resolved.status.name == "READY") {
            "Metadata only. YouTube Data API does not provide media files; no authorized YouTube media retrieval adapter is implemented, and prior written approval would be required before considering one."
        } else resolved.message ?: "YouTube metadata is not available."
        SourceKind.INSTAGRAM -> "No general public-post downloader is configured. The official Instagram API is limited to authorized professional-account workflows."
        SourceKind.FACEBOOK -> "No general public-post downloader is configured. Graph API access is limited to authorized content and app permissions."
        SourceKind.X -> "No X media adapter is configured. API access requires a developer app, authorization, and current usage access."
        SourceKind.TELEGRAM -> "No arbitrary-link downloader is configured. Telegram Bot API downloads require an accessible bot file_id; user-chat access needs an authorized client session."
        else -> resolved.message ?: "No metadata or downloader adapter is available for this URL."
    }
    return EngineInspection(classification, sharedMetadata = resolved, message = message)
}

private fun sourceLabel(source: SourceKind): String = when (source) {
    SourceKind.DIRECT_MEDIA -> "Direct media URL"
    SourceKind.REDDIT -> "Reddit · metadata adapter"
    SourceKind.YOUTUBE -> "YouTube · configuration required"
    SourceKind.INSTAGRAM -> "Instagram · unsupported"
    SourceKind.FACEBOOK -> "Facebook · unsupported"
    SourceKind.X -> "X · unsupported"
    SourceKind.TELEGRAM -> "Telegram · unsupported"
    SourceKind.INVALID -> "Invalid URL"
}

private fun EngineInspection.capabilityStatus(): String = when {
    canDownload -> "direct download path verified · original stream"
    classification.source == SourceKind.DIRECT_MEDIA -> "direct download unavailable"
    sharedMetadata?.status == ResolutionStatus.READY -> "metadata available · download unavailable"
    sharedMetadata?.status == ResolutionStatus.NOT_CONFIGURED -> "configuration required · media download unavailable"
    sharedMetadata?.status == ResolutionStatus.RESTRICTED -> "restricted"
    sharedMetadata?.status == ResolutionStatus.RATE_LIMITED -> "rate limited"
    sharedMetadata?.status == ResolutionStatus.UNSUPPORTED -> "unsupported"
    sharedMetadata?.status == ResolutionStatus.INACCESSIBLE -> "unavailable or inaccessible"
    else -> "unsupported or unavailable"
}

private fun MediaType.displayName(): String = when (this) {
    MediaType.IMAGE -> "Image"
    MediaType.VIDEO -> "Video"
    MediaType.AUDIO -> "Audio"
    MediaType.UNKNOWN -> "Unverified"
}

private fun MediaKind.displayName(): String = when (this) {
    MediaKind.VIDEO -> "Video"
    MediaKind.AUDIO -> "Audio"
    MediaKind.UNKNOWN -> "Unverified"
}

private fun formatDuration(durationMs: Long): String = "%d:%02d".format(durationMs / 60_000, durationMs / 1_000 % 60)

private fun formatBytes(bytes: Long): String = if (bytes >= 1024L * 1024L) {
    "%.2f MB".format(bytes / (1024.0 * 1024.0))
} else {
    "%.1f KB".format(bytes / 1024.0)
}
