package com.voxora.player.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.voxora.player.ai.dubbing.DubbedExportState
import com.voxora.player.ai.dubbing.DubbingExportFormat
import com.voxora.player.ui.theme.TabularTimeStyle
import com.voxora.player.ui.theme.VoxoraElectricPurple
import com.voxora.player.ui.theme.VoxoraElectricPurpleBright
import com.voxora.player.ui.theme.VoxoraSubtleBlue

/**
 * Offline Dubbed Video (MP4 pass-through) & Dubbed Audio (M4A / WAV) export card
 * inside the Controls tab of AI Dubbing Studio.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DubbedExportSection(
    hasSynthesizedDubTrack: Boolean,
    exportState: DubbedExportState,
    onExportFormat: (DubbingExportFormat) -> Unit,
    onCancelExport: () -> Unit,
    onOpenExportedMedia: (uriString: String, mimeType: String) -> Unit,
    onShareExportedMedia: (uriString: String, mimeType: String, title: String) -> Unit
) {
    val emerald = Color(0xFF4ADE80)

    Surface(
        color = Color(0xFF181924),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(
            1.dp,
            when {
                exportState.isExporting -> VoxoraSubtleBlue.copy(alpha = 0.7f)
                exportState.hasCompletedExport -> emerald.copy(alpha = 0.55f)
                else -> Color.White.copy(alpha = 0.09f)
            }
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("dubbed_export_section")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.SaveAlt,
                        contentDescription = null,
                        tint = VoxoraSubtleBlue,
                        modifier = Modifier.size(19.dp)
                    )
                    Column {
                        Text(
                            text = "Offline Dubbed Export",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = Color.White
                        )
                        Text(
                            text = "Saves MP4 to Movies/VOXORA & Audio to Music/VOXORA (fast video pass-through)",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.68f)
                        )
                    }
                }
            }

            if (exportState.isExporting) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = VoxoraSubtleBlue
                            )
                            Text(
                                text = exportState.statusMessage,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Text(
                            text = "${(exportState.progressFraction * 100).toInt()}%",
                            style = TabularTimeStyle,
                            color = VoxoraSubtleBlue
                        )
                    }
                    LinearProgressIndicator(
                        progress = { exportState.progressFraction.coerceIn(0f, 1f) },
                        color = VoxoraSubtleBlue,
                        trackColor = Color.White.copy(alpha = 0.1f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(4.dp))
                    )
                    OutlinedButton(
                        onClick = onCancelExport,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("export_cancel_button")
                    ) {
                        Icon(Icons.Rounded.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Cancel Export")
                    }
                }
            } else {
                Button(
                    onClick = { onExportFormat(DubbingExportFormat.MP4_VIDEO) },
                    enabled = hasSynthesizedDubTrack,
                    colors = ButtonDefaults.buttonColors(containerColor = VoxoraElectricPurple),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("export_dubbed_mp4_button")
                ) {
                    Icon(Icons.Rounded.Movie, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Export Dubbed Video (MP4)")
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedButton(
                        onClick = { onExportFormat(DubbingExportFormat.M4A_AUDIO) },
                        enabled = hasSynthesizedDubTrack,
                        border = BorderStroke(1.dp, VoxoraSubtleBlue.copy(alpha = 0.6f)),
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("export_dubbed_m4a_button")
                    ) {
                        Icon(Icons.Rounded.AudioFile, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Export Audio (M4A)")
                    }

                    OutlinedButton(
                        onClick = { onExportFormat(DubbingExportFormat.WAV_AUDIO) },
                        enabled = hasSynthesizedDubTrack,
                        border = BorderStroke(1.dp, VoxoraElectricPurpleBright.copy(alpha = 0.55f)),
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("export_dubbed_wav_button")
                    ) {
                        Icon(Icons.Rounded.AudioFile, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Export Audio (WAV)")
                    }
                }

                if (!hasSynthesizedDubTrack) {
                    Text(
                        text = "Run Auto-Dub Video above (or synthesize Bengali lines in Voice / TTS) to unlock offline MP4 & Audio export.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.62f)
                    )
                }
            }

            if (exportState.hasCompletedExport) {
                val uriStr = exportState.exportedUriString.orEmpty()
                val mime = exportState.exportedMimeType ?: "video/mp4"
                val name = exportState.exportedDisplayName ?: "VOXORA Dubbed Media"
                Surface(
                    color = emerald.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, emerald.copy(alpha = 0.45f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(11.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.CheckCircle,
                                contentDescription = null,
                                tint = emerald,
                                modifier = Modifier.size(17.dp)
                            )
                            Text(
                                text = exportState.statusMessage,
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                                color = emerald
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { onOpenExportedMedia(uriStr, mime) },
                                colors = ButtonDefaults.buttonColors(containerColor = emerald.copy(alpha = 0.25f), contentColor = Color.White),
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                                    .testTag("export_open_button")
                            ) {
                                Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Open")
                            }
                            OutlinedButton(
                                onClick = { onShareExportedMedia(uriStr, mime, name) },
                                border = BorderStroke(1.dp, emerald.copy(alpha = 0.65f)),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = emerald),
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                                    .testTag("export_share_button")
                            ) {
                                Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Share")
                            }
                        }
                    }
                }
            }

            if (!exportState.errorMessage.isNullOrBlank()) {
                Surface(
                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.14f),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.45f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = exportState.errorMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White
                        )
                    }
                }
            }
        }
    }
}
