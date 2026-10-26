package com.example.androidtest.ui.screens

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun PreparationLoadingScreen(
    statusMessage: String,
    progressPercent: Int? = null,
    onCancel: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(130.dp)
                .scale(pulseScale)
                .background(
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(90.dp)
                    .background(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(54.dp),
                    strokeWidth = 4.dp,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        Spacer(Modifier.height(32.dp))

        Text(
            text = "Preparing Your Media",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(Modifier.height(10.dp))

        Text(
            text = statusMessage.ifBlank { "Processing media on Layer Hub service…" },
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
            modifier = Modifier.padding(horizontal = 24.dp)
        )

        Spacer(Modifier.height(20.dp))

        if (progressPercent != null && progressPercent > 0) {
            Column(
                modifier = Modifier.fillMaxWidth(0.75f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                LinearProgressIndicator(
                    progress = { progressPercent / 100f },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "$progressPercent%",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                )
            }
        }

        Spacer(Modifier.height(30.dp))

        OutlinedButton(
            onClick = onCancel,
            shape = RoundedCornerShape(16.dp)
        ) {
            Text("Cancel Preparation")
        }
    }
}
