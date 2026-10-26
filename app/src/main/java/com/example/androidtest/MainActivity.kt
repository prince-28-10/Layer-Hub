package com.example.androidtest

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.androidtest.media.MediaAnalysis
import com.example.androidtest.media.MediaAnalysisRequest
import com.example.androidtest.media.MediaPreparer
import com.example.androidtest.media.MediaResolution
import com.example.androidtest.media.MediaResolverRegistry
import com.example.androidtest.media.MediaType
import com.example.androidtest.media.PreparedMedia
import com.example.androidtest.media.ResolutionStatus
import com.example.androidtest.media.ShareInput
import com.example.androidtest.media.readShareInput
import com.example.androidtest.network.BackendAnalysisResponse
import com.example.androidtest.network.BackendMediaApi
import com.example.androidtest.network.LayerHubApiClient
import com.example.androidtest.preferences.AppPreferences
import com.example.androidtest.preferences.Palettes
import com.example.androidtest.preferences.ThemeMode
import com.example.androidtest.ui.components.TopBar
import com.example.androidtest.ui.screens.EmptyStateScreen
import com.example.androidtest.ui.screens.MediaAnalysisScreen
import com.example.androidtest.ui.screens.MediaReadyScreen
import com.example.androidtest.ui.screens.PreparationLoadingScreen
import com.example.androidtest.ui.screens.SettingsDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private enum class HubStage {
    EMPTY,
    CONFIRMATION,
    PREPARING,
    READY
}

class MainActivity : ComponentActivity() {
    private val shareInputState = mutableStateOf(ShareInput())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        shareInputState.value = readShareInput(intent)
        setContent {
            LayerHubApp(shareInput = shareInputState.value)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        shareInputState.value = readShareInput(intent)
    }
}

@Composable
fun LayerHubApp(shareInput: ShareInput) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val preferences = remember { AppPreferences(context.applicationContext) }
    val themeMode by preferences.themeMode.collectAsState()
    val paletteIndex by preferences.paletteIndex.collectAsState()
    val backendUrl by preferences.backendUrl.collectAsState()

    val apiClient = remember(backendUrl) {
        LayerHubApiClient { preferences.backendUrl.value }
    }
    val backendMediaApi = remember(apiClient) {
        BackendMediaApi(apiClient) { preferences.backendUrl.value }
    }
    val resolver = remember(backendMediaApi) {
        MediaResolverRegistry(backendApi = backendMediaApi)
    }
    val preparer = remember(backendMediaApi) {
        MediaPreparer(api = backendMediaApi)
    }

    // Dynamic theming
    val systemDark = isSystemInDarkTheme()
    val isDark = when (themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val selectedPalette = Palettes.choices.getOrElse(paletteIndex) { Palettes.choices[0] }
    val backgroundColor = if (isDark) selectedPalette.darkColor else selectedPalette.lightColor
    val primaryAccent = Color(0xFF39BFA3)

    val colorScheme = if (isDark) {
        darkColorScheme(
            primary = primaryAccent,
            secondary = primaryAccent,
            background = backgroundColor,
            surface = Color(0xFF1B2530),
            surfaceVariant = Color(0xFF243242),
            onBackground = Color(0xFFF2F5F7),
            onSurface = Color(0xFFF2F5F7)
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF087F70),
            secondary = Color(0xFF087F70),
            background = backgroundColor,
            surface = Color.White,
            surfaceVariant = Color(0xFFE6EFEA),
            onBackground = Color(0xFF17211F),
            onSurface = Color(0xFF17211F)
        )
    }

    // App Navigation & Workflow State
    var stage by remember { mutableStateOf(HubStage.EMPTY) }
    var activeInput by remember { mutableStateOf(shareInput) }
    var analysisResponse by remember { mutableStateOf<BackendAnalysisResponse?>(null) }
    var currentResolution by remember { mutableStateOf<MediaResolution?>(null) }
    var preparedMedia by remember { mutableStateOf<PreparedMedia?>(null) }

    var isAnalyzing by remember { mutableStateOf(false) }
    var preparationStatusMessage by remember { mutableStateOf("Preparing your media…") }
    var preparationProgress by remember { mutableStateOf<Int?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showSettingsDialog by remember { mutableStateOf(false) }

    // React to Incoming Shared Content
    LaunchedEffect(shareInput) {
        activeInput = shareInput
        if (shareInput.hasContent) {
            errorMessage = null
            isAnalyzing = true
            stage = HubStage.EMPTY

            try {
                // Try resolving via registry
                val result = resolver.resolve(context.applicationContext, shareInput)
                currentResolution = result

                if (result.backendAnalysis != null) {
                    val ba = result.backendAnalysis
                    analysisResponse = BackendAnalysisResponse(
                        sourcePlatform = ba.detectedPlatform ?: result.sourceName,
                        originalUrl = ba.originalUrl,
                        title = ba.title ?: result.title,
                        thumbnailUrl = ba.thumbnailUrl,
                        mediaType = ba.mediaType,
                        durationSeconds = ba.durationSeconds,
                        videoQualities = ba.videoQualities,
                        audioQualities = ba.audioQualities,
                        fileSize = null,
                        status = ba.status ?: (if (result.status == ResolutionStatus.READY) "SUCCESS" else result.status.name),
                        message = result.message
                    )
                    stage = HubStage.CONFIRMATION
                } else if (result.status != ResolutionStatus.INVALID && result.mediaType != MediaType.UNKNOWN) {
                    // Local shared media file
                    analysisResponse = BackendAnalysisResponse(
                        sourcePlatform = result.sourceName,
                        originalUrl = shareInput.text ?: "",
                        title = result.title ?: "Shared file",
                        thumbnailUrl = null,
                        mediaType = result.mediaType,
                        durationSeconds = result.durationMs?.let { it / 1000 },
                        videoQualities = result.videoQualities,
                        audioQualities = result.audioQualities,
                        fileSize = null,
                        status = if (result.status == ResolutionStatus.READY) "SUCCESS" else result.status.name,
                        message = result.message
                    )
                    stage = HubStage.CONFIRMATION
                } else {
                    errorMessage = result.message ?: "Could not inspect the shared content."
                }
            } catch (e: Exception) {
                errorMessage = e.message ?: "Failed to process shared media."
            } finally {
                isAnalyzing = false
            }
        } else {
            stage = HubStage.EMPTY
        }
    }

    MaterialTheme(colorScheme = colorScheme) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 22.dp, vertical = 16.dp)
            ) {
                TopBar(
                    subtitle = when (stage) {
                        HubStage.EMPTY -> "Share link or file"
                        HubStage.CONFIRMATION -> "Review & select options"
                        HubStage.PREPARING -> "Retrieving media"
                        HubStage.READY -> "Ready to save or share"
                    },
                    onOpenSettings = { showSettingsDialog = true }
                )

                Spacer(Modifier.height(16.dp))

                when (stage) {
                    HubStage.EMPTY -> {
                        EmptyStateScreen(
                            isAnalyzing = isAnalyzing,
                            errorMessage = errorMessage,
                            onAnalyzeUrl = { rawUrl ->
                                errorMessage = null
                                isAnalyzing = true
                                scope.launch {
                                    try {
                                        val resp = apiClient.analyze(rawUrl)
                                        analysisResponse = resp
                                        if (resp.status == "ERROR") {
                                            errorMessage = resp.message ?: "Unsupported or unavailable media link"
                                        } else {
                                            activeInput = ShareInput(text = rawUrl)
                                            stage = HubStage.CONFIRMATION
                                        }
                                    } catch (e: Exception) {
                                        errorMessage = "Backend connection failed: ${e.message}. Check backend URL in Settings."
                                    } finally {
                                        isAnalyzing = false
                                    }
                                }
                            }
                        )
                    }

                    HubStage.CONFIRMATION -> {
                        val resp = analysisResponse
                        if (resp != null) {
                            MediaAnalysisScreen(
                                analysis = resp,
                                localThumbnail = currentResolution?.thumbnail,
                                onPrepare = { outputChoice, qualityId ->
                                    stage = HubStage.PREPARING
                                    preparationStatusMessage = "Connecting to Layer Hub service…"
                                    preparationProgress = 15

                                    scope.launch {
                                        try {
                                            // Handle backend vs local preparation
                                            val prepared = if (currentResolution?.uris?.isNotEmpty() == true && currentResolution?.backendAnalysis == null) {
                                                preparationStatusMessage = "Copying shared media files…"
                                                preparer.prepare(
                                                    context = context.applicationContext,
                                                    input = activeInput,
                                                    resolution = currentResolution!!,
                                                    videoQualityId = qualityId,
                                                    audioQualityId = qualityId
                                                )
                                            } else {
                                                // Call backend service
                                                preparationStatusMessage = "Preparing media on server…"
                                                val analysis = MediaAnalysis(
                                                    originalUrl = resp.originalUrl,
                                                    detectedPlatform = resp.sourcePlatform,
                                                    mediaType = if (outputChoice == "Audio") MediaType.AUDIO else resp.mediaType,
                                                    title = resp.title,
                                                    thumbnailUrl = resp.thumbnailUrl,
                                                    durationSeconds = resp.durationSeconds,
                                                    videoQualities = resp.videoQualities,
                                                    audioQualities = resp.audioQualities
                                                )

                                                val res = MediaResolution(
                                                    status = ResolutionStatus.READY,
                                                    sourceName = resp.sourcePlatform,
                                                    title = resp.title,
                                                    mediaType = analysis.mediaType,
                                                    durationMs = resp.durationSeconds?.let { it * 1000 },
                                                    canPrepare = true,
                                                    backendAnalysis = analysis
                                                )

                                                preparer.prepare(
                                                    context = context.applicationContext,
                                                    input = activeInput,
                                                    resolution = res,
                                                    videoQualityId = qualityId,
                                                    audioQualityId = qualityId
                                                ) { progress ->
                                                    progress.fraction?.let { fraction ->
                                                        preparationProgress = (fraction * 100).toInt()
                                                    }
                                                    preparationStatusMessage = "Downloading prepared media bytes…"
                                                }
                                            }

                                            preparedMedia = prepared
                                            stage = HubStage.READY
                                        } catch (cancelled: CancellationException) {
                                            stage = HubStage.CONFIRMATION
                                        } catch (err: Exception) {
                                            errorMessage = err.message ?: "Media preparation failed."
                                            stage = HubStage.CONFIRMATION
                                        }
                                    }
                                },
                                onBack = {
                                    stage = HubStage.EMPTY
                                }
                            )
                        }
                    }

                    HubStage.PREPARING -> {
                        PreparationLoadingScreen(
                            statusMessage = preparationStatusMessage,
                            progressPercent = preparationProgress,
                            onCancel = {
                                stage = HubStage.CONFIRMATION
                            }
                        )
                    }

                    HubStage.READY -> {
                        val prepared = preparedMedia
                        if (prepared != null) {
                            MediaReadyScreen(
                                prepared = prepared,
                                onBack = {
                                    stage = HubStage.EMPTY
                                    analysisResponse = null
                                    preparedMedia = null
                                }
                            )
                        }
                    }
                }
            }

            if (showSettingsDialog) {
                SettingsDialog(
                    preferences = preferences,
                    currentThemeMode = themeMode,
                    currentPaletteIndex = paletteIndex,
                    currentBackendUrl = backendUrl,
                    onDismiss = { showSettingsDialog = false }
                )
            }
        }
    }
}
