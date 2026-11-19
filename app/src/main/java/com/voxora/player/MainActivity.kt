package com.voxora.player

import android.content.Intent
import android.net.Uri
import android.os.Bundle
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
                    onUpdateDialogRequestConsumed = { openUpdateDialogRequest = false }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
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
