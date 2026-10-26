package com.example.androidtest.ui.screens

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun EmptyStateScreen(
    isAnalyzing: Boolean,
    errorMessage: String?,
    onAnalyzeUrl: (String) -> Unit
) {
    var inputUrl by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(Modifier.height(10.dp))

        // Hero icon
        Box(
            modifier = Modifier
                .size(72.dp)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "L",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 36.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Text(
            "Layer Hub",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )

        Text(
            "Unified media retrieval and sharing across YouTube, Reddit, Instagram, X, Facebook, and direct media.",
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        Spacer(Modifier.height(8.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    "Retrieve from Link",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold
                )

                OutlinedTextField(
                    value = inputUrl,
                    onValueChange = { inputUrl = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Paste media link…") },
                    placeholder = { Text("https://...") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp)
                )

                Button(
                    onClick = {
                        if (inputUrl.isNotBlank() && !isAnalyzing) {
                            onAnalyzeUrl(inputUrl.trim())
                        }
                    },
                    enabled = inputUrl.isNotBlank() && !isAnalyzing,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    if (isAnalyzing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = Color.White
                        )
                        Spacer(Modifier.width(10.dp))
                        Text("Analyzing…")
                    } else {
                        Text("Analyze Media", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        errorMessage?.let { error ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            ) {
                Text(
                    text = error,
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    fontSize = 13.sp
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        // Sharing hint card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("💡", fontSize = 22.sp)
                Spacer(Modifier.width(12.dp))
                Text(
                    "Tip: You can also tap Share in any app (e.g. YouTube, Reddit, Gallery) and select Layer Hub to retrieve media instantly.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                )
            }
        }
    }
}
