package com.voxora.player.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.voxora.player.R
import com.voxora.player.data.update.AppReleaseInfo
import com.voxora.player.data.update.UpdateUiState
import com.voxora.player.ui.theme.LocalVoxoraColors
import com.voxora.player.ui.theme.TabularTimeStyle
import com.voxora.player.util.FormatUtils

@Composable
fun UpdateAvailableBanner(
    state: UpdateUiState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val voxoraColors = LocalVoxoraColors.current
    val (title, subtitle, actionLabel) = when (state) {
        is UpdateUiState.UpdateAvailable -> Triple(
            "Update v${state.release.versionName} Available",
            "Tap to download & install the latest VOXORA build",
            "Update"
        )
        is UpdateUiState.Downloading -> Triple(
            "Downloading v${state.release.versionName} (${(state.progressFraction * 100).toInt()}%)",
            "${FormatUtils.formatFileSize(state.downloadedBytes)} / ${FormatUtils.formatFileSize(state.totalBytes)}",
            "View"
        )
        is UpdateUiState.ReadyToInstall -> Triple(
            "VOXORA v${state.release.versionName} Ready",
            "Download complete · Tap to install now",
            "Install"
        )
        else -> return
    }

    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .testTag("home_update_banner"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.65f))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.SystemUpdate,
                    contentDescription = stringResource(R.string.cd_app_update),
                    tint = voxoraColors.blueHighlight,
                    modifier = Modifier.size(24.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Surface(
                    color = MaterialTheme.colorScheme.primary,
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(
                        text = actionLabel,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }
            if (state is UpdateUiState.Downloading) {
                LinearProgressIndicator(
                    progress = { state.progressFraction },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
fun InAppUpdateDialog(
    state: UpdateUiState,
    canInstallPackages: Boolean,
    onDownload: (AppReleaseInfo) -> Unit,
    onCancelDownload: () -> Unit,
    onInstallApk: (String) -> Unit,
    onOpenUnknownSourcesSettings: () -> Unit,
    onOpenReleasePage: (String) -> Unit,
    onRetryCheck: () -> Unit,
    onDismiss: () -> Unit
) {
    val voxoraColors = LocalVoxoraColors.current

    when (state) {
        is UpdateUiState.UpdateAvailable -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                icon = {
                    Icon(
                        imageVector = Icons.Rounded.NewReleases,
                        contentDescription = stringResource(R.string.cd_app_update),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(30.dp)
                    )
                },
                title = {
                    Text(
                        text = "VOXORA Update Available",
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    text = "v${state.currentVersion} → v${state.release.versionName}",
                                    style = TabularTimeStyle.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }
                            if (state.release.apkSizeBytes > 0L) {
                                Text(
                                    text = FormatUtils.formatFileSize(state.release.apkSizeBytes),
                                    style = TabularTimeStyle,
                                    color = voxoraColors.blueHighlight
                                )
                            }
                        }

                        val notes = state.release.releaseNotes.ifBlank {
                            "Includes the latest improvements, performance upgrades, and fixes for VOXORA."
                        }
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.55f),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 200.dp)
                        ) {
                            Text(
                                text = notes,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .verticalScroll(rememberScrollState())
                                    .padding(12.dp)
                            )
                        }
                    }
                },
                confirmButton = {
                    if (state.release.apkDownloadUrl != null) {
                        Button(
                            onClick = { onDownload(state.release) },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            modifier = Modifier.testTag("update_dialog_download_button")
                        ) {
                            Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Download & Install")
                        }
                    } else {
                        Button(
                            onClick = { onOpenReleasePage(state.release.htmlUrl) },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Open Release")
                        }
                    }
                },
                dismissButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { onOpenReleasePage(state.release.htmlUrl) }) {
                            Text("GitHub")
                        }
                        TextButton(onClick = onDismiss) {
                            Text("Later")
                        }
                    }
                }
            )
        }

        is UpdateUiState.Downloading -> {
            val pct = (state.progressFraction * 100).toInt()
            AlertDialog(
                onDismissRequest = onDismiss,
                icon = {
                    Icon(
                        imageVector = Icons.Rounded.Download,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                },
                title = { Text("Downloading v${state.release.versionName}") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (state.totalBytes > 0L) {
                            LinearProgressIndicator(
                                progress = { state.progressFraction },
                                modifier = Modifier.fillMaxWidth(),
                                color = MaterialTheme.colorScheme.primary
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "${FormatUtils.formatFileSize(state.downloadedBytes)} / ${FormatUtils.formatFileSize(state.totalBytes)}",
                                    style = TabularTimeStyle,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "$pct%",
                                    style = TabularTimeStyle,
                                    color = voxoraColors.blueHighlight
                                )
                            }
                        } else {
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth(),
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "Downloaded ${FormatUtils.formatFileSize(state.downloadedBytes)}...",
                                style = TabularTimeStyle,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = onDismiss) {
                        Text("Run in Background")
                    }
                },
                dismissButton = {
                    OutlinedButton(onClick = onCancelDownload) {
                        Text("Cancel")
                    }
                }
            )
        }

        is UpdateUiState.ReadyToInstall -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                icon = {
                    Icon(
                        imageVector = Icons.Rounded.InstallMobile,
                        contentDescription = null,
                        tint = voxoraColors.success,
                        modifier = Modifier.size(30.dp)
                    )
                },
                title = { Text("Ready to Install v${state.release.versionName}") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = if (canInstallPackages) {
                                "The update APK has finished downloading. Tap Install Now to update VOXORA."
                            } else {
                                "The update APK is ready! Android requires enabling \"Install unknown apps\" for VOXORA once before installing updates directly."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                confirmButton = {
                    if (canInstallPackages) {
                        Button(
                            onClick = { onInstallApk(state.apkFilePath) },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            modifier = Modifier.testTag("update_dialog_install_button")
                        ) {
                            Icon(Icons.Rounded.InstallMobile, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Install Now")
                        }
                    } else {
                        Button(
                            onClick = onOpenUnknownSourcesSettings,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            modifier = Modifier.testTag("update_dialog_allow_install_button")
                        ) {
                            Text("Allow & Install")
                        }
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) {
                        Text("Later")
                    }
                }
            )
        }

        is UpdateUiState.Error -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("Update Check Failed") },
                text = {
                    Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (state.release != null) {
                                onDownload(state.release)
                            } else {
                                onRetryCheck()
                            }
                        }
                    ) {
                        Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Retry")
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) {
                        Text("Close")
                    }
                }
            )
        }

        else -> Unit
    }
}
