package com.example.androidtest.network

import com.example.androidtest.media.MediaQualityOption
import com.example.androidtest.media.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

data class BackendAnalysisResponse(
    val sourcePlatform: String,
    val originalUrl: String,
    val title: String?,
    val thumbnailUrl: String?,
    val mediaType: MediaType,
    val durationSeconds: Long?,
    val videoQualities: List<MediaQualityOption>,
    val audioQualities: List<MediaQualityOption>,
    val fileSize: Long?,
    val status: String,
    val message: String?
)

data class BackendJobResponse(
    val jobId: String,
    val status: String, // PENDING, IN_PROGRESS, COMPLETED, FAILED
    val progressPercent: Int,
    val statusMessage: String?,
    val mediaId: String?,
    val downloadUrl: String?,
    val title: String?,
    val mimeType: String?,
    val error: String?
)

class LayerHubApiClient(private val getBaseUrl: () -> String) {

    suspend fun checkHealth(): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = URL("${getBaseUrl()}/api/health")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 4000
                readTimeout = 4000
            }
            conn.responseCode == 200
        } catch (_: Exception) {
            false
        }
    }

    suspend fun analyze(originalUrl: String): BackendAnalysisResponse = withContext(Dispatchers.IO) {
        val url = URL("${getBaseUrl()}/api/media/analyze")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8000
            readTimeout = 12000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }

        val body = JSONObject().apply {
            put("url", originalUrl)
        }.toString()

        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

        val statusCode = conn.responseCode
        val responseText = if (statusCode in 200..299) {
            conn.inputStream.bufferedReader().use { it.readText() }
        } else {
            val error = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            throw IllegalStateException(
                runCatching { JSONObject(error).optString("message") }.getOrNull()
                    ?: "Server returned HTTP $statusCode"
            )
        }

        val json = JSONObject(responseText)
        val sourcePlatform = json.optString("sourcePlatform", "Unknown")
        val mediaTypeStr = json.optString("mediaType", "UNKNOWN")
        val mediaType = when (mediaTypeStr.uppercase()) {
            "VIDEO" -> MediaType.VIDEO
            "AUDIO" -> MediaType.AUDIO
            "IMAGE" -> MediaType.IMAGE
            else -> MediaType.UNKNOWN
        }

        val videoQualities = mutableListOf<MediaQualityOption>()
        json.optJSONArray("videoQualities")?.let { arr ->
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                videoQualities.add(
                    MediaQualityOption(
                        id = obj.getString("id"),
                        label = obj.getString("label"),
                        mimeType = if (obj.has("mimeType") && !obj.isNull("mimeType")) obj.getString("mimeType") else null
                    )
                )
            }
        }

        val audioQualities = mutableListOf<MediaQualityOption>()
        json.optJSONArray("audioQualities")?.let { arr ->
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                audioQualities.add(
                    MediaQualityOption(
                        id = obj.getString("id"),
                        label = obj.getString("label"),
                        mimeType = if (obj.has("mimeType") && !obj.isNull("mimeType")) obj.getString("mimeType") else null
                    )
                )
            }
        }

        BackendAnalysisResponse(
            sourcePlatform = sourcePlatform,
            originalUrl = json.optString("originalUrl", originalUrl),
            title = json.optString("title").takeIf { it.isNotBlank() },
            thumbnailUrl = json.optString("thumbnailUrl").takeIf { it.isNotBlank() },
            mediaType = mediaType,
            durationSeconds = if (json.has("durationSeconds") && !json.isNull("durationSeconds")) json.optLong("durationSeconds") else null,
            videoQualities = videoQualities,
            audioQualities = audioQualities,
            fileSize = if (json.has("fileSize") && !json.isNull("fileSize")) json.optLong("fileSize") else null,
            status = json.optString("status", "SUCCESS"),
            message = json.optString("message").takeIf { it.isNotBlank() }
        )
    }

    suspend fun startPreparation(
        originalUrl: String,
        mediaType: MediaType,
        qualityId: String?,
        durationSeconds: Long?
    ): String = withContext(Dispatchers.IO) {
        val url = URL("${getBaseUrl()}/api/media/prepare")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8000
            readTimeout = 12000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }

        val body = JSONObject().apply {
            put("url", originalUrl)
            put("mediaType", mediaType.name)
            if (qualityId != null) put("qualityId", qualityId)
            if (durationSeconds != null) put("durationSeconds", durationSeconds)
        }.toString()

        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

        val statusCode = conn.responseCode
        val responseText = if (statusCode in 200..299) {
            conn.inputStream.bufferedReader().use { it.readText() }
        } else {
            val error = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            val parsedMsg = runCatching { JSONObject(error).optString("message") }.getOrNull()
            throw IllegalStateException(parsedMsg ?: "Server returned HTTP $statusCode")
        }

        val json = JSONObject(responseText)
        json.getString("jobId")
    }

    suspend fun getJobStatus(jobId: String): BackendJobResponse = withContext(Dispatchers.IO) {
        val url = URL("${getBaseUrl()}/api/media/jobs/$jobId")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 6000
            readTimeout = 6000
        }

        val statusCode = conn.responseCode
        if (statusCode !in 200..299) {
            throw IllegalStateException("Failed to query job status (HTTP $statusCode)")
        }

        val responseText = conn.inputStream.bufferedReader().use { it.readText() }
        val json = JSONObject(responseText)

        BackendJobResponse(
            jobId = json.getString("jobId"),
            status = json.getString("status"),
            progressPercent = json.optInt("progressPercent", 0),
            statusMessage = json.optString("statusMessage").takeIf { it.isNotBlank() },
            mediaId = json.optString("mediaId").takeIf { it.isNotBlank() },
            downloadUrl = json.optString("downloadUrl").takeIf { it.isNotBlank() },
            title = json.optString("title").takeIf { it.isNotBlank() },
            mimeType = json.optString("mimeType").takeIf { it.isNotBlank() },
            error = json.optString("error").takeIf { it.isNotBlank() }
        )
    }

    suspend fun downloadMediaFile(
        mediaId: String,
        destinationFile: File,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val url = URL("${getBaseUrl()}/api/media/download/$mediaId")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10000
            readTimeout = 60000
        }

        if (conn.responseCode !in 200..299) {
            throw IllegalStateException("Download failed with HTTP ${conn.responseCode}")
        }

        val totalLength = conn.contentLengthLong.takeIf { it > 0 }
        val partFile = File(destinationFile.parentFile, "${destinationFile.name}.part")

        var downloaded = 0L
        conn.inputStream.use { input ->
            FileOutputStream(partFile).use { output ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    downloaded += bytesRead
                    onProgress(downloaded, totalLength)
                }
            }
        }

        if (partFile.renameTo(destinationFile)) {
            destinationFile
        } else {
            partFile.copyTo(destinationFile, overwrite = true)
            partFile.delete()
            destinationFile
        }
    }
}
