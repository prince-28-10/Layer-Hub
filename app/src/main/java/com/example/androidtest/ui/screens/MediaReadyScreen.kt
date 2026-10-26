package com.example.androidtest.ui.screens

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import android.widget.ImageView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import com.example.androidtest.media.PreparedMedia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

data class SharingTarget(
    val packageName: String,
    val activityName: String,
    val label: String,
    val icon: Drawable
)

@Composable
fun MediaReadyScreen(
    prepared: PreparedMedia,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var isSaving by remember { mutableStateOf(false) }

    val primaryFile = prepared.files.firstOrNull()

    // File saver launcher
    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(prepared.mimeType)
    ) { uri ->
        if (uri != null && primaryFile != null) {
            isSaving = true
            statusMessage = null
            scope.launch {
                statusMessage = try {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.use { output ->
                            FileInputStream(primaryFile).use { input -> input.copyTo(output) }
                        } ?: error("Failed to open destination output stream")
                    }
                    "Media successfully saved to device!"
                } catch (e: Exception) {
                    "Save failed: ${e.message}"
                }
                isSaving = false
            }
        }
    }

    // Discover installed sharing targets
    val installedTargets = remember(prepared) {
        discoverSharingTargets(context, prepared)
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            "Media Ready",
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )

        // Summary Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = prepared.title ?: primaryFile?.name ?: "Prepared Media",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "Type: ${prepared.mimeType}",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                primaryFile?.let {
                    Text(
                        text = "Size: ${formatFileSize(it.length())}",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
            }
        }

        // Action A: Save to Device
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Save to Device", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Button(
                    onClick = {
                        val fileName = primaryFile?.name ?: "media_${System.currentTimeMillis()}"
                        saveLauncher.launch(fileName)
                    },
                    enabled = !isSaving && primaryFile != null,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(if (isSaving) "Saving…" else "Save Media", fontWeight = FontWeight.SemiBold)
                }
            }
        }

        // Action B: Send / Share Media
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Send Media via App", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)

                if (installedTargets.isNotEmpty()) {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(installedTargets) { target ->
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .width(72.dp)
                                    .clickable {
                                        launchTargetShare(context, prepared, target)
                                    }
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.surfaceVariant),
                                    contentAlignment = Alignment.Center
                                ) {
                                    AndroidView(
                                        factory = { ctx ->
                                            ImageView(ctx).apply {
                                                setImageDrawable(target.icon)
                                                scaleType = ImageView.ScaleType.FIT_CENTER
                                            }
                                        },
                                        modifier = Modifier.size(32.dp)
                                    )
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = target.label,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }

                OutlinedButton(
                    onClick = {
                        try {
                            launchNativeSharesheet(context, prepared)
                        } catch (e: Exception) {
                            statusMessage = "Could not open Sharesheet: ${e.message}"
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("Android Sharesheet / All Apps")
                }
            }
        }

        statusMessage?.let { msg ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Text(
                    text = msg,
                    modifier = Modifier.padding(14.dp),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontSize = 13.sp
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        OutlinedButton(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(14.dp)
        ) {
            Text("Done / Retrieve Another")
        }
    }
}

private fun discoverSharingTargets(context: Context, prepared: PreparedMedia): List<SharingTarget> {
    val pm = context.packageManager
    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = prepared.mimeType
    }

    val activities = pm.queryIntentActivities(shareIntent, PackageManager.MATCH_DEFAULT_ONLY)
    val knownPopularApps = setOf(
        "com.whatsapp",
        "org.telegram.messenger",
        "com.instagram.android",
        "com.facebook.katana",
        "com.twitter.android",
        "com.google.android.gm"
    )

    return activities
        .mapNotNull { resolveInfo ->
            val packageName = resolveInfo.activityInfo.packageName
            if (packageName == context.packageName) return@mapNotNull null
            val label = resolveInfo.loadLabel(pm).toString()
            val icon = resolveInfo.loadIcon(pm)
            SharingTarget(
                packageName = packageName,
                activityName = resolveInfo.activityInfo.name,
                label = label,
                icon = icon
            )
        }
        .sortedByDescending { knownPopularApps.contains(it.packageName) }
        .take(8)
}

private fun launchTargetShare(context: Context, prepared: PreparedMedia, target: SharingTarget) {
    val file = prepared.files.firstOrNull() ?: return
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    val intent = Intent(Intent.ACTION_SEND).apply {
        type = prepared.mimeType
        setClassName(target.packageName, target.activityName)
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
}

private fun launchNativeSharesheet(context: Context, prepared: PreparedMedia) {
    val uris = prepared.files.map { file ->
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }
    val outgoing = if (uris.size > 1) Intent(Intent.ACTION_SEND_MULTIPLE) else Intent(Intent.ACTION_SEND)
    outgoing.type = prepared.mimeType
    outgoing.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    if (uris.size > 1) {
        outgoing.putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
    } else {
        outgoing.putExtra(Intent.EXTRA_STREAM, uris.first())
    }
    val clip = ClipData.newUri(context.contentResolver, "Prepared media", uris.first())
    uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
    outgoing.clipData = clip
    outgoing.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    val chooser = Intent.createChooser(outgoing, "Share prepared media").apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(chooser)
}

private fun formatFileSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> "%.2f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes bytes"
}
