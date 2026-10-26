package com.example.androidtest.media

import android.graphics.Bitmap
import android.net.Uri

data class ShareInput(
    val text: String? = null,
    val mimeType: String? = null,
    val uris: List<Uri> = emptyList(),
    val message: String? = null
) {
    val hasContent: Boolean get() = !text.isNullOrBlank() || uris.isNotEmpty()
}

enum class MediaType { IMAGE, VIDEO, AUDIO, UNKNOWN }

enum class ResolutionStatus { READY, NOT_CONFIGURED, UNSUPPORTED, INACCESSIBLE, RESTRICTED, RATE_LIMITED, INVALID }

enum class VideoDurationStatus { NOT_APPLICABLE, WITHIN_LIMIT, EXCEEDS_LIMIT, UNKNOWN }

data class MediaResolution(
    val status: ResolutionStatus,
    val sourceName: String,
    val title: String? = null,
    val mimeType: String? = null,
    val mediaType: MediaType = MediaType.UNKNOWN,
    val durationMs: Long? = null,
    val durationStatus: VideoDurationStatus = VideoDurationStatus.NOT_APPLICABLE,
    val thumbnail: Bitmap? = null,
    val uris: List<Uri> = emptyList(),
    val message: String? = null,
    val isVerified: Boolean = false,
    val canPrepare: Boolean = false,
    /** Original public direct-media URL, when the engine has verified an audio/video response. */
    val remoteDownloadUrl: String? = null,
    val videoQualities: List<MediaQualityOption> = emptyList(),
    val audioQualities: List<MediaQualityOption> = emptyList(),
    val backendAnalysis: MediaAnalysis? = null
)

internal fun MediaResolution.capabilityLabel(): String = when {
    canPrepare && remoteDownloadUrl != null -> "Direct download path verified"
    canPrepare -> "Shared media preparation available"
    status == ResolutionStatus.READY -> "Metadata available · no download path"
    status == ResolutionStatus.NOT_CONFIGURED -> "Configuration required"
    status == ResolutionStatus.RESTRICTED -> "Restricted or access denied"
    status == ResolutionStatus.RATE_LIMITED -> "Rate limited"
    status == ResolutionStatus.UNSUPPORTED -> "Unsupported"
    status == ResolutionStatus.INACCESSIBLE -> "Unavailable"
    else -> "Invalid input"
}

internal fun directVideoDurationFailure(durationMs: Long?): String? = when {
    durationMs == null || durationMs <= 0L ->
        "Layer Hub could not verify this video's duration, so the file was discarded."
    durationMs > MAX_VIDEO_DURATION_MS ->
        "This video exceeds Layer Hub's 3-minute limit and was discarded."
    else -> null
}

data class PreparedMedia(
    val files: List<java.io.File>,
    val mimeType: String,
    val title: String?
)

const val MAX_VIDEO_DURATION_MS = 3 * 60 * 1000L
