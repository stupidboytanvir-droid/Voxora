package com.voxora.player.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.voxora.player.dubbing.CharacterVoiceProfile
import com.voxora.player.dubbing.whisper.TranscriptExporter
import com.voxora.player.ui.theme.VoxoraCharcoal
import com.voxora.player.ui.theme.VoxoraSubtleBlue
import kotlin.math.abs

/**
 * Distinct high-contrast color palette for color-coded Character badges on dark surfaces.
 */
private val CharacterAccentPalette = listOf(
    Color(0xFFA78BFA), // Electric Violet
    Color(0xFF38BDF8), // Sky Cyan
    Color(0xFF4ADE80), // Emerald
    Color(0xFFFBBF24), // Amber Gold
    Color(0xFFFB7185), // Rose Coral
    Color(0xFF2DD4BF), // Teal
    Color(0xFFFB923C), // Warm Orange
    Color(0xFFE879F9)  // Orchid Pink
)

fun characterAccentColor(
    characterId: String?,
    characters: List<CharacterVoiceProfile>
): Color {
    if (characterId.isNullOrBlank()) return Color.White.copy(alpha = 0.5f)
    val index = characters.indexOfFirst { it.characterId == characterId }
    val slot = if (index >= 0) index else abs(characterId.hashCode())
    return CharacterAccentPalette[slot % CharacterAccentPalette.size]
}

fun speakerIndexAccentColor(speakerIndex: Int): Color {
    return CharacterAccentPalette[speakerIndex.coerceAtLeast(0) % CharacterAccentPalette.size]
}

/**
 * Compact color-coded Character pill displayed next to each Transcript & Bengali Translation line.
 */
@Composable
fun DialogueCharacterBadge(
    character: CharacterVoiceProfile?,
    characters: List<CharacterVoiceProfile>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val accent = characterAccentColor(character?.characterId, characters)
    Surface(
        color = if (character != null) accent.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.06f),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(
            1.dp,
            if (character != null) accent.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.16f)
        ),
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(accent)
            )
            Text(
                text = character?.label ?: "Assign Speaker",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = if (character != null) accent else Color.White.copy(alpha = 0.68f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Icon(
                imageVector = Icons.Rounded.SwapHoriz,
                contentDescription = "Change character",
                tint = accent.copy(alpha = 0.85f),
                modifier = Modifier.size(12.dp)
            )
        }
    }
}

/**
 * Modal dialog allowing a user to manually override the assigned VOXORA Character for any
 * single dialogue line, with a convenient one-tap seek shortcut to that segment's timestamp.
 */
@Composable
fun CharacterOverrideDialog(
    segmentStartMs: Long,
    dialoguePreview: String,
    currentCharacterId: String?,
    characters: List<CharacterVoiceProfile>,
    onSelectCharacter: (String) -> Unit,
    onSeekToSegment: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = VoxoraCharcoal,
        titleContentColor = Color.White,
        textContentColor = Color.White,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Assign Dialogue Character",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                )
                Text(
                    text = "${TranscriptExporter.formatReadableTimestamp(segmentStartMs)} · \"${dialoguePreview.take(90)}\"",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.72f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (characters.isEmpty()) {
                    Text(
                        text = "No VOXORA characters found. Add characters in the Characters tab first.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.72f)
                    )
                } else {
                    characters.forEach { profile ->
                        val isSelected = profile.characterId == currentCharacterId
                        val accent = characterAccentColor(profile.characterId, characters)
                        Surface(
                            onClick = {
                                onSelectCharacter(profile.characterId)
                                onDismiss()
                            },
                            color = if (isSelected) accent.copy(alpha = 0.2f) else Color(0xFF14151E),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(
                                1.dp,
                                if (isSelected) accent else Color.White.copy(alpha = 0.1f)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .testTag("override_character_option_${profile.characterId}")
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
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .clip(CircleShape)
                                            .background(accent)
                                    )
                                    Column {
                                        Text(
                                            text = profile.label,
                                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                            color = Color.White,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "${profile.gender.label} · ${profile.speakerId}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color.White.copy(alpha = 0.65f)
                                        )
                                    }
                                }
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Rounded.CheckCircle,
                                        contentDescription = "Assigned",
                                        tint = accent,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = VoxoraSubtleBlue)
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = {
                    onSeekToSegment()
                    onDismiss()
                },
                border = BorderStroke(1.dp, VoxoraSubtleBlue.copy(alpha = 0.55f))
            ) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Seek Here")
            }
        }
    )
}
