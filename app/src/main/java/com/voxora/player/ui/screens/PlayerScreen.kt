package com.voxora.player.ui.screens

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import android.view.WindowManager
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
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.res.stringResource
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
import com.voxora.player.R
import com.voxora.player.model.AspectRatioMode
import com.voxora.player.ui.components.AspectRatioSheet
import com.voxora.player.ui.components.AudioTrackSheet
import com.voxora.player.ui.components.AutoSubtitleSettingsSheet
import com.voxora.player.ui.components.DraggableSubtitleOverlay
import com.voxora.player.ui.components.GestureHudOverlay
import com.voxora.player.ui.components.GestureHudState
import com.voxora.player.ui.components.HoldSpeedIndicatorPill
import com.voxora.player.ui.components.PlayerControlsOverlay
import com.voxora.player.ui.components.SleepTimerSheet
import com.voxora.player.ui.components.SpeedSelectorSheet
import com.voxora.player.ui.theme.VoxoraCyan
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
    SLEEP_TIMER
}

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    uriString: String,
    videoTitle: String,
    startPositionMs: Long,
    viewModel: PlayerViewModel,
    isInPipMode: Boolean = false,
    onEnterPipMode: (videoWidth: Int, videoHeight: Int) -> Boolean = { _, _ -> false },
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
    val autoSubtitleState by viewModel.autoSubtitleState.collectAsStateWithLifecycle()
    val sleepTimerState by viewModel.sleepTimerState.collectAsStateWithLifecycle()

    val comingSoonMessage = stringResource(R.string.ai_dubbing_coming_soon_message)
    val latestIsInPip by rememberUpdatedState(isInPipMode)
    val latestHoldSpeedPref by rememberUpdatedState(preferences.holdSpeedMultiplier)

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

    // Show user feedback toasts/snackbars (subtitle load, sleep timer, AI Dubbing Coming Soon)
    LaunchedEffect(uiState.feedbackMessage) {
        val msg = uiState.feedbackMessage
        if (msg != null) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearFeedbackMessage()
        }
    }

    // Auto-hide controls smoothly after 3.0 seconds of inactivity while playing
    LaunchedEffect(controlsVisible, playbackState.isPlaying, activeSheet, lastInteractionMs, uiState.isLocked, isInPipMode) {
        if (isInPipMode) {
            controlsVisible = false
            activeSheet = ActiveSheet.NONE
            return@LaunchedEffect
        }
        if (controlsVisible && playbackState.isPlaying && activeSheet == ActiveSheet.NONE) {
            delay(3_000L)
            controlsVisible = false
        }
    }

    // Battery-safe WakeLock/Keep-Screen-On: ONLY active while video is actively playing!
    DisposableEffect(playbackState.isPlaying, activity) {
        val window = activity?.window
        if (window != null) {
            if (playbackState.isPlaying) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // Lifecycle pause/background handling: immediately cancels polling & AI coroutines when leaving
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                viewModel.onActivityBackgrounded(isInPipMode = latestIsInPip)
            } else if (event == Lifecycle.Event.ON_STOP && !latestIsInPip) {
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

    // Immersive fullscreen system bars handling
    DisposableEffect(uiState.isFullScreen, view, isInPipMode) {
        val window = activity?.window
        val controller = if (window != null) WindowCompat.getInsetsController(window, view) else null
        if (controller != null && !isInPipMode) {
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

        // Gesture Capture Layer: Tap, Double-Tap Seek, Press & Hold 2x/3x Speed (with slide up/down), Brightness, Volume, Seek
        if (!isInPipMode) {
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
                                    delay(750L)
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
                            // 0=undecided, 1=horizontal seek, 2=vertical brightness/volume, 3=hold-to-speed (2x/3x)
                            var dragMode = 0
                            var totalDx = 0f
                            var totalDy = 0f
                            var holdAnchorY = down.position.y
                            var holdActivated = false
                            val downTimeMs = System.currentTimeMillis()

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

                            try {
                                while (true) {
                                    val event = if (dragMode == 0) {
                                        val remainingMs = (360L - (System.currentTimeMillis() - downTimeMs)).coerceAtLeast(16L)
                                        withTimeoutOrNull(remainingMs) { awaitPointerEvent() }
                                    } else {
                                        awaitPointerEvent()
                                    }

                                    // Long-press threshold reached without dragging -> Activate Hold-to-Speed (2x / 3x)!
                                    if (event == null && dragMode == 0) {
                                        dragMode = 3
                                        holdActivated = true
                                        holdAnchorY = down.position.y
                                        controlsVisible = false
                                        viewModel.beginHoldSpeed(latestHoldSpeedPref)
                                        continue
                                    }

                                    val change = event?.changes?.firstOrNull() ?: break
                                    if (!change.pressed) {
                                        if (dragMode == 1) {
                                            viewModel.seekTo(seekTargetMs)
                                        }
                                        if (dragMode == 1 || dragMode == 2) {
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
                                        3 -> {
                                            change.consume()
                                            val dyFromAnchor = change.position.y - holdAnchorY
                                            if (dyFromAnchor < -72f) {
                                                viewModel.updateHoldSpeed(3.0f)
                                            } else if (dyFromAnchor > 72f) {
                                                viewModel.updateHoldSpeed(2.0f)
                                            }
                                        }
                                    }
                                }
                            } finally {
                                if (holdActivated) {
                                    viewModel.endHoldSpeed()
                                }
                            }
                        }
                    }
            )
        }

        // Draggable, Frame-Synced Subtitles Overlay (visible in both full & PiP mode)
        DraggableSubtitleOverlay(
            subtitleText = uiState.activeSubtitleText,
            fontSizeSp = if (isInPipMode) (preferences.subtitleFontSizeSp * 0.65f).coerceAtLeast(11f) else preferences.subtitleFontSizeSp,
            verticalPositionFraction = preferences.subtitleVerticalPositionFraction,
            textColor = preferences.subtitleTextColor,
            backgroundOpacity = preferences.subtitleBackgroundOpacity,
            isLocked = uiState.isLocked || isInPipMode,
            onVerticalPositionChange = viewModel::setSubtitleVerticalPosition
        )

        // Buffering Indicator
        if (playbackState.isBuffering && uiState.fileMissingError == null && playbackState.errorMessage == null) {
            CircularProgressIndicator(
                color = VoxoraCyan,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        // Hold-to-Speed Top Pill (⚡ 2.0x Speed >> / ⚡ 3.0x Speed >>)
        if (!isInPipMode) {
            HoldSpeedIndicatorPill(
                visible = uiState.isHoldSpeedActive,
                speed = uiState.activeHoldSpeed
            )

            // Gesture Feedback HUD
            GestureHudOverlay(state = gestureHudState)

            // Controls Overlay
            val autoBadge = if (autoSubtitleState.isEnabled) {
                if (autoSubtitleState.isSetupRunning) {
                    "AI ${autoSubtitleState.setupProgressPercent}%"
                } else {
                    "AI · ${autoSubtitleState.targetLanguageCode.uppercase()}"
                }
            } else {
                "CC AI"
            }

            PlayerControlsOverlay(
                visible = controlsVisible && uiState.fileMissingError == null && playbackState.errorMessage == null && !uiState.isHoldSpeedActive,
                isLocked = uiState.isLocked,
                title = uiState.videoTitle.ifBlank { videoTitle },
                isPlaying = playbackState.isPlaying,
                currentPositionMs = playbackState.currentPositionMs,
                durationMs = playbackState.durationMs,
                playbackSpeed = playbackState.playbackSpeed,
                aspectRatioMode = uiState.aspectRatioMode,
                isFullScreen = uiState.isFullScreen,
                resumedFromMs = uiState.resumedFromPositionMs,
                autoSubtitleActive = autoSubtitleState.isEnabled,
                autoSubtitleBadgeText = autoBadge,
                sleepTimerState = sleepTimerState,
                canEnterPip = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O,
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
                onOpenSleepTimer = { activeSheet = ActiveSheet.SLEEP_TIMER },
                onEnterPip = {
                    controlsVisible = false
                    activeSheet = ActiveSheet.NONE
                    onEnterPipMode(playbackState.videoWidth, playbackState.videoHeight)
                },
                onTapAiDubbingComingSoon = {
                    viewModel.showFeedbackMessage(comingSoonMessage)
                },
                onStartFromBeginning = { viewModel.startFromBeginning() },
                onDismissResumeBanner = { viewModel.dismissResumeBanner() },
                onUserInteraction = { registerInteraction() }
            )
        }

        // Graceful Error State
        val activeError = uiState.fileMissingError ?: playbackState.errorMessage
        if (activeError != null && !isInPipMode) {
            Surface(
                color = Color(0xEE11141E),
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
        if (!isInPipMode) {
            when (activeSheet) {
                ActiveSheet.SUBTITLES -> {
                    AutoSubtitleSettingsSheet(
                        autoSubtitleState = autoSubtitleState,
                        tracks = playbackState.subtitleTracks,
                        fontSizeSp = preferences.subtitleFontSizeSp,
                        verticalPositionFraction = preferences.subtitleVerticalPositionFraction,
                        textColor = preferences.subtitleTextColor,
                        backgroundOpacity = preferences.subtitleBackgroundOpacity,
                        syncOffsetMs = playbackState.subtitleSyncOffsetMs,
                        onToggleAutoSubtitles = viewModel::toggleAutoSubtitles,
                        onSelectAutoTargetLanguage = viewModel::setAutoSubtitleTargetLanguage,
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
                        onVerticalPositionChange = viewModel::setSubtitleVerticalPosition,
                        onTextColorChange = viewModel::setSubtitleTextColor,
                        onBackgroundOpacityChange = viewModel::setSubtitleBackgroundOpacity,
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
                        holdSpeedMultiplier = preferences.holdSpeedMultiplier,
                        onSelectSpeed = viewModel::setPlaybackSpeed,
                        onSelectHoldSpeed = viewModel::setHoldSpeedMultiplier,
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
                ActiveSheet.SLEEP_TIMER -> {
                    SleepTimerSheet(
                        state = sleepTimerState,
                        onSelectOption = viewModel::setSleepTimer,
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
}
