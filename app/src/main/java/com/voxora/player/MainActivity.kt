package com.voxora.player

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voxora.player.data.update.UpdateNotificationHelper
import com.voxora.player.model.UserPreferences
import com.voxora.player.navigation.VoxoraApp
import com.voxora.player.ui.theme.VoxoraTheme

class MainActivity : ComponentActivity() {

    private var incomingVideoUri by mutableStateOf<Uri?>(null)
    private var openUpdateDialogRequest by mutableStateOf(false)
    private var isInPipMode by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)

        val app = application as VoxoraApplication
        setContent {
            val prefs by app.settingsRepository.preferencesFlow
                .collectAsStateWithLifecycle(initialValue = UserPreferences())

            VoxoraTheme(themeMode = prefs.themeMode) {
                VoxoraApp(
                    incomingVideoUri = incomingVideoUri,
                    onIncomingUriConsumed = { incomingVideoUri = null },
                    openUpdateDialogRequest = openUpdateDialogRequest,
                    onUpdateDialogRequestConsumed = { openUpdateDialogRequest = false },
                    isInPipMode = isInPipMode,
                    onEnterPipMode = { width, height -> enterPlayerPipMode(width, height) }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isInPipMode = isInPictureInPictureMode
    }

    fun enterPlayerPipMode(videoWidth: Int, videoHeight: Int): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        return runCatching {
            val aspectRatio = computeSafePipRatio(videoWidth, videoHeight)
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(aspectRatio)
                .build()
            enterPictureInPictureMode(params)
        }.getOrDefault(false)
    }

    private fun computeSafePipRatio(videoWidth: Int, videoHeight: Int): Rational {
        if (videoWidth <= 0 || videoHeight <= 0) return Rational(16, 9)
        val raw = videoWidth.toFloat() / videoHeight.toFloat()
        // Android PiP requires aspect ratio strictly within [0.418410, 2.39]
        val clamped = raw.coerceIn(0.45f, 2.35f)
        val scaledW = (clamped * 1000f).toInt().coerceAtLeast(450)
        return Rational(scaledW, 1000)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(UpdateNotificationHelper.EXTRA_OPEN_UPDATE_DIALOG, false) == true) {
            openUpdateDialogRequest = true
            intent.removeExtra(UpdateNotificationHelper.EXTRA_OPEN_UPDATE_DIALOG)
        }
        if (intent?.action == Intent.ACTION_VIEW) {
            intent.data?.let { uri ->
                incomingVideoUri = uri
            }
        }
    }
}
