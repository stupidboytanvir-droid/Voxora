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
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
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
import androidx.compose.ui.unit.dp
import com.voxora.player.ai.tts.OneClickAiSetupState
import com.voxora.player.ui.theme.TabularTimeStyle
import com.voxora.player.ui.theme.VoxoraElectricPurple
import com.voxora.player.ui.theme.VoxoraElectricPurpleBright
import com.voxora.player.ui.theme.VoxoraSubtleBlue

/**
 * Unified Phase 5 "One-Click AI Setup" banner placed right below the AI Dubbing Studio header.
 *
 * - When any of the 4 offline AI models (Whisper ASR, Sherpa-ONNX Diarization, ML Kit EN→বাংলা,
 *   Sherpa-ONNX VITS Bengali TTS) is missing, renders a single "Download All Missing Models" button
 *   with live combined step & percentage progress.
 * - Once all 4 models are ready, collapses into a compact green "All Offline AI Models Ready" badge.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OneClickAiSetupCard(
    state: OneClickAiSetupState,
    onStartOneClickSetup: () -> Unit,
    onCancelOneClickSetup: () -> Unit
) {
    val emerald = Color(0xFF4ADE80)

    if (state.allModelsReady && !state.isRunning) {
        Surface(
            color = emerald.copy(alpha = 0.12f),
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, emerald.copy(alpha = 0.45f)),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("one_click_all_ready_badge")
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CheckCircle,
                        contentDescription = null,
                        tint = emerald,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "All Offline AI Models Ready",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = emerald
                    )
                }
                Surface(
                    color = emerald.copy(alpha = 0.18f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = "4/4 OFFLINE",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = emerald,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                    )
                }
            }
        }
        return
    }

    Surface(
        color = Color(0xFF171A29),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(
            1.dp,
            if (state.isRunning) VoxoraSubtleBlue.copy(alpha = 0.7f) else VoxoraElectricPurple.copy(alpha = 0.55f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("one_click_ai_setup_card")
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
                        imageVector = Icons.Rounded.AutoAwesome,
                        contentDescription = null,
                        tint = VoxoraElectricPurpleBright,
                        modifier = Modifier.size(19.dp)
                    )
                    Column {
                        Text(
                            text = "One-Click AI Setup",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = Color.White
                        )
                        Text(
                            text = "${state.readyModelCount}/4 offline models installed · ${state.missingModelCount} missing",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.72f)
                        )
                    }
                }
                Surface(
                    color = VoxoraElectricPurple.copy(alpha = 0.22f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = "100% FREE",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = VoxoraElectricPurpleBright,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                    )
                }
            }

            // Compact 4-model readiness chips
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                SetupModelPill("Whisper ASR", state.isWhisperReady)
                SetupModelPill("Diarization", state.isDiarizationReady)
                SetupModelPill("EN→বাংলা", state.isTranslationReady)
                SetupModelPill("Bengali TTS", state.isBengaliTtsReady)
            }

            if (state.isRunning) {
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
                                modifier = Modifier.size(15.dp),
                                strokeWidth = 2.dp,
                                color = VoxoraSubtleBlue
                            )
                            Text(
                                text = "Downloading ${state.currentStepIndex}/${state.totalSteps}: ${state.currentStepLabel}... ${state.currentStepProgressPercent}%",
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                                color = Color.White
                            )
                        }
                        Text(
                            text = "${(state.overallProgressFraction * 100).toInt()}%",
                            style = TabularTimeStyle,
                            color = VoxoraSubtleBlue
                        )
                    }
                    LinearProgressIndicator(
                        progress = { state.overallProgressFraction.coerceIn(0f, 1f) },
                        color = VoxoraSubtleBlue,
                        trackColor = Color.White.copy(alpha = 0.1f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(4.dp))
                    )
                    OutlinedButton(
                        onClick = onCancelOneClickSetup,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("one_click_setup_cancel_button")
                    ) {
                        Icon(Icons.Rounded.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Cancel Setup")
                    }
                }
            } else {
                Button(
                    onClick = onStartOneClickSetup,
                    colors = ButtonDefaults.buttonColors(containerColor = VoxoraElectricPurple),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("one_click_download_all_button")
                ) {
                    Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (state.missingModelCount == 1) {
                            "Download Remaining Missing Model"
                        } else {
                            "Download All Missing Models (${state.missingModelCount})"
                        }
                    )
                }
            }

            if (!state.errorMessage.isNullOrBlank()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = state.errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
private fun SetupModelPill(
    label: String,
    ready: Boolean
) {
    val color = if (ready) Color(0xFF4ADE80) else Color(0xFFFBBF24)
    Surface(
        color = color.copy(alpha = 0.12f),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.35f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = if (ready) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(12.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                color = color
            )
        }
    }
}
