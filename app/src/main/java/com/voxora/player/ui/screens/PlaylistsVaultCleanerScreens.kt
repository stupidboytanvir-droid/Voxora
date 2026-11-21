package com.voxora.player.ui.screens

import android.app.Activity
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Backspace
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.WatchLater
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.voxora.player.R
import com.voxora.player.model.PlaylistEntryItem
import com.voxora.player.model.PlaylistSummary
import com.voxora.player.model.VaultVideoItem
import com.voxora.player.model.VideoItem
import com.voxora.player.ui.theme.LocalVoxoraColors
import com.voxora.player.ui.theme.TabularTimeStyle
import com.voxora.player.ui.theme.VoxoraCardCharcoal
import com.voxora.player.ui.theme.VoxoraCharcoal
import com.voxora.player.ui.theme.VoxoraCyan
import com.voxora.player.ui.theme.VoxoraElectricPurple
import com.voxora.player.ui.theme.VoxoraElectricPurpleBright
import com.voxora.player.util.FormatUtils
import com.voxora.player.util.PrivateVaultManager
import com.voxora.player.util.StorageCleanerEngine
import com.voxora.player.util.StorageCleanerReport
import java.io.File

/**
 * Playlists & Watch Later Tab rendered inside HomeScreen.
 */
@Composable
fun PlaylistsWatchLaterTabContent(
    playlists: List<PlaylistSummary>,
    allPlaylistItems: List<PlaylistEntryItem>,
    onCreatePlaylist: (String) -> Unit,
    onDeletePlaylist: (Long) -> Unit,
    onPlayEntry: (PlaylistEntryItem) -> Unit,
    onRemoveEntry: (playlistId: Long, videoUri: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val voxoraColors = LocalVoxoraColors.current
    var showCreateDialog by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }
    var selectedPlaylistId by remember { mutableStateOf<Long?>(null) }

    val selectedPlaylist = playlists.firstOrNull { it.id == selectedPlaylistId }
    val selectedEntries = remember(selectedPlaylistId, allPlaylistItems) {
        if (selectedPlaylistId == null) emptyList()
        else allPlaylistItems.filter { it.playlistId == selectedPlaylistId }
    }

    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("New Custom Playlist") },
            text = {
                OutlinedTextField(
                    value = newPlaylistName,
                    onValueChange = { newPlaylistName = it },
                    placeholder = { Text("e.g. Action Movies, Anime, Study...") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("create_playlist_name_input")
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val trimmed = newPlaylistName.trim()
                        if (trimmed.isNotEmpty()) {
                            onCreatePlaylist(trimmed)
                            newPlaylistName = ""
                            showCreateDialog = false
                        }
                    },
                    modifier = Modifier.testTag("create_playlist_confirm_button")
                ) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (selectedPlaylist != null) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { selectedPlaylistId = null }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back to playlists")
                    }
                    Column {
                        Text(selectedPlaylist.name, style = MaterialTheme.typography.titleLarge)
                        Text(
                            text = "${selectedEntries.size} video(s)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (!selectedPlaylist.isWatchLater) {
                    IconButton(onClick = {
                        onDeletePlaylist(selectedPlaylist.id)
                        selectedPlaylistId = null
                    }) {
                        Icon(
                            imageVector = Icons.Rounded.DeleteOutline,
                            contentDescription = "Delete playlist",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            if (selectedEntries.isEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "No videos in this playlist yet. Tap the 3-dot menu on any video in Home or Videos to add it here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(18.dp)
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    items(selectedEntries, key = { it.id }) { entry ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .clickable { onPlayEntry(entry) },
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                            border = BorderStroke(1.dp, voxoraColors.cardBorder.copy(alpha = 0.7f))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(40.dp)
                                            .clip(CircleShape)
                                            .background(VoxoraElectricPurple.copy(alpha = 0.2f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = VoxoraCyan)
                                    }
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = entry.title,
                                            style = MaterialTheme.typography.titleSmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "${entry.folderName} · ${FormatUtils.formatDuration(entry.durationMs)}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                IconButton(onClick = { onRemoveEntry(entry.playlistId, entry.videoUri) }) {
                                    Icon(
                                        imageVector = Icons.Rounded.DeleteOutline,
                                        contentDescription = "Remove from playlist",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Playlists & Watch Later", style = MaterialTheme.typography.titleLarge)
                    Text(
                        text = "Organize movies, series, and saved queues",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Button(
                    onClick = { showCreateDialog = true },
                    colors = ButtonDefaults.buttonColors(containerColor = VoxoraElectricPurple),
                    modifier = Modifier.testTag("new_playlist_button")
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("New")
                }
            }
        }

        items(playlists, key = { it.id }) { pl ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { selectedPlaylistId = pl.id }
                    .testTag("playlist_card_${pl.id}"),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                border = BorderStroke(
                    1.dp,
                    if (pl.isWatchLater) VoxoraCyan.copy(alpha = 0.55f) else voxoraColors.cardBorder.copy(alpha = 0.7f)
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(46.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (pl.isWatchLater) {
                                        Brush.linearGradient(listOf(VoxoraElectricPurple, VoxoraCyan))
                                    } else {
                                        Brush.linearGradient(
                                            listOf(
                                                VoxoraElectricPurple.copy(alpha = 0.35f),
                                                VoxoraElectricPurple.copy(alpha = 0.15f)
                                            )
                                        )
                                    }
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (pl.isWatchLater) Icons.Rounded.WatchLater else Icons.AutoMirrored.Rounded.PlaylistPlay,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Column {
                            Text(pl.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = "${pl.videoCount} ${if (pl.videoCount == 1) "video" else "videos"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (!pl.isWatchLater) {
                        IconButton(onClick = { onDeletePlaylist(pl.id) }) {
                            Icon(
                                imageVector = Icons.Rounded.DeleteOutline,
                                contentDescription = "Delete playlist",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Private Vault Folder Screen: SHA-256 4-digit PIN lock + optional Android BiometricPrompt.
 */
@Composable
fun PrivateVaultScreen(
    storedPinHash: String,
    biometricEnabled: Boolean,
    vaultVideos: List<VaultVideoItem>,
    allLibraryVideos: List<VideoItem>,
    message: String?,
    onSetPin: (String) -> Unit,
    onToggleBiometric: (Boolean) -> Unit,
    onHideVideo: (VideoItem) -> Unit,
    onRestoreVideo: (VaultVideoItem) -> Unit,
    onDeleteVaultVideo: (VaultVideoItem) -> Unit,
    onPlayVaultVideo: (VaultVideoItem) -> Unit,
    onClearMessage: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val biometricSupported = remember(context) { PrivateVaultManager.isBiometricAvailable(context) }

    var isUnlocked by remember { mutableStateOf(false) }
    var pinInput by remember { mutableStateOf("") }
    var pendingFirstPin by remember { mutableStateOf<String?>(null) }
    var pinError by remember { mutableStateOf<String?>(null) }
    var showAddVideoDialog by remember { mutableStateOf(false) }

    LaunchedEffect(message) {
        if (message != null) {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(message)
            onClearMessage()
        }
    }

    // Automatically prompt biometric unlock if user enabled it
    LaunchedEffect(storedPinHash, biometricEnabled, isUnlocked) {
        if (storedPinHash.isNotBlank() && biometricEnabled && biometricSupported && !isUnlocked) {
            PrivateVaultManager.promptBiometricUnlock(
                context = context,
                onSuccess = { isUnlocked = true },
                onError = { /* User can fall back to 4-digit PIN */ }
            )
        }
    }

    fun submitDigit(digit: String) {
        pinError = null
        if (pinInput.length >= 4) return
        val next = pinInput + digit
        pinInput = next
        if (next.length == 4) {
            if (storedPinHash.isBlank()) {
                val first = pendingFirstPin
                if (first == null) {
                    pendingFirstPin = next
                    pinInput = ""
                } else if (first == next) {
                    onSetPin(next)
                    pendingFirstPin = null
                    pinInput = ""
                    isUnlocked = true
                } else {
                    pinError = "PINs did not match. Please try again."
                    pendingFirstPin = null
                    pinInput = ""
                }
            } else {
                if (PrivateVaultManager.verifyPin(next, storedPinHash)) {
                    pinInput = ""
                    isUnlocked = true
                } else {
                    pinError = "Incorrect 4-digit PIN"
                    pinInput = ""
                }
            }
        }
    }

    if (showAddVideoDialog) {
        AlertDialog(
            onDismissRequest = { showAddVideoDialog = false },
            title = { Text("Hide Video in Private Vault") },
            text = {
                if (allLibraryVideos.isEmpty()) {
                    Text("No local videos available in library.")
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 320.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(allLibraryVideos.take(40), key = { it.uriString }) { vid ->
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable {
                                        onHideVideo(vid)
                                        showAddVideoDialog = false
                                    },
                                color = MaterialTheme.colorScheme.surfaceContainer,
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(vid.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        text = "${FormatUtils.formatDuration(vid.durationMs)} · ${FormatUtils.formatFileSize(vid.sizeBytes)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAddVideoDialog = false }) { Text("Close") }
            }
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top App Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                    Column {
                        Text("Private Vault", style = MaterialTheme.typography.titleLarge)
                        Text(
                            text = if (isUnlocked) "Unlocked · ${vaultVideos.size} hidden video(s)" else "PIN & Biometric Protected",
                            style = MaterialTheme.typography.bodySmall,
                            color = VoxoraCyan
                        )
                    }
                }
                if (isUnlocked) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { showAddVideoDialog = true }) {
                            Icon(Icons.Rounded.Add, contentDescription = "Hide video in vault", tint = VoxoraCyan)
                        }
                        IconButton(onClick = { isUnlocked = false }) {
                            Icon(Icons.Rounded.Lock, contentDescription = "Lock vault")
                        }
                    }
                }
            }

            if (!isUnlocked) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(Brush.linearGradient(listOf(VoxoraElectricPurple, VoxoraCyan))),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Rounded.Security, contentDescription = null, tint = Color.White, modifier = Modifier.size(32.dp))
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = when {
                            storedPinHash.isBlank() && pendingFirstPin == null -> "Create 4-Digit Vault PIN"
                            storedPinHash.isBlank() && pendingFirstPin != null -> "Confirm 4-Digit Vault PIN"
                            else -> "Enter Vault PIN"
                        },
                        style = MaterialTheme.typography.titleLarge
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = pinError ?: "Hidden videos stay inside VOXORA's private app storage.",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (pinError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(18.dp))

                    // 4 PIN Dots
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        repeat(4) { idx ->
                            val filled = idx < pinInput.length
                            Box(
                                modifier = Modifier
                                    .size(16.dp)
                                    .clip(CircleShape)
                                    .background(if (filled) VoxoraCyan else Color.White.copy(alpha = 0.2f))
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(24.dp))

                    // Numeric Keypad
                    val rows = listOf(
                        listOf("1", "2", "3"),
                        listOf("4", "5", "6"),
                        listOf("7", "8", "9"),
                        listOf("BIO", "0", "DEL")
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        rows.forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                                row.forEach { key ->
                                    when (key) {
                                        "BIO" -> {
                                            Surface(
                                                onClick = {
                                                    if (storedPinHash.isNotBlank() && biometricSupported) {
                                                        PrivateVaultManager.promptBiometricUnlock(
                                                            context = context,
                                                            onSuccess = { isUnlocked = true },
                                                            onError = { pinError = it }
                                                        )
                                                    }
                                                },
                                                color = MaterialTheme.colorScheme.surfaceContainer,
                                                shape = CircleShape,
                                                modifier = Modifier.size(64.dp)
                                            ) {
                                                Box(contentAlignment = Alignment.Center) {
                                                    Icon(
                                                        imageVector = Icons.Rounded.Fingerprint,
                                                        contentDescription = "Biometric unlock",
                                                        tint = if (storedPinHash.isNotBlank() && biometricSupported) VoxoraCyan else Color.White.copy(alpha = 0.25f)
                                                    )
                                                }
                                            }
                                        }
                                        "DEL" -> {
                                            Surface(
                                                onClick = {
                                                    if (pinInput.isNotEmpty()) pinInput = pinInput.dropLast(1)
                                                },
                                                color = MaterialTheme.colorScheme.surfaceContainer,
                                                shape = CircleShape,
                                                modifier = Modifier.size(64.dp)
                                            ) {
                                                Box(contentAlignment = Alignment.Center) {
                                                    Icon(Icons.Rounded.Backspace, contentDescription = "Delete digit")
                                                }
                                            }
                                        }
                                        else -> {
                                            Surface(
                                                onClick = { submitDigit(key) },
                                                color = MaterialTheme.colorScheme.surfaceContainer,
                                                shape = CircleShape,
                                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f)),
                                                modifier = Modifier
                                                    .size(64.dp)
                                                    .testTag("vault_pin_key_$key")
                                            ) {
                                                Box(contentAlignment = Alignment.Center) {
                                                    Text(key, style = MaterialTheme.typography.titleLarge)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (biometricSupported) {
                        item {
                            Card(
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Icon(Icons.Rounded.Fingerprint, contentDescription = null, tint = VoxoraCyan)
                                        Column {
                                            Text("Biometric Unlock", style = MaterialTheme.typography.titleSmall)
                                            Text(
                                                text = "Use fingerprint or face to open vault",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                    Switch(
                                        checked = biometricEnabled,
                                        onCheckedChange = onToggleBiometric
                                    )
                                }
                            }
                        }
                    }

                    item {
                        Button(
                            onClick = { showAddVideoDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = VoxoraElectricPurple),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("vault_add_video_button")
                        ) {
                            Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Hide Video in Private Vault")
                        }
                    }

                    if (vaultVideos.isEmpty()) {
                        item {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceContainer,
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = "Your Private Vault is empty. Hidden videos are stored privately inside VOXORA and excluded from gallery apps.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(18.dp)
                                )
                            }
                        }
                    } else {
                        items(vaultVideos, key = { it.id }) { item ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(16.dp))
                                    .clickable { onPlayVaultVideo(item) },
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = item.originalTitle,
                                            style = MaterialTheme.typography.titleSmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "${FormatUtils.formatDuration(item.durationMs)} · ${FormatUtils.formatFileSize(item.sizeBytes)}",
                                            style = TabularTimeStyle,
                                            color = VoxoraCyan
                                        )
                                    }
                                    Row {
                                        IconButton(onClick = { onRestoreVideo(item) }) {
                                            Icon(Icons.Rounded.Restore, contentDescription = "Restore to Movies", tint = VoxoraCyan)
                                        }
                                        IconButton(onClick = { onDeleteVaultVideo(item) }) {
                                            Icon(Icons.Rounded.DeleteOutline, contentDescription = "Delete permanently", tint = MaterialTheme.colorScheme.error)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp)
        )
    }
}

/**
 * Duplicate & Large File (> 500 MB) Storage Cleaner Screen.
 */
@Composable
fun StorageCleanerScreen(
    report: StorageCleanerReport,
    message: String?,
    onScan: () -> Unit,
    onDeleteLegacy: (List<Uri>) -> Unit,
    onDeleteConfirmed: () -> Unit,
    onPlayVideo: (VideoItem) -> Unit,
    onClearMessage: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    val deleteIntentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            onDeleteConfirmed()
        }
    }

    LaunchedEffect(Unit) {
        if (!report.hasScanned && !report.isScanning) {
            onScan()
        }
    }

    LaunchedEffect(message) {
        if (message != null) {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(message)
            onClearMessage()
        }
    }

    fun requestSafeDelete(videos: List<VideoItem>) {
        val uris = videos.map { Uri.parse(it.uriString) }
        if (uris.isEmpty()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val sender = StorageCleanerEngine.buildDeleteRequestIntentSender(context, uris)
            if (sender != null) {
                deleteIntentLauncher.launch(IntentSenderRequest.Builder(sender).build())
                return
            }
        }
        onDeleteLegacy(uris)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                    Column {
                        Text("Storage Cleaner", style = MaterialTheme.typography.titleLarge)
                        Text(
                            text = "Duplicate detector & large files (> 500 MB)",
                            style = MaterialTheme.typography.bodySmall,
                            color = VoxoraCyan
                        )
                    }
                }
                TextButton(onClick = onScan, enabled = !report.isScanning) {
                    Text("Rescan")
                }
            }

            if (report.isScanning) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(color = VoxoraCyan)
                        Text("Scanning sizes & partial SHA-256 hashes...")
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // Summary Banner
                    item {
                        Card(
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                            border = BorderStroke(1.dp, VoxoraCyan.copy(alpha = 0.45f))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text("Reclaimable Duplicate Space", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(
                                        text = FormatUtils.formatFileSize(report.totalDuplicateReclaimableBytes).ifBlank { "0 MB" },
                                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                                        color = VoxoraCyan
                                    )
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text("Large Files (>500 MB)", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(
                                        text = "${report.largeVideos.size} file(s)",
                                        style = MaterialTheme.typography.titleLarge,
                                        color = VoxoraElectricPurpleBright
                                    )
                                }
                            }
                        }
                    }

                    // Exact Duplicates Section
                    item {
                        Text("Exact Duplicate Groups (${report.duplicateGroups.size})", style = MaterialTheme.typography.titleMedium)
                    }

                    if (report.duplicateGroups.isEmpty()) {
                        item {
                            Text(
                                text = "No exact duplicate videos detected.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        items(report.duplicateGroups, key = { it.hashKey }) { group ->
                            Card(
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                            ) {
                                Column(
                                    modifier = Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "${group.videos.size} identical copies · ${FormatUtils.formatFileSize(group.sizeBytes)} each",
                                            style = MaterialTheme.typography.labelLarge,
                                            color = VoxoraCyan
                                        )
                                        TextButton(onClick = { requestSafeDelete(group.videos.drop(1)) }) {
                                            Text("Clean Copies", color = MaterialTheme.colorScheme.error)
                                        }
                                    }
                                    group.videos.forEachIndexed { idx, vid ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable { onPlayVideo(vid) }
                                                .padding(vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = if (idx == 0) "${vid.title} (Keep)" else vid.title,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    text = vid.folderName,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            IconButton(onClick = { requestSafeDelete(listOf(vid)) }) {
                                                Icon(Icons.Rounded.DeleteOutline, contentDescription = "Delete video", tint = MaterialTheme.colorScheme.error)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Large Videos > 500 MB
                    item {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text("Large Videos > 500 MB (${report.largeVideos.size})", style = MaterialTheme.typography.titleMedium)
                    }

                    if (report.largeVideos.isEmpty()) {
                        item {
                            Text(
                                text = "No videos larger than 500 MB found.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        items(report.largeVideos, key = { "large_${it.uriString}" }) { vid ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .clickable { onPlayVideo(vid) },
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(vid.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(
                                            text = "${FormatUtils.formatFileSize(vid.sizeBytes)} · ${vid.folderName}",
                                            style = TabularTimeStyle,
                                            color = VoxoraCyan
                                        )
                                    }
                                    IconButton(onClick = { requestSafeDelete(listOf(vid)) }) {
                                        Icon(Icons.Rounded.DeleteOutline, contentDescription = "Delete large video", tint = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp)
        )
    }
}

/**
 * Network Stream Dialog: Paste direct MP4, MKV, HLS (.m3u8), or DASH (.mpd) URL.
 */
@Composable
fun StreamUrlDialog(
    onPlayUrl: (url: String, title: String) -> Unit,
    onDismiss: () -> Unit
) {
    var urlInput by remember { mutableStateOf("") }
    var titleInput by remember { mutableStateOf("") }
    var validationError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Link, contentDescription = null, tint = VoxoraCyan) },
        title = { Text("Play Network Stream") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Paste a direct MP4, MKV, HLS (.m3u8), or DASH (.mpd) URL. Works seamlessly with Live Auto AI Subtitles.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = urlInput,
                    onValueChange = {
                        urlInput = it
                        validationError = null
                    },
                    label = { Text("Stream URL") },
                    placeholder = { Text("https://example.com/stream.m3u8") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("stream_url_input")
                )
                OutlinedTextField(
                    value = titleInput,
                    onValueChange = { titleInput = it },
                    label = { Text("Optional Title") },
                    placeholder = { Text("Network Stream") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (validationError != null) {
                    Text(validationError!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val trimmed = urlInput.trim()
                    if (!trimmed.startsWith("http://", ignoreCase = true) &&
                        !trimmed.startsWith("https://", ignoreCase = true) &&
                        !trimmed.startsWith("rtsp://", ignoreCase = true)
                    ) {
                        validationError = "Please enter a valid http://, https://, or rtsp:// URL"
                        return@Button
                    }
                    val resolvedTitle = titleInput.trim().ifBlank {
                        Uri.parse(trimmed).lastPathSegment?.substringBefore('?') ?: "Network Stream"
                    }
                    onPlayUrl(trimmed, resolvedTitle)
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = VoxoraElectricPurple),
                modifier = Modifier.testTag("stream_url_play_button")
            ) {
                Text("Stream Now")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/**
 * Video Options Modal Sheet shown from any video's 3-dot button in Home or Videos.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoOptionsBottomSheet(
    video: VideoItem,
    playlists: List<PlaylistSummary>,
    onAddToWatchLater: (VideoItem) -> Unit,
    onAddToPlaylist: (playlistId: Long, playlistName: String, video: VideoItem) -> Unit,
    onExtractAudio: (VideoItem) -> Unit,
    onHideInVault: (VideoItem) -> Unit,
    onDismiss: () -> Unit
) {
    var pickingPlaylist by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = VoxoraCharcoal,
        contentColor = Color.White
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = video.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            HorizontalDivider(color = Color.White.copy(alpha = 0.1f))

            if (pickingPlaylist) {
                Text("Select Playlist", style = MaterialTheme.typography.titleSmall, color = VoxoraCyan)
                playlists.forEach { pl ->
                    Surface(
                        onClick = {
                            onAddToPlaylist(pl.id, pl.name, video)
                            onDismiss()
                        },
                        color = VoxoraCardCharcoal,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Rounded.PlaylistPlay, contentDescription = null, tint = VoxoraCyan)
                            Text(pl.name, style = MaterialTheme.typography.titleSmall)
                        }
                    }
                }
                TextButton(onClick = { pickingPlaylist = false }) { Text("Back") }
            } else {
                VideoOptionRow(
                    icon = Icons.Rounded.WatchLater,
                    title = "Add to Watch Later",
                    subtitle = "Save to your quick Watch Later queue",
                    onClick = {
                        onAddToWatchLater(video)
                        onDismiss()
                    }
                )
                VideoOptionRow(
                    icon = Icons.AutoMirrored.Rounded.PlaylistAdd,
                    title = "Add to Custom Playlist",
                    subtitle = "Organize into one of your playlists",
                    onClick = { pickingPlaylist = true }
                )
                VideoOptionRow(
                    icon = Icons.Rounded.MusicNote,
                    title = "Extract Audio (.m4a)",
                    subtitle = "Save lossless audio track to Music/VOXORA/",
                    onClick = {
                        onExtractAudio(video)
                        onDismiss()
                    }
                )
                VideoOptionRow(
                    icon = Icons.Rounded.Lock,
                    title = "Hide in Private Vault",
                    subtitle = "Copy into PIN-protected private vault folder",
                    onClick = {
                        onHideInVault(video)
                        onDismiss()
                    }
                )
            }
        }
    }
}

@Composable
private fun VideoOptionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = VoxoraCardCharcoal,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(icon, contentDescription = null, tint = VoxoraCyan, modifier = Modifier.size(22.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall, color = Color.White)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.65f))
            }
        }
    }
}
