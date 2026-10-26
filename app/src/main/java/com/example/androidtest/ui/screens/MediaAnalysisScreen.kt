package com.example.androidtest.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.androidtest.media.MediaQualityOption
import com.example.androidtest.media.MediaType
import com.example.androidtest.network.BackendAnalysisResponse
import com.example.androidtest.ui.components.RemoteThumbnail

@Composable
fun MediaAnalysisScreen(
    analysis: BackendAnalysisResponse,
    localThumbnail: Bitmap? = null,
    onPrepare: (selectedOutput: String, selectedQualityId: String?) -> Unit,
    onBack: () -> Unit
) {
    var outputType by remember { mutableStateOf(if (analysis.mediaType == MediaType.AUDIO) "Audio" else "Video") }
    var selectedVideoQualityId by remember {
        mutableStateOf(analysis.videoQualities.firstOrNull()?.id ?: "original")
    }
    var selectedAudioQualityId by remember {
        mutableStateOf(analysis.audioQualities.firstOrNull()?.id ?: "original_audio")
    }

    // 180s strict check
    val isVideo = analysis.mediaType == MediaType.VIDEO
    val durationSeconds = analysis.durationSeconds
    val exceedsThreeMinutes = isVideo && durationSeconds != null && durationSeconds > 180L
    val unknownVideoDuration = isVideo && durationSeconds == null
    val isSuccessStatus = analysis.status == "SUCCESS"
    val hasSupportedMediaType = analysis.mediaType == MediaType.IMAGE || isVideo || analysis.mediaType == MediaType.AUDIO
    val canProceed = !exceedsThreeMinutes && !unknownVideoDuration && isSuccessStatus && hasSupportedMediaType

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Media Confirmation",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            // Source badge
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Text(
                    text = analysis.sourcePlatform,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp
                )
            }
        }

        // Large Preview Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                RemoteThumbnail(
                    url = analysis.thumbnailUrl,
                    fallbackBitmap = localThumbnail,
                    mediaTypeLabel = analysis.mediaType.name,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                )

                Text(
                    text = analysis.title ?: "Untitled Media",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Type: ${analysis.mediaType.name}",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                    if (durationSeconds != null) {
                        Text(
                            text = "Duration: ${formatSeconds(durationSeconds)}",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (exceedsThreeMinutes) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }

        // Duration Check Warning if applicable
        if (exceedsThreeMinutes) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("⚠️ 3-Minute Limit Exceeded", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                    Text(
                        "Video exceeds the 3-minute limit. Please choose a video that is 180 seconds or shorter.",
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        fontSize = 13.sp
                    )
                }
            }
        } else if (unknownVideoDuration) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f))
            ) {
                Text(
                    "Video duration could not be verified in advance. Layer Hub requires duration verification (max 180s).",
                    modifier = Modifier.padding(14.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    fontSize = 13.sp
                )
            }
        }

        if (!analysis.message.isNullOrBlank() && !exceedsThreeMinutes) {
            val isError = analysis.status in setOf("ERROR", "INVALID", "RESTRICTED", "RATE_LIMITED", "NOT_CONFIGURED", "UNSUPPORTED")
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isError)
                        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
                    else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                )
            ) {
                Text(
                    text = analysis.message,
                    modifier = Modifier.padding(14.dp),
                    color = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer,
                    fontSize = 13.sp
                )
            }
        }

        // Quality and Format options (skipped for IMAGE)
        if (analysis.mediaType != MediaType.IMAGE && canProceed) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (isVideo) {
                        Text("Output Format", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = outputType == "Video",
                                onClick = { outputType = "Video" },
                                label = { Text("Video") }
                            )
                            FilterChip(
                                selected = outputType == "Audio",
                                onClick = { outputType = "Audio" },
                                label = { Text("Audio Only") }
                            )
                        }
                    }

                    if (outputType == "Video" && analysis.videoQualities.isNotEmpty()) {
                        Text("Available Video Quality", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            analysis.videoQualities.forEach { quality ->
                                FilterChip(
                                    selected = selectedVideoQualityId == quality.id,
                                    onClick = { selectedVideoQualityId = quality.id },
                                    label = { Text(quality.label) }
                                )
                            }
                        }
                    } else if (outputType == "Audio" && analysis.audioQualities.isNotEmpty()) {
                        Text("Available Audio Quality", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            analysis.audioQualities.forEach { quality ->
                                FilterChip(
                                    selected = selectedAudioQualityId == quality.id,
                                    onClick = { selectedAudioQualityId = quality.id },
                                    label = { Text(quality.label) }
                                )
                            }
                        }
                    }
                }
            }
        }

        // Prepare Media primary action button
        val buttonLabel = when {
            canProceed -> "Prepare Media"
            exceedsThreeMinutes -> "Exceeds 180s Limit"
            unknownVideoDuration -> "Duration Verification Required"
            analysis.status == "NOT_CONFIGURED" -> "Configuration Required"
            analysis.status == "UNSUPPORTED" -> "Preparation Unsupported"
            analysis.mediaType == MediaType.UNKNOWN -> "Media Stream Unavailable"
            else -> "Media Preparation Unavailable"
        }

        Button(
            onClick = {
                val chosenQuality = if (outputType == "Audio") selectedAudioQualityId else selectedVideoQualityId
                onPrepare(outputType, chosenQuality)
            },
            enabled = canProceed,
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary
            )
        ) {
            Text(buttonLabel, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }

        OutlinedButton(
            onClick = onBack,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text("Cancel / Change Media")
        }
    }
}

private fun formatSeconds(seconds: Long): String {
    val m = seconds / 60
    val s = seconds % 60
    return "%d:%02d".format(m, s)
}
