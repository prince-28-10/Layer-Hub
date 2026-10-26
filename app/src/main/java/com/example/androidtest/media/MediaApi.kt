package com.example.androidtest.media

import com.example.androidtest.BuildConfig

/** Contracts for the future media service. No endpoint or downloader is assumed here. */
data class MediaAnalysisRequest(val originalUrl: String)

data class MediaQualityOption(val id: String, val label: String, val mimeType: String? = null)

data class MediaAnalysis(
    val originalUrl: String,
    val detectedPlatform: String?,
    val mediaType: MediaType,
    val title: String?,
    val thumbnailUrl: String?,
    val durationSeconds: Long?,
    val videoQualities: List<MediaQualityOption> = emptyList(),
    val audioQualities: List<MediaQualityOption> = emptyList(),
    val status: String? = null,
    val message: String? = null
)

data class MediaPreparationRequest(
    val analysis: MediaAnalysis,
    val videoQualityId: String? = null,
    val audioQualityId: String? = null
)

sealed interface MediaPreparationStatus {
    data class InProgress(val progressPercent: Int? = null) : MediaPreparationStatus
    data class Complete(val file: PreparedMediaFile) : MediaPreparationStatus
    data class Failed(val message: String) : MediaPreparationStatus
}

data class PreparedMediaFile(
    val localFilePath: String? = null,
    val temporaryDownloadUrl: String? = null,
    val mimeType: String,
    val title: String?
)

interface MediaAnalysisApi {
    suspend fun analyze(request: MediaAnalysisRequest): MediaAnalysis
    suspend fun prepare(request: MediaPreparationRequest): MediaPreparationStatus
}

data class MediaApiConfiguration(val baseUrl: String) {
    companion object {
        fun fromBuildConfiguration() = MediaApiConfiguration(BuildConfig.LAYER_HUB_API_BASE_URL)
    }
}

/** Safe default until an API implementation and base URL are provided. */
class BackendNotConfiguredApi : MediaAnalysisApi {
    override suspend fun analyze(request: MediaAnalysisRequest): MediaAnalysis =
        throw IllegalStateException("Link analysis is not connected yet. Choose a media file shared from your device.")

    override suspend fun prepare(request: MediaPreparationRequest): MediaPreparationStatus =
        MediaPreparationStatus.Failed("Media preparation service is not connected yet.")
}

fun isDurationAllowed(type: MediaType, seconds: Long?): Boolean = when (type) {
    MediaType.IMAGE -> true
    MediaType.VIDEO, MediaType.AUDIO -> seconds != null && seconds in 1..180
    MediaType.UNKNOWN -> false
}
