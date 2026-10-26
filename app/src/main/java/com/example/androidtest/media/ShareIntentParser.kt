package com.example.androidtest.media

import android.content.Intent
import android.content.ClipData
import android.net.Uri
import androidx.core.content.IntentCompat

fun readShareInput(intent: Intent?): ShareInput {
    if (intent == null || intent.action !in setOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)) {
        return ShareInput()
    }

    var text: String? = null
    val uris = linkedSetOf<Uri>()
    var message: String? = null
    try {
        text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            ?: intent.getStringExtra(Intent.EXTRA_PROCESS_TEXT)
        when (intent.action) {
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(
                intent, Intent.EXTRA_STREAM, Uri::class.java
            )?.let(uris::add)
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(
                intent, Intent.EXTRA_STREAM, Uri::class.java
            )?.filterNotNull()?.forEach(uris::add)
        }
        intent.clipData?.let { clipData: ClipData ->
            for (index in 0 until clipData.itemCount) {
                clipData.getItemAt(index).uri?.let(uris::add)
            }
        }
        if (text.isNullOrBlank()) {
            intent.data?.takeIf { it.scheme.equals("http", true) || it.scheme.equals("https", true) }
                ?.let { text = it.toString() }
        }
    } catch (_: SecurityException) {
        message = "Some shared content could not be accessed. Please share it again with access enabled."
    } catch (_: RuntimeException) {
        message = "The shared content could not be read. Please try sharing it again."
    }

    val mime = try {
        intent.type?.trim()?.lowercase()?.takeIf(String::isNotEmpty)
    } catch (_: RuntimeException) {
        message = "The shared content could not be read. Please try sharing it again."
        null
    }
    val supported = mime == null || mime == "text/plain" || mime.startsWith("image/") ||
        mime.startsWith("video/") || mime.startsWith("audio/")
    if (!supported) message = "This shared file type is not supported yet."
    if (text.isNullOrBlank() && uris.isEmpty() && message == null) {
        message = "No readable text or media was included in the share."
    }
    return ShareInput(text, mime, uris.toList(), message)
}
