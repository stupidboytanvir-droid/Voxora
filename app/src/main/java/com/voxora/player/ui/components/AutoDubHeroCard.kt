package com.voxora.player.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.PlayArrow
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.voxora.player.ai.dubbing.AutoDubPipelineState
import com.voxora.player.ai.dubbing.AutoDubStage
import com.voxora.player.ai.dubbing.LiveDubbingPlaybackState
import com.voxora.player.ui.theme.TabularTimeStyle
import com.voxora.player.ui.theme.VoxoraElectricPurple
import com.voxora.player.ui.theme.VoxoraElectricPurpleBright
import com.voxora.player.ui.theme.VoxoraSubtleBlue

/**
 * Prominent Phase 6 "One-Click Auto-Dub Video (English → বাংলা)" card for the Controls tab
 * of AI Dubbing Studio.
 *
 * Chains Transcribe -> Diarize -> Translate -> Bengali VITS TTS -> Live Synchronized Playback
 * with stage-by-stage progress, cancellation, and live playback status pill.
 */
@Composable
fun AutoDubHeroCard(
    hasActiveVideo: Boolean,
    allModelsInstalled: Boolean,
    autoDubState: AutoDubPipelineState,
    liveDubbingState: LiveDubbingPlaybackState,
    fastMovieModeEnabled: Boolean = true,
    onStartAutoDub: () -> Unit,
    onCancelAutoDub: () -> Unit
) {
    val emerald = Color(0xFF4ADE80)
    val accentBorder = when {
        autoDubState.isLiveChunkStreaming || liveDubbingState.isActive -> emerald.copy(alpha = 0.72f)
        autoDubState.isRunning -> VoxoraSubtleBlue
        else -> VoxoraElectricPurple.copy(alpha = 0.7f)
    }

    Surface(
        color = Color(0xFF171828),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.2.dp, accentBorder),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("auto_dub_hero_card")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        listOf(
                            VoxoraElectricPurple.copy(alpha = 0.14f),
                            VoxoraSubtleBlue.copy(alpha = 0.08f),
                            Color.Transparent
                        )
                    )
                )
                .padding(15.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(11.dp))
                            .background(
                                Brush.linearGradient(listOf(VoxoraElectricPurple, VoxoraSubtleBlue))
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.AutoAwesome,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Column {
                        Text(
                            text = if (fastMovieModeEnabled) {
                                "Fast Live Bengali Auto-Dub"
                            } else {
                                "Multi-Speaker Live Auto-Dub"
                            },
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = Color.White
                        )
                        Text(
                            text = if (fastMovieModeEnabled) {
                                "30s rolling chunks · Whisper tiny → বাংলা → VITS · Starts in seconds"
                            } else {
                                "30s rolling chunks · Whisper → Diarize → বাংলা → VITS"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.75f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                if (liveDubbingState.isActive || autoDubState.isLiveChunkStreaming) {
                    Surface(
                        color = emerald.copy(alpha = 0.18f),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, emerald.copy(alpha = 0.55f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.GraphicEq,
                                contentDescription = null,
                                tint = emerald,
                                modifier = Modifier.size(12.dp)
                            )
                            Text(
                                text = when {
                                    liveDubbingState.isOriginalDucked -> "LIVE · DUCKED"
                                    autoDubState.isLiveChunkStreaming && autoDubState.totalChunkCount > 0 ->
                                        "LIVE · ${autoDubState.completedChunkCount}/${autoDubState.totalChunkCount}"
                                    else -> "LIVE SYNC"
                                },
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = emerald
                            )
                        }
                    }
                }
            }

            if (autoDubState.isRunning) {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
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
                                color = if (autoDubState.isLiveChunkStreaming) emerald else VoxoraSubtleBlue
                            )
                            Text(
                                text = autoDubState.stageLabel,
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                                color = Color.White,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Text(
                            text = "${(autoDubState.overallProgressFraction * 100).toInt()}%",
                            style = TabularTimeStyle,
                            color = if (autoDubState.isLiveChunkStreaming) emerald else VoxoraSubtleBlue
                        )
                    }
                    LinearProgressIndicator(
                        progress = { autoDubState.overallProgressFraction.coerceIn(0f, 1f) },
                        color = if (autoDubState.isLiveChunkStreaming) emerald else VoxoraElectricPurpleBright,
                        trackColor = Color.White.copy(alpha = 0.12f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(7.dp)
                            .clip(RoundedCornerShape(4.dp))
                    )
                    OutlinedButton(
                        onClick = onCancelAutoDub,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.6f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("auto_dub_cancel_button")
                    ) {
                        Icon(Icons.Rounded.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            if (autoDubState.isLiveChunkStreaming) "Stop Live Bengali Dubbing" else "Cancel Auto-Dub"
                        )
                    }
                }
            } else {
                Button(
                    onClick = onStartAutoDub,
                    enabled = hasActiveVideo,
                    colors = ButtonDefaults.buttonColors(containerColor = VoxoraElectricPurple),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("auto_dub_start_button")
                ) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (liveDubbingState.isTrackReady) {
                            "Restart Live Bengali Auto-Dub"
                        } else {
                            "Start Live Bengali Auto-Dub (30s Chunks)"
                        },
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
                    )
                }

                if (!allModelsInstalled) {
                    Text(
                        text = if (fastMovieModeEnabled) {
                            "Tip: Download Whisper tiny (~31 MB), EN→বাংলা & Bengali TTS via One-Click AI Setup above."
                        } else {
                            "Tip: Download all 4 offline AI models via One-Click AI Setup above before starting."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFFBBF24)
                    )
                }
            }

            if (autoDubState.currentStage == AutoDubStage.COMPLETED && !autoDubState.isRunning) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CheckCircle,
                        contentDescription = null,
                        tint = emerald,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = autoDubState.stageLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = emerald
                    )
                }
            }

            if (!autoDubState.errorMessage.isNullOrBlank()) {
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
                            text = autoDubState.errorMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White
                        )
                    }
                }
            }
        }
    }
}
