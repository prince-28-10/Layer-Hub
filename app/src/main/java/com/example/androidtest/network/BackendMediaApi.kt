package com.example.androidtest.network

import com.example.androidtest.media.MediaAnalysis
import com.example.androidtest.media.MediaAnalysisApi
import com.example.androidtest.media.MediaAnalysisRequest
import com.example.androidtest.media.MediaPreparationRequest
import com.example.androidtest.media.MediaPreparationStatus
import com.example.androidtest.media.PreparedMediaFile
import kotlinx.coroutines.delay

class BackendMediaApi(
    private val apiClient: LayerHubApiClient,
    private val getBaseUrl: () -> String
) : MediaAnalysisApi {

    override suspend fun analyze(request: MediaAnalysisRequest): MediaAnalysis {
        val response = apiClient.analyze(request.originalUrl)

        return MediaAnalysis(
            originalUrl = response.originalUrl,
            detectedPlatform = response.sourcePlatform,
            mediaType = response.mediaType,
            title = response.title,
            thumbnailUrl = response.thumbnailUrl,
            durationSeconds = response.durationSeconds,
            videoQualities = response.videoQualities,
            audioQualities = response.audioQualities,
            status = response.status,
            message = response.message
        )
    }

    override suspend fun prepare(request: MediaPreparationRequest): MediaPreparationStatus {
        val analysis = request.analysis
        val qualityId = request.videoQualityId ?: request.audioQualityId

        val jobId = apiClient.startPreparation(
            originalUrl = analysis.originalUrl,
            mediaType = analysis.mediaType,
            qualityId = qualityId,
            durationSeconds = analysis.durationSeconds
        )

        // Poll job status until completion or failure (up to 60 seconds)
        var attempts = 0
        val maxAttempts = 60
        while (attempts < maxAttempts) {
            attempts++
            delay(1000)
            val job = apiClient.getJobStatus(jobId)
            when (job.status) {
                "COMPLETED" -> {
                    val fullDownloadUrl = if (job.downloadUrl?.startsWith("http") == true) {
                        job.downloadUrl
                    } else {
                        "${getBaseUrl().trimEnd('/')}${job.downloadUrl}"
                    }

                    return MediaPreparationStatus.Complete(
                        PreparedMediaFile(
                            temporaryDownloadUrl = fullDownloadUrl,
                            mimeType = job.mimeType ?: "video/mp4",
                            title = job.title ?: analysis.title
                        )
                    )
                }
                "FAILED" -> {
                    return MediaPreparationStatus.Failed(
                        job.error ?: job.statusMessage ?: "Media preparation failed on the server."
                    )
                }
                else -> {
                    // In progress
                }
            }
        }

        return MediaPreparationStatus.Failed("Media preparation timed out on the server.")
    }
}
