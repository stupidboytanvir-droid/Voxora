package com.voxora.player.ui.screens

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
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
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
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
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import com.voxora.player.R
import com.voxora.player.model.AspectRatioMode
import com.voxora.player.model.DisplayFilterSettings
import com.voxora.player.ui.components.ABLoopSheet
import com.voxora.player.ui.components.AspectRatioSheet
import com.voxora.player.ui.components.AudioFxSheet
import com.voxora.player.ui.components.AudioTrackSheet
import com.voxora.player.ui.components.AutoSubtitleSettingsSheet
import com.voxora.player.ui.components.BookmarksSheet
import com.voxora.player.ui.components.ClipCutterSheet
import com.voxora.player.ui.components.DisplaySettingsSheet
import com.voxora.player.ui.components.DraggableSubtitleOverlay
import com.voxora.player.ui.components.GestureHudOverlay
import com.voxora.player.ui.components.GestureHudState
import com.voxora.player.ui.components.HoldSpeedIndicatorPill
import com.voxora.player.ui.components.KidsLockOverlay
import com.voxora.player.ui.components.PlayerControlsOverlay
import com.voxora.player.ui.components.SleepTimerSheet
import com.voxora.player.ui.components.SpeedSelectorSheet
import com.voxora.player.ui.components.UpNextOverlayCard
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
    AUDIO_FX,
    DISPLAY,
    BOOKMARKS,
    AB_LOOP,
    CLIP_CUTTER,
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
    val upNextState by viewModel.upNextState.collectAsStateWithLifecycle()
    val bookmarks by viewModel.bookmarks.collectAsStateWithLifecycle()

    val comingSoonMessage = stringResource(R.string.ai_dubbing_coming_soon_message)
    val latestIsInPip by rememberUpdatedState(isInPipMode)
    val latestHoldSpeedPref by rememberUpdatedState(preferences.holdSpeedMultiplier)

    var controlsVisible by remember { mutableStateOf(true) }
    var lastInteractionMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var activeSheet by remember { mutableStateOf(ActiveSheet.NONE) }
    var gestureHudState by remember { mutableStateOf<GestureHudState>(GestureHudState.Hidden) }

    // Smart Auto-Orientation state (resets per video URI, respects manual Rotate button override)
    var userManualOrientation by remember(uriString) { mutableStateOf(false) }

    // Pinch-to-Zoom (1.0x to 4.0x) and Pan state
    var zoomScale by remember(uriString) { mutableFloatStateOf(1f) }
    var panOffset by remember(uriString) { mutableStateOf(Offset.Zero) }

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

    // Smart Auto-Orientation: Landscape videos -> SENSOR_LANDSCAPE; Portrait/Square -> PORTRAIT
    LaunchedEffect(
        playbackState.effectiveVideoWidth,
        playbackState.effectiveVideoHeight,
        userManualOrientation,
        isInPipMode
    ) {
        val act = activity ?: return@LaunchedEffect
        if (userManualOrientation || isInPipMode) return@LaunchedEffect
        val w = playbackState.effectiveVideoWidth
        val h = playbackState.effectiveVideoHeight
        if (w > 0 && h > 0) {
            act.requestedOrientation = if (w > h) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            } else {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
        }
    }

    // Show user feedback toasts/snackbars
    LaunchedEffect(uiState.feedbackMessage) {
        val msg = uiState.feedbackMessage
        if (msg != null) {
            snackbarHostState.currentSnackbarData?.dismiss()
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

    // Battery-safe Keep-Screen-On: ONLY active while video is actively playing
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

    // Lifecycle pause/background handling (keeps playing in PiP or Background Audio Mode)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                viewModel.onActivityBackgrounded(isInPipMode = latestIsInPip)
            } else if (event == Lifecycle.Event.ON_STOP && !latestIsInPip && !viewModel.uiState.value.isBackgroundPlayActive) {
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
        when {
            uiState.isKidsLockActive -> {
                // Kids Lock blocks system back navigation until unlocked by holding 3s
            }
            activeSheet != ActiveSheet.NONE -> {
                activeSheet = ActiveSheet.NONE
            }
            else -> {
                if (!uiState.isBackgroundPlayActive) {
                    viewModel.pausePlayback()
                }
                onBack()
            }
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
        userManualOrientation = true
        val isCurrentlyLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        act.requestedOrientation = if (isCurrentlyLandscape) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    fun openSystemCastSettings() {
        runCatching {
            val intent = Intent(Settings.ACTION_CAST_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }.onFailure {
            viewModel.showFeedbackMessage("Wireless Cast / Screen Mirroring is not available on this device.")
        }
    }

    val filters = uiState.displayFilters
    val composeColorRenderEffect = remember(filters.contrast, filters.saturation, filters.brightnessOffset) {
        buildVideoColorRenderEffect(filters)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Video Surface Area with Aspect Ratio, Pinch-to-Zoom (up to 4x + pan), Mirror, 90° Rotation & GPU ColorMatrix
        val baseAspectScale = if (uiState.aspectRatioMode == AspectRatioMode.ZOOM) 1.25f else 1f
        val effectiveScale = (baseAspectScale * zoomScale).coerceIn(1f, 4f)
        val mirrorMultiplier = if (filters.isMirrored) -1f else 1f

        val videoSurfaceModifier = when (uiState.aspectRatioMode) {
            AspectRatioMode.FIT,
            AspectRatioMode.FILL,
            AspectRatioMode.ZOOM,
            AspectRatioMode.STRETCH -> Modifier.fillMaxSize()
            AspectRatioMode.RATIO_16_9 -> Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
            AspectRatioMode.RATIO_4_3 -> Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .aspectRatio(4f / 3f)
        }.graphicsLayer {
            scaleX = effectiveScale * mirrorMultiplier
            scaleY = effectiveScale
            translationX = if (zoomScale > 1.01f) panOffset.x else 0f
            translationY = if (zoomScale > 1.01f) panOffset.y else 0f
            rotationZ = filters.manualRotationDegrees.toFloat()
            if (composeColorRenderEffect != null) {
                renderEffect = composeColorRenderEffect
            }
        }

        val contentScale = when (uiState.aspectRatioMode) {
            AspectRatioMode.FIT -> ContentScale.Fit
            AspectRatioMode.FILL, AspectRatioMode.ZOOM -> ContentScale.Crop
            AspectRatioMode.RATIO_16_9,
            AspectRatioMode.RATIO_4_3,
            AspectRatioMode.STRETCH -> ContentScale.FillBounds
        }

        // TextureView ensures smooth Compose graphicsLayer zoom, pan, mirror, 90° rotate & RenderEffect filters
        ContentFrame(
            player = viewModel.playerEngine.exoPlayer,
            modifier = videoSurfaceModifier,
            surfaceType = SURFACE_TYPE_TEXTURE_VIEW,
            contentScale = contentScale
        )

        // Pre-API-31 Brightness fallback scrim (API 31+ uses full GPU ColorMatrix RenderEffect above)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && abs(filters.brightnessOffset) > 0.01f) {
            val scrimColor = if (filters.brightnessOffset < 0f) {
                Color.Black.copy(alpha = (-filters.brightnessOffset * 0.9f).coerceIn(0f, 0.7f))
            } else {
                Color.White.copy(alpha = (filters.brightnessOffset * 0.45f).coerceIn(0f, 0.35f))
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(scrimColor)
            )
        }

        // Night Mode / Eye Care Warm Blue-Light Filter Overlay
        if (filters.eyeCareEnabled) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x36FF9800))
            )
        }

        // Gesture Capture Layer: Pinch-to-Zoom (up to 4x + pan), Tap, Double-Tap Seek,
        // Press & Hold 2x/3x Speed (slide up/down), Brightness, Volume, Horizontal Seek
        if (!isInPipMode && !uiState.isKidsLockActive) {
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
                            // 0=undecided, 1=horizontal seek, 2=vertical brightness/volume, 3=hold-to-speed (2x/3x), 4=pinch-zoom/pan
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

                                    // Long-press threshold reached without dragging -> Activate Hold-to-Speed (2x / 3x)
                                    if (event == null && dragMode == 0) {
                                        dragMode = 3
                                        holdActivated = true
                                        holdAnchorY = down.position.y
                                        controlsVisible = false
                                        viewModel.beginHoldSpeed(latestHoldSpeedPref)
                                        continue
                                    }

                                    if (event == null) break
                                    val pressedCount = event.changes.count { it.pressed }
                                    if (pressedCount == 0) {
                                        if (dragMode == 1) {
                                            viewModel.seekTo(seekTargetMs)
                                        }
                                        if (dragMode == 1 || dragMode == 2) {
                                            gestureHudState = GestureHudState.Hidden
                                        }
                                        break
                                    }

                                    // Multi-touch Pinch-to-Zoom (up to 4x) & Pan
                                    if (pressedCount >= 2) {
                                        if (holdActivated) {
                                            viewModel.endHoldSpeed()
                                            holdActivated = false
                                        }
                                        dragMode = 4
                                        val zoomDelta = event.calculateZoom()
                                        val panDelta = event.calculatePan()
                                        val nextZoom = (zoomScale * zoomDelta).coerceIn(1f, 4f)
                                        zoomScale = nextZoom
                                        if (nextZoom <= 1.02f) {
                                            zoomScale = 1f
                                            panOffset = Offset.Zero
                                        } else {
                                            val maxX = size.width * (nextZoom - 1f) * 0.5f
                                            val maxY = size.height * (nextZoom - 1f) * 0.5f
                                            panOffset = Offset(
                                                x = (panOffset.x + panDelta.x).coerceIn(-maxX, maxX),
                                                y = (panOffset.y + panDelta.y).coerceIn(-maxY, maxY)
                                            )
                                        }
                                        event.changes.forEach { it.consume() }
                                        continue
                                    }

                                    if (dragMode == 4) {
                                        // Single-finger pan while zoomed in
                                        val change = event.changes.firstOrNull() ?: break
                                        val delta = change.position - change.previousPosition
                                        if (zoomScale > 1.02f) {
                                            val maxX = size.width * (zoomScale - 1f) * 0.5f
                                            val maxY = size.height * (zoomScale - 1f) * 0.5f
                                            panOffset = Offset(
                                                x = (panOffset.x + delta.x).coerceIn(-maxX, maxX),
                                                y = (panOffset.y + delta.y).coerceIn(-maxY, maxY)
                                            )
                                            change.consume()
                                        }
                                        continue
                                    }

                                    val change = event.changes.firstOrNull() ?: break
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

        // Draggable, Speech-Synced Dual Subtitles Overlay (15sp default, hidden in silence)
        DraggableSubtitleOverlay(
            subtitleText = uiState.activeSubtitleText,
            secondarySubtitleText = uiState.activeSecondarySubtitleText,
            fontSizeSp = if (isInPipMode) (preferences.subtitleFontSizeSp * 0.7f).coerceAtLeast(11f) else preferences.subtitleFontSizeSp,
            secondaryFontSizeSp = if (isInPipMode) (preferences.secondarySubtitleFontSizeSp * 0.7f).coerceAtLeast(10f) else preferences.secondarySubtitleFontSizeSp,
            verticalPositionFraction = preferences.subtitleVerticalPositionFraction,
            textColor = preferences.subtitleTextColor,
            secondaryTextColor = preferences.secondarySubtitleTextColor,
            backgroundOpacity = preferences.subtitleBackgroundOpacity,
            isLocked = uiState.isLocked || uiState.isKidsLockActive || isInPipMode,
            onVerticalPositionChange = viewModel::setSubtitleVerticalPosition
        )

        // Buffering Indicator
        if (playbackState.isBuffering && uiState.fileMissingError == null && playbackState.errorMessage == null) {
            CircularProgressIndicator(
                color = VoxoraCyan,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        if (!isInPipMode) {
            if (uiState.isKidsLockActive) {
                KidsLockOverlay(onUnlock = viewModel::unlockKidsLock)
            } else {
                // Hold-to-Speed Top Pill (2.0x / 3.0x Speed >>)
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
                    } else if (autoSubtitleState.dualSubtitlesEnabled) {
                        "AI · ${autoSubtitleState.targetLanguageCode.uppercase()}+${autoSubtitleState.secondaryLanguageCode.uppercase()}"
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
                    isBackgroundPlayActive = uiState.isBackgroundPlayActive,
                    volumeBoostPercent = preferences.volumeBoostPercent,
                    eqPreset = preferences.audioEqPreset,
                    eyeCareEnabled = uiState.displayFilters.eyeCareEnabled,
                    abLoop = uiState.abLoop,
                    bookmarks = bookmarks,
                    zoomScale = zoomScale,
                    isExtractingAudio = uiState.isExtractingAudio,
                    resumedFromMs = uiState.resumedFromPositionMs,
                    autoSubtitleActive = autoSubtitleState.isEnabled,
                    autoSubtitleBadgeText = autoBadge,
                    sleepTimerState = sleepTimerState,
                    canEnterPip = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O,
                    onBack = {
                        if (!uiState.isBackgroundPlayActive) {
                            viewModel.pausePlayback()
                        }
                        onBack()
                    },
                    onToggleLock = {
                        viewModel.toggleScreenLock()
                        controlsVisible = true
                        registerInteraction()
                    },
                    onEnableKidsLock = {
                        controlsVisible = false
                        activeSheet = ActiveSheet.NONE
                        viewModel.enableKidsLock()
                    },
                    onRotateScreen = {
                        toggleOrientation()
                        registerInteraction()
                    },
                    onToggleFullScreen = {
                        viewModel.toggleFullScreen()
                        registerInteraction()
                    },
                    onToggleBackgroundPlay = {
                        viewModel.toggleBackgroundAudioMode()
                        registerInteraction()
                    },
                    onCaptureScreenshot = {
                        viewModel.captureScreenshot()
                        registerInteraction()
                    },
                    onOpenCast = {
                        openSystemCastSettings()
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
                    onStepPrevFrame = {
                        viewModel.stepFrameBackward()
                        registerInteraction()
                    },
                    onStepNextFrame = {
                        viewModel.stepFrameForward()
                        registerInteraction()
                    },
                    onOpenSpeed = { activeSheet = ActiveSheet.SPEED },
                    onOpenSubtitles = { activeSheet = ActiveSheet.SUBTITLES },
                    onOpenAudioTracks = { activeSheet = ActiveSheet.AUDIO_TRACKS },
                    onOpenAudioFx = { activeSheet = ActiveSheet.AUDIO_FX },
                    onOpenDisplaySettings = { activeSheet = ActiveSheet.DISPLAY },
                    onOpenBookmarks = { activeSheet = ActiveSheet.BOOKMARKS },
                    onOpenABLoop = { activeSheet = ActiveSheet.AB_LOOP },
                    onOpenClipCutter = { activeSheet = ActiveSheet.CLIP_CUTTER },
                    onExtractAudio = { viewModel.extractAudioToMusic() },
                    onOpenAspectRatio = { activeSheet = ActiveSheet.ASPECT_RATIO },
                    onOpenSleepTimer = { activeSheet = ActiveSheet.SLEEP_TIMER },
                    onEnterPip = {
                        controlsVisible = false
                        activeSheet = ActiveSheet.NONE
                        onEnterPipMode(
                            playbackState.effectiveVideoWidth.takeIf { it > 0 } ?: playbackState.videoWidth,
                            playbackState.effectiveVideoHeight.takeIf { it > 0 } ?: playbackState.videoHeight
                        )
                    },
                    onResetZoom = {
                        zoomScale = 1f
                        panOffset = Offset.Zero
                    },
                    onTapAiDubbingComingSoon = {
                        viewModel.showFeedbackMessage(comingSoonMessage)
                    },
                    onStartFromBeginning = { viewModel.startFromBeginning() },
                    onDismissResumeBanner = { viewModel.dismissResumeBanner() },
                    onUserInteraction = { registerInteraction() }
                )

                // 5-Second Cancelable "Up Next" Card
                UpNextOverlayCard(
                    upNextState = upNextState,
                    onPlayNow = viewModel::playUpNextNow,
                    onCancel = viewModel::cancelUpNext
                )
            }
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
        if (!isInPipMode && !uiState.isKidsLockActive) {
            when (activeSheet) {
                ActiveSheet.SUBTITLES -> {
                    AutoSubtitleSettingsSheet(
                        autoSubtitleState = autoSubtitleState,
                        tracks = playbackState.subtitleTracks,
                        fontSizeSp = preferences.subtitleFontSizeSp,
                        verticalPositionFraction = preferences.subtitleVerticalPositionFraction,
                        textColor = preferences.subtitleTextColor,
                        secondaryTextColor = preferences.secondarySubtitleTextColor,
                        secondaryFontSizeSp = preferences.secondarySubtitleFontSizeSp,
                        backgroundOpacity = preferences.subtitleBackgroundOpacity,
                        syncOffsetMs = playbackState.subtitleSyncOffsetMs,
                        isExportingSrt = uiState.isExportingSrt,
                        onToggleAutoSubtitles = viewModel::toggleAutoSubtitles,
                        onSelectAutoTargetLanguage = viewModel::setAutoSubtitleTargetLanguage,
                        onToggleDualSubtitles = viewModel::setDualSubtitlesEnabled,
                        onSelectSecondaryLanguage = viewModel::setSecondarySubtitleLanguage,
                        onSecondaryTextColorChange = viewModel::setSecondarySubtitleTextColor,
                        onSecondaryFontSizeChange = viewModel::setSecondarySubtitleFontSize,
                        onExportSrt = viewModel::exportAutoSubtitlesToSrt,
                        onSearchDialogue = viewModel::searchSubtitleDialogue,
                        onJumpToTimestamp = viewModel::seekTo,
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
                ActiveSheet.AUDIO_FX -> {
                    AudioFxSheet(
                        volumeBoostPercent = preferences.volumeBoostPercent,
                        selectedPreset = preferences.audioEqPreset,
                        onVolumeBoostChange = viewModel::setVolumeBoostPercent,
                        onSelectPreset = viewModel::setAudioEqPreset,
                        onDismiss = { activeSheet = ActiveSheet.NONE }
                    )
                }
                ActiveSheet.DISPLAY -> {
                    DisplaySettingsSheet(
                        filters = uiState.displayFilters,
                        onEyeCareToggle = viewModel::setEyeCareEnabled,
                        onBrightnessChange = viewModel::setVideoBrightnessOffset,
                        onContrastChange = viewModel::setVideoContrast,
                        onSaturationChange = viewModel::setVideoSaturation,
                        onToggleMirror = viewModel::toggleVideoMirror,
                        onRotate90 = viewModel::rotateVideo90,
                        onReset = viewModel::resetDisplayFilters,
                        onDismiss = { activeSheet = ActiveSheet.NONE }
                    )
                }
                ActiveSheet.BOOKMARKS -> {
                    BookmarksSheet(
                        currentPositionMs = playbackState.currentPositionMs,
                        bookmarks = bookmarks,
                        onAddBookmark = viewModel::addSceneBookmark,
                        onJumpToBookmark = viewModel::seekTo,
                        onDeleteBookmark = viewModel::deleteSceneBookmark,
                        onDismiss = { activeSheet = ActiveSheet.NONE }
                    )
                }
                ActiveSheet.AB_LOOP -> {
                    ABLoopSheet(
                        currentPositionMs = playbackState.currentPositionMs,
                        abLoop = uiState.abLoop,
                        onSetA = viewModel::setLoopPointA,
                        onSetB = viewModel::setLoopPointB,
                        onClear = viewModel::clearABLoop,
                        onDismiss = { activeSheet = ActiveSheet.NONE }
                    )
                }
                ActiveSheet.CLIP_CUTTER -> {
                    ClipCutterSheet(
                        currentPositionMs = playbackState.currentPositionMs,
                        durationMs = playbackState.durationMs,
                        isCutting = uiState.isClipCutting,
                        onCutAndSave = { startMs, endMs ->
                            viewModel.cutAndSaveClip(startMs, endMs)
                        },
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

private fun buildVideoColorRenderEffect(
    filters: DisplayFilterSettings
): androidx.compose.ui.graphics.RenderEffect? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
    val hasAdjustment = abs(filters.brightnessOffset) > 0.01f ||
        abs(filters.contrast - 1.0f) > 0.01f ||
        abs(filters.saturation - 1.0f) > 0.01f
    if (!hasAdjustment) return null

    val satMatrix = ColorMatrix().apply { setSaturation(filters.saturation.coerceIn(0f, 2f)) }
    val c = filters.contrast.coerceIn(0.5f, 1.8f)
    val t = (-0.5f * c + 0.5f) * 255f + (filters.brightnessOffset.coerceIn(-0.5f, 0.5f) * 255f)
    val contrastBrightnessMatrix = ColorMatrix(
        floatArrayOf(
            c, 0f, 0f, 0f, t,
            0f, c, 0f, 0f, t,
            0f, 0f, c, 0f, t,
            0f, 0f, 0f, 1f, 0f
        )
    )
    val combined = ColorMatrix()
    combined.postConcat(satMatrix)
    combined.postConcat(contrastBrightnessMatrix)

    return runCatching {
        RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(combined)).asComposeRenderEffect()
    }.getOrNull()
}
