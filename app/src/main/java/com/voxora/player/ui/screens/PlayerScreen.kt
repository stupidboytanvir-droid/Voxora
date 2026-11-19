package com.voxora.player.ui.screens

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.ContentFrame
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import com.voxora.player.model.AspectRatioMode
import com.voxora.player.ui.components.AiDubbingStudioSheet
import com.voxora.player.ui.components.AspectRatioSheet
import com.voxora.player.ui.components.AudioTrackSheet
import com.voxora.player.ui.components.BatterySaverSheet
import com.voxora.player.ui.components.GestureHudOverlay
import com.voxora.player.ui.components.GestureHudState
import com.voxora.player.ui.components.PlayerControlsOverlay
import com.voxora.player.ui.components.SpeedSelectorSheet
import com.voxora.player.ui.components.SubtitleOverlay
import com.voxora.player.ui.components.SubtitleSettingsSheet
import com.voxora.player.ui.theme.VoxoraElectricPurple
import com.voxora.player.ui.viewmodel.PlayerViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

private enum class ActiveSheet {
    NONE,
    SUBTITLES,
    AUDIO_TRACKS,
    SPEED,
    ASPECT_RATIO,
    AI_DUBBING,
    BATTERY_SAVER
}

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    uriString: String,
    videoTitle: String,
    startPositionMs: Long,
    viewModel: PlayerViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val view = LocalView.current
    val configuration = LocalConfiguration.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val playbackState by viewModel.playbackState.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val preferences by viewModel.preferences.collectAsStateWithLifecycle()
    val batterySaving by viewModel.batterySavingState.collectAsStateWithLifecycle()
    val batteryStatus by viewModel.batteryStatus.collectAsStateWithLifecycle()
    val dubbingState by viewModel.dubbingState.collectAsStateWithLifecycle()
    val animationsEnabled = !batterySaving.disableAnimations

    var controlsVisible by remember { mutableStateOf(true) }
    var lastInteractionMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var activeSheet by remember { mutableStateOf(ActiveSheet.NONE) }
    var gestureHudState by remember { mutableStateOf<GestureHudState>(GestureHudState.Hidden) }

    val subtitlePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.loadExternalSubtitle(uri)
        }
    }

    LaunchedEffect(uriString, startPositionMs) {
        viewModel.initializeMedia(
            uriString = uriString,
            fallbackTitle = videoTitle,
            explicitStartMs = startPositionMs
        )
    }

    // Handle auto-play next when video finishes
    LaunchedEffect(playbackState.isEnded) {
        if (playbackState.isEnded) {
            val movedToNext = viewModel.playNextInLibraryIfEnabled()
            if (!movedToNext) {
                controlsVisible = true
            }
        }
    }

    // Show subtitle load feedback
    LaunchedEffect(uiState.subtitleLoadFeedback) {
        val msg = uiState.subtitleLoadFeedback
        if (msg != null) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearSubtitleFeedback()
        }
    }

    // Auto-hide controls after 3.5 seconds while playing and no sheet is open
    LaunchedEffect(controlsVisible, playbackState.isPlaying, activeSheet, lastInteractionMs, uiState.isLocked) {
        if (controlsVisible && playbackState.isPlaying && activeSheet == ActiveSheet.NONE) {
            delay(3_500L)
            controlsVisible = false
        }
    }

    // Lifecycle pause/save & clean orientation/system bar restoration
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                viewModel.pausePlayback()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.persistProgressSync()
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // Immersive fullscreen handling
    DisposableEffect(uiState.isFullScreen, view) {
        val window = activity?.window
        val controller = if (window != null) WindowCompat.getInsetsController(window, view) else null
        if (controller != null) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (uiState.isFullScreen) {
                controller.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // Battery-saving display refresh rate cap (60Hz when active, default 0f otherwise)
    DisposableEffect(batterySaving.capRefreshRate, activity) {
        val window = activity?.window
        if (window != null) {
            val lp = window.attributes
            lp.preferredRefreshRate = if (batterySaving.capRefreshRate) 60f else 0f
            window.attributes = lp
        }
        onDispose {
            val win = activity?.window
            if (win != null) {
                val lp = win.attributes
                if (lp.preferredRefreshRate != 0f) {
                    lp.preferredRefreshRate = 0f
                    win.attributes = lp
                }
            }
        }
    }

    BackHandler {
        if (activeSheet != ActiveSheet.NONE) {
            activeSheet = ActiveSheet.NONE
        } else {
            viewModel.pausePlayback()
            onBack()
        }
    }

    val audioManager = remember(context) {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    val touchSlop = LocalViewConfiguration.current.touchSlop

    fun registerInteraction() {
        lastInteractionMs = System.currentTimeMillis()
    }

    fun toggleOrientation() {
        val act = activity ?: return
        val isCurrentlyLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        act.requestedOrientation = if (isCurrentlyLandscape) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Video Surface Area with Aspect Ratio Mode
        val videoSurfaceModifier = when (uiState.aspectRatioMode) {
            AspectRatioMode.FIT,
            AspectRatioMode.FILL,
            AspectRatioMode.STRETCH -> Modifier.fillMaxSize()
            AspectRatioMode.ZOOM -> Modifier
                .fillMaxSize()
                .graphicsLayer(scaleX = 1.25f, scaleY = 1.25f)
            AspectRatioMode.RATIO_16_9 -> Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
            AspectRatioMode.RATIO_4_3 -> Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .aspectRatio(4f / 3f)
        }

        val contentScale = when (uiState.aspectRatioMode) {
            AspectRatioMode.FIT -> ContentScale.Fit
            AspectRatioMode.FILL, AspectRatioMode.ZOOM -> ContentScale.Crop
            AspectRatioMode.RATIO_16_9,
            AspectRatioMode.RATIO_4_3,
            AspectRatioMode.STRETCH -> ContentScale.FillBounds
        }

        ContentFrame(
            player = viewModel.playerEngine.exoPlayer,
            modifier = videoSurfaceModifier,
            surfaceType = SURFACE_TYPE_SURFACE_VIEW,
            contentScale = contentScale
        )

        // Gesture Capture Layer (Tap, Double-Tap Seek, Brightness, Volume, Horizontal Scrub)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(uiState.isLocked, preferences.doubleTapSeekSeconds) {
                    detectTapGestures(
                        onTap = {
                            controlsVisible = !controlsVisible
                            if (controlsVisible) registerInteraction()
                        },
                        onDoubleTap = { offset ->
                            if (uiState.isLocked) {
                                controlsVisible = true
                                registerInteraction()
                                return@detectTapGestures
                            }
                            val seekSec = preferences.doubleTapSeekSeconds
                            val isForward = offset.x >= size.width / 2f
                            viewModel.seekBySeconds(if (isForward) seekSec else -seekSec)
                            registerInteraction()
                            scope.launch {
                                gestureHudState = GestureHudState.DoubleTapSeek(isForward, seekSec)
                                delay(800L)
                                if (gestureHudState is GestureHudState.DoubleTapSeek) {
                                    gestureHudState = GestureHudState.Hidden
                                }
                            }
                        }
                    )
                }
                .pointerInput(
                    uiState.isLocked,
                    preferences.brightnessGestureEnabled,
                    preferences.volumeGestureEnabled,
                    playbackState.durationMs
                ) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        if (uiState.isLocked) return@awaitEachGesture

                        val startX = down.position.x
                        val isLeftHalf = startX < size.width / 2f
                        var dragMode = 0 // 0=undecided, 1=horizontal seek, 2=vertical brightness/volume
                        var totalDx = 0f
                        var totalDy = 0f

                        val initialPos = playbackState.currentPositionMs
                        val duration = playbackState.durationMs.coerceAtLeast(1L)
                        var seekTargetMs = initialPos

                        var currentBrightness = activity?.window?.attributes?.screenBrightness
                            ?.takeIf { it in 0f..1f }
                            ?: runCatching {
                                Settings.System.getInt(
                                    context.contentResolver,
                                    Settings.System.SCREEN_BRIGHTNESS
                                ) / 255f
                            }.getOrDefault(0.5f)

                        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                        var volumeAccumulator = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()

                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break
                            if (!change.pressed) {
                                if (dragMode == 1) {
                                    viewModel.seekTo(seekTargetMs)
                                }
                                if (dragMode != 0) {
                                    gestureHudState = GestureHudState.Hidden
                                }
                                break
                            }

                            val delta = change.position - change.previousPosition
                            totalDx += delta.x
                            totalDy += delta.y

                            if (dragMode == 0) {
                                if (abs(totalDx) > touchSlop && abs(totalDx) > abs(totalDy) * 1.2f) {
                                    dragMode = 1
                                } else if (abs(totalDy) > touchSlop && abs(totalDy) > abs(totalDx) * 1.2f) {
                                    if ((isLeftHalf && preferences.brightnessGestureEnabled) ||
                                        (!isLeftHalf && preferences.volumeGestureEnabled)
                                    ) {
                                        dragMode = 2
                                    } else {
                                        break
                                    }
                                }
                            }

                            when (dragMode) {
                                1 -> {
                                    change.consume()
                                    val seekSpanMs = minOf(duration, 120_000L).coerceAtLeast(20_000L)
                                    val deltaMs = ((totalDx / size.width.coerceAtLeast(1)) * seekSpanMs).toLong()
                                    seekTargetMs = (initialPos + deltaMs).coerceIn(0L, duration)
                                    gestureHudState = GestureHudState.SeekPreview(
                                        targetMs = seekTargetMs,
                                        deltaMs = seekTargetMs - initialPos,
                                        durationMs = duration
                                    )
                                }
                                2 -> {
                                    change.consume()
                                    val verticalRatio = -delta.y / (size.height.coerceAtLeast(1) * 0.75f)
                                    if (isLeftHalf && preferences.brightnessGestureEnabled) {
                                        currentBrightness = (currentBrightness + verticalRatio).coerceIn(0.02f, 1.0f)
                                        activity?.window?.let { win ->
                                            val lp = win.attributes
                                            lp.screenBrightness = currentBrightness
                                            win.attributes = lp
                                        }
                                        gestureHudState = GestureHudState.Brightness(currentBrightness)
                                    } else if (!isLeftHalf && preferences.volumeGestureEnabled) {
                                        volumeAccumulator = (volumeAccumulator + verticalRatio * maxVolume)
                                            .coerceIn(0f, maxVolume.toFloat())
                                        val targetVol = kotlin.math.round(volumeAccumulator).toInt().coerceIn(0, maxVolume)
                                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, 0)
                                        val fraction = volumeAccumulator / maxVolume.toFloat()
                                        gestureHudState = GestureHudState.Volume(
                                            fraction = fraction,
                                            percent = (fraction * 100).toInt()
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
        )

        // Subtitles Overlay
        SubtitleOverlay(
            subtitleText = uiState.activeSubtitleText,
            fontSizeSp = preferences.subtitleFontSizeSp,
            bottomPaddingFraction = preferences.subtitleBottomPaddingFraction,
            backgroundOpacity = preferences.subtitleBackgroundOpacity,
            animationsEnabled = animationsEnabled
        )

        // Buffering Indicator
        if (playbackState.isBuffering && uiState.fileMissingError == null && playbackState.errorMessage == null) {
            CircularProgressIndicator(
                color = VoxoraElectricPurple,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        // Gesture Feedback HUD
        GestureHudOverlay(
            state = gestureHudState,
            animationsEnabled = animationsEnabled
        )

        // Controls Overlay
        PlayerControlsOverlay(
            visible = controlsVisible && uiState.fileMissingError == null && playbackState.errorMessage == null,
            isLocked = uiState.isLocked,
            title = uiState.videoTitle.ifBlank { videoTitle },
            isPlaying = playbackState.isPlaying,
            currentPositionMs = playbackState.currentPositionMs,
            durationMs = playbackState.durationMs,
            playbackSpeed = playbackState.playbackSpeed,
            aspectRatioMode = uiState.aspectRatioMode,
            isFullScreen = uiState.isFullScreen,
            resumedFromMs = uiState.resumedFromPositionMs,
            animationsEnabled = animationsEnabled,
            batterySavingActive = batterySaving.isActive,
            batteryCapRefreshRate = batterySaving.capRefreshRate,
            batteryPercent = batteryStatus.batteryPercent,
            aiDubbingActive = dubbingState.config.isDubbingEnabled,
            onBack = {
                viewModel.pausePlayback()
                onBack()
            },
            onToggleLock = {
                viewModel.toggleScreenLock()
                controlsVisible = true
                registerInteraction()
            },
            onRotateScreen = {
                toggleOrientation()
                registerInteraction()
            },
            onToggleFullScreen = {
                viewModel.toggleFullScreen()
                registerInteraction()
            },
            onTogglePlayPause = {
                viewModel.togglePlayPause()
                registerInteraction()
            },
            onSeekTo = { target ->
                viewModel.seekTo(target)
                registerInteraction()
            },
            onRewind = {
                viewModel.seekBySeconds(-10)
                registerInteraction()
            },
            onForward = {
                viewModel.seekBySeconds(10)
                registerInteraction()
            },
            onOpenSpeed = { activeSheet = ActiveSheet.SPEED },
            onOpenSubtitles = { activeSheet = ActiveSheet.SUBTITLES },
            onOpenAudioTracks = { activeSheet = ActiveSheet.AUDIO_TRACKS },
            onOpenAspectRatio = { activeSheet = ActiveSheet.ASPECT_RATIO },
            onOpenAiDubbing = {
                activeSheet = ActiveSheet.AI_DUBBING
                if (dubbingState.extractedAudioInfo == null) {
                    viewModel.inspectCurrentVideoAudio()
                }
            },
            onOpenBatterySaver = { activeSheet = ActiveSheet.BATTERY_SAVER },
            onStartFromBeginning = { viewModel.startFromBeginning() },
            onDismissResumeBanner = { viewModel.dismissResumeBanner() },
            onUserInteraction = { registerInteraction() }
        )

        // Graceful Error State (Missing file, revoked permission, or unsupported codec)
        val activeError = uiState.fileMissingError ?: playbackState.errorMessage
        if (activeError != null) {
            Surface(
                color = Color(0xEE121218),
                shape = RoundedCornerShape(22.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp)
                    .widthIn(max = 420.dp)
            ) {
                Column(
                    modifier = Modifier.padding(22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(44.dp)
                    )
                    Text(
                        text = "Unable to Play Video",
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White
                    )
                    Text(
                        text = activeError,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.8f)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = onBack) {
                            Text("Go Back")
                        }
                        Button(
                            onClick = { viewModel.retryLoad() },
                            colors = ButtonDefaults.buttonColors(containerColor = VoxoraElectricPurple)
                        ) {
                            Text("Retry")
                        }
                    }
                }
            }
        }

        // Modal Sheets
        when (activeSheet) {
            ActiveSheet.SUBTITLES -> {
                SubtitleSettingsSheet(
                    tracks = playbackState.subtitleTracks,
                    fontSizeSp = preferences.subtitleFontSizeSp,
                    bottomPaddingFraction = preferences.subtitleBottomPaddingFraction,
                    syncOffsetMs = playbackState.subtitleSyncOffsetMs,
                    onSelectTrack = viewModel::selectSubtitleTrack,
                    onDisableSubtitles = viewModel::disableSubtitles,
                    onPickExternalSubtitle = {
                        subtitlePickerLauncher.launch(
                            arrayOf(
                                "application/x-subrip",
                                "text/vtt",
                                "text/plain",
                                "*/*"
                            )
                        )
                    },
                    onFontSizeChange = viewModel::setSubtitleFontSize,
                    onPositionChange = viewModel::setSubtitleBottomPadding,
                    onAdjustSync = viewModel::adjustSubtitleSync,
                    onResetSync = { viewModel.setSubtitleSync(0L) },
                    onDismiss = { activeSheet = ActiveSheet.NONE }
                )
            }
            ActiveSheet.AUDIO_TRACKS -> {
                AudioTrackSheet(
                    tracks = playbackState.audioTracks,
                    onSelectTrack = viewModel::selectAudioTrack,
                    onDismiss = { activeSheet = ActiveSheet.NONE }
                )
            }
            ActiveSheet.SPEED -> {
                SpeedSelectorSheet(
                    currentSpeed = playbackState.playbackSpeed,
                    onSelectSpeed = viewModel::setPlaybackSpeed,
                    onDismiss = { activeSheet = ActiveSheet.NONE }
                )
            }
            ActiveSheet.ASPECT_RATIO -> {
                AspectRatioSheet(
                    currentMode = uiState.aspectRatioMode,
                    onSelectMode = viewModel::setAspectRatio,
                    onDismiss = { activeSheet = ActiveSheet.NONE }
                )
            }
            ActiveSheet.AI_DUBBING -> {
                AiDubbingStudioSheet(
                    state = dubbingState,
                    onSetEnabled = viewModel::setDubbingEnabled,
                    onSelectSourceLanguage = viewModel::setDubbingSourceLanguage,
                    onSelectTargetLanguage = viewModel::setDubbingTargetLanguage,
                    onSelectOutputMode = viewModel::setDubbingOutputMode,
                    onOriginalVolumeChange = viewModel::setDubbingOriginalVolume,
                    onDubbedVolumeChange = viewModel::setDubbingVoiceVolume,
                    onAdjustSyncOffset = viewModel::adjustDubbingSyncOffset,
                    onResetSyncOffset = viewModel::resetDubbingSyncOffset,
                    onPreserveBackgroundChange = viewModel::setPreserveBackgroundAudio,
                    onAddCharacter = viewModel::addDubbingCharacter,
                    onUpdateCharacter = viewModel::updateDubbingCharacter,
                    onRemoveCharacter = viewModel::removeDubbingCharacter,
                    onResetDefaultCharacters = viewModel::resetDefaultDubbingCharacters,
                    onPreviewCharacterVoice = viewModel::previewCharacterVoice,
                    onStopVoicePreview = viewModel::stopVoicePreview,
                    onInspectAudioTrack = viewModel::inspectCurrentVideoAudio,
                    onRunStage1Extraction = viewModel::runStage1AudioExtraction,
                    onDismiss = {
                        viewModel.stopVoicePreview()
                        activeSheet = ActiveSheet.NONE
                    }
                )
            }
            ActiveSheet.BATTERY_SAVER -> {
                BatterySaverSheet(
                    preferences = preferences,
                    batteryStatus = batteryStatus,
                    evaluation = batterySaving,
                    onSelectMode = viewModel::setBatterySaverMode,
                    onToggleCapRefreshRate = viewModel::setBatterySaverCapRefreshRate,
                    onToggleDisableAnimations = viewModel::setBatterySaverDisableAnimations,
                    onDismiss = { activeSheet = ActiveSheet.NONE }
                )
            }
            ActiveSheet.NONE -> Unit
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(20.dp)
        )
    }
}
