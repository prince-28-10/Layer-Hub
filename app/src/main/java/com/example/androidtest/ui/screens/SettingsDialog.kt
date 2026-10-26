package com.example.androidtest.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.androidtest.network.LayerHubApiClient
import com.example.androidtest.preferences.AppPreferences
import com.example.androidtest.preferences.Palettes
import com.example.androidtest.preferences.ThemeMode
import kotlinx.coroutines.launch

@Composable
fun SettingsDialog(
    preferences: AppPreferences,
    currentThemeMode: ThemeMode,
    currentPaletteIndex: Int,
    currentBackendUrl: String,
    onDismiss: () -> Unit
) {
    var urlText by remember { mutableStateOf(currentBackendUrl) }
    var testStatus by remember { mutableStateOf<String?>(null) }
    var isTesting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Settings & Customization", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                // 1. Theme Mode
                Text("Appearance Theme", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = currentThemeMode == ThemeMode.SYSTEM,
                        onClick = { preferences.setThemeMode(ThemeMode.SYSTEM) },
                        label = { Text("System") }
                    )
                    FilterChip(
                        selected = currentThemeMode == ThemeMode.LIGHT,
                        onClick = { preferences.setThemeMode(ThemeMode.LIGHT) },
                        label = { Text("Light") }
                    )
                    FilterChip(
                        selected = currentThemeMode == ThemeMode.DARK,
                        onClick = { preferences.setThemeMode(ThemeMode.DARK) },
                        label = { Text("Dark") }
                    )
                }

                // 2. Background Color Palette
                Text("Background Color Palette", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    itemsIndexed(Palettes.choices) { index, palette ->
                        val isSelected = currentPaletteIndex == index
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(if (currentThemeMode == ThemeMode.LIGHT) palette.lightColor else palette.darkColor)
                                .border(
                                    width = if (isSelected) 3.dp else 1.dp,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.5f),
                                    shape = CircleShape
                                )
                                .clickable { preferences.setPaletteIndex(index) },
                            contentAlignment = Alignment.Center
                        ) {
                            if (isSelected) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primary)
                                )
                            }
                        }
                    }
                }
                Text(
                    text = "Selected: ${Palettes.choices[currentPaletteIndex].name}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )

                // 3. Backend Server URL
                Text("Backend Server URL", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                OutlinedTextField(
                    value = urlText,
                    onValueChange = {
                        urlText = it
                        testStatus = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Base URL (e.g. http://10.0.2.2:3000)") },
                    singleLine = true
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            isTesting = true
                            testStatus = null
                            scope.launch {
                                val client = LayerHubApiClient { urlText.trim() }
                                val healthy = client.checkHealth()
                                isTesting = false
                                if (healthy) {
                                    preferences.setBackendUrl(urlText)
                                    testStatus = "Connected successfully!"
                                } else {
                                    testStatus = "Could not reach server. Verify server is running."
                                }
                            }
                        },
                        enabled = !isTesting
                    ) {
                        if (isTesting) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(6.dp))
                        }
                        Text("Test & Save")
                    }
                }

                testStatus?.let { msg ->
                    Text(
                        text = msg,
                        fontSize = 12.sp,
                        color = if (msg.startsWith("Connected")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                preferences.setBackendUrl(urlText)
                onDismiss()
            }) {
                Text("Done")
            }
        }
    )
}
