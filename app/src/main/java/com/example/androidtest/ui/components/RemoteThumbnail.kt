package com.example.androidtest.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

private val memoryCache = object : LruCache<String, Bitmap>(30) {}

@Composable
fun RemoteThumbnail(
    url: String?,
    fallbackBitmap: Bitmap? = null,
    mediaTypeLabel: String = "Media",
    modifier: Modifier = Modifier
) {
    var bitmap by remember(url, fallbackBitmap) { mutableStateOf(fallbackBitmap ?: url?.let { memoryCache.get(it) }) }
    var isLoading by remember(url) { mutableStateOf(url != null && bitmap == null) }

    LaunchedEffect(url) {
        if (url.isNullOrBlank() || bitmap != null) {
            isLoading = false
            return@LaunchedEffect
        }
        val cached = memoryCache.get(url)
        if (cached != null) {
            bitmap = cached
            isLoading = false
            return@LaunchedEffect
        }

        isLoading = true
        val downloaded = withContext(Dispatchers.IO) {
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 6000
                    readTimeout = 6000
                    doInput = true
                }
                conn.inputStream.use { input ->
                    BitmapFactory.decodeStream(input)
                }?.also { memoryCache.put(url, it) }
            } catch (_: Exception) {
                null
            }
        }
        bitmap = downloaded
        isLoading = false
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        val currentBitmap = bitmap
        when {
            currentBitmap != null -> {
                Image(
                    bitmap = currentBitmap.asImageBitmap(),
                    contentDescription = "$mediaTypeLabel preview",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            isLoading -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(36.dp),
                    strokeWidth = 3.dp,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            else -> {
                Text(
                    text = mediaTypeLabel.take(1).uppercase(),
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
