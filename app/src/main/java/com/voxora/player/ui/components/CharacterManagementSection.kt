package com.voxora.player.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.voxora.player.dubbing.CharacterVoiceProfile
import com.voxora.player.dubbing.DubbingVoiceCatalog
import com.voxora.player.dubbing.MovieSpeakerAssignment
import com.voxora.player.dubbing.TtsPreviewState
import com.voxora.player.dubbing.VoiceEmotionStyle
import com.voxora.player.dubbing.VoiceGender
import com.voxora.player.ui.theme.TabularTimeStyle
import com.voxora.player.ui.theme.VoxoraCharcoal
import com.voxora.player.ui.theme.VoxoraElectricPurple
import com.voxora.player.ui.theme.VoxoraElectricPurpleBright
import com.voxora.player.ui.theme.VoxoraSubtleBlue
import java.util.Locale

private enum class CharacterCategoryFilter(val label: String) {
    ALL("All"),
    MALE("Male"),
    FEMALE("Female")
}

/**
 * Full-featured VOXORA Character Management UI inside AI Dubbing Studio.
 *
 * Supports unlimited locally persisted characters, Add/Edit/Delete/Assign Voice,
 * genuine Android TTS preview (strictly disabled when no Bengali TTS engine exists),
 * whole-movie voice consistency locks, and Phase 2 speaker diarization readiness.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CharacterManagementSection(
    characters: List<CharacterVoiceProfile>,
    movieAssignments: List<MovieSpeakerAssignment>,
    ttsPreviewState: TtsPreviewState,
    hasActiveMovie: Boolean,
    onAddCharacter: (CharacterVoiceProfile) -> Unit,
    onUpdateCharacter: (CharacterVoiceProfile) -> Unit,
    onDeleteCharacter: (characterId: String) -> Unit,
    onResetDefaultCharacters: () -> Unit,
    onPreviewCharacterVoice: (CharacterVoiceProfile) -> Unit,
    onStopVoicePreview: () -> Unit
) {
    var filter by remember { mutableStateOf(CharacterCategoryFilter.ALL) }
    var showAddDialog by remember { mutableStateOf(false) }
    var editingCharacter by remember { mutableStateOf<CharacterVoiceProfile?>(null) }
    var assigningVoiceCharacter by remember { mutableStateOf<CharacterVoiceProfile?>(null) }
    var deletingCharacter by remember { mutableStateOf<CharacterVoiceProfile?>(null) }

    val filteredCharacters = remember(characters, filter) {
        when (filter) {
            CharacterCategoryFilter.ALL -> characters
            CharacterCategoryFilter.MALE -> characters.filter { it.gender == VoiceGender.MALE }
            CharacterCategoryFilter.FEMALE -> characters.filter { it.gender == VoiceGender.FEMALE }
        }
    }

    val maleCount = remember(characters) { characters.count { it.gender == VoiceGender.MALE } }
    val femaleCount = remember(characters) { characters.count { it.gender == VoiceGender.FEMALE } }
    val lockedCount = remember(characters) { characters.count { it.isVoiceLockedForMovie } }

    // Add Character Dialog
    if (showAddDialog) {
        val nextId = remember(characters) { DubbingVoiceCatalog.nextUniqueCharacterId(characters) }
        val nextSpeaker = remember(characters) { DubbingVoiceCatalog.nextAvailableSpeakerId(characters) }
        val suggestedName = remember(characters) { DubbingVoiceCatalog.nextDefaultCharacterName(characters) }
        val defaultGender = if (maleCount <= femaleCount) VoiceGender.MALE else VoiceGender.FEMALE
        val initialPreset = remember(characters, defaultGender) {
            DubbingVoiceCatalog.nextVoicePresetForGender(defaultGender, characters)
        }
        val draft = remember(nextId, nextSpeaker, suggestedName, defaultGender, initialPreset) {
            CharacterVoiceProfile(
                characterId = nextId,
                speakerId = nextSpeaker,
                label = suggestedName,
                voiceId = initialPreset.id,
                gender = defaultGender,
                pitchFactor = initialPreset.defaultPitch,
                speakingRate = initialPreset.defaultRate,
                emotionStyle = VoiceEmotionStyle.NATURAL,
                emotionIntensity = 0.5f,
                isVoiceLockedForMovie = true,
                createdAtMs = System.currentTimeMillis()
            )
        }
        CharacterEditorDialog(
            title = "Add Movie Character",
            confirmLabel = "Add Character",
            initialProfile = draft,
            onConfirm = { newProfile ->
                onAddCharacter(newProfile)
                showAddDialog = false
            },
            onDismiss = { showAddDialog = false }
        )
    }

    // Edit Character Dialog
    editingCharacter?.let { target ->
        CharacterEditorDialog(
            title = "Edit Character",
            confirmLabel = "Save Changes",
            initialProfile = target,
            onConfirm = { updated ->
                onUpdateCharacter(updated)
                editingCharacter = null
            },
            onDismiss = { editingCharacter = null }
        )
    }

    // Assign Voice Dialog
    assigningVoiceCharacter?.let { target ->
        AssignVoiceDialog(
            character = target,
            onAssign = { newGender, newVoiceId, defaultPitch, defaultRate ->
                onUpdateCharacter(
                    target.copy(
                        gender = newGender,
                        voiceId = newVoiceId,
                        pitchFactor = defaultPitch,
                        speakingRate = defaultRate
                    )
                )
                assigningVoiceCharacter = null
            },
            onDismiss = { assigningVoiceCharacter = null }
        )
    }

    // Delete Confirmation Dialog
    deletingCharacter?.let { target ->
        DeleteCharacterConfirmDialog(
            character = target,
            onConfirmDelete = {
                if (ttsPreviewState.activePreviewCharacterId == target.characterId) {
                    onStopVoicePreview()
                }
                onDeleteCharacter(target.characterId)
                deletingCharacter = null
            },
            onDismiss = { deletingCharacter = null }
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // 1. Cast Summary & Honest Voice Model / TTS Status Card
        Surface(
            color = Color(0xFF151824),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, VoxoraElectricPurple.copy(alpha = 0.35f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Movie Cast (${characters.size} Characters · Unlimited)",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = Color.White
                        )
                        Text(
                            text = "$maleCount Male · $femaleCount Female · $lockedCount Locked for Movie",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.72f)
                        )
                    }
                    TtsStatusPill(ttsPreviewState = ttsPreviewState)
                }

                // Transparent status notice — never pretends multi-character AI voices are active yet
                Surface(
                    color = Color(0xFF10131D),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Info,
                            contentDescription = null,
                            tint = VoxoraSubtleBlue,
                            modifier = Modifier
                                .padding(top = 1.dp)
                                .size(16.dp)
                        )
                        Text(
                            text = "Character profiles are saved locally on your device. Assigned Bengali voice slots are configuration profiles ready for Phase 2 neural voice models — VOXORA does not simulate fake AI voices. ${ttsPreviewState.statusMessage}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.78f)
                        )
                    }
                }
            }
        }

        // 2. Gender Filter Row & Add Character Button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CharacterCategoryFilter.entries.forEach { option ->
                    val count = when (option) {
                        CharacterCategoryFilter.ALL -> characters.size
                        CharacterCategoryFilter.MALE -> maleCount
                        CharacterCategoryFilter.FEMALE -> femaleCount
                    }
                    FilterChip(
                        selected = filter == option,
                        onClick = { filter = option },
                        label = { Text("${option.label} ($count)") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = VoxoraElectricPurple,
                            selectedLabelColor = Color.White,
                            labelColor = Color.White.copy(alpha = 0.82f)
                        ),
                        modifier = Modifier.testTag("character_filter_${option.name.lowercase()}")
                    )
                }
            }

            Button(
                onClick = { showAddDialog = true },
                colors = ButtonDefaults.buttonColors(containerColor = VoxoraElectricPurple),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("dubbing_add_character_button")
            ) {
                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Add")
            }
        }

        // 3. Movie-Wide Voice Consistency Banner
        if (hasActiveMovie && characters.isNotEmpty()) {
            Surface(
                color = Color(0xFF141D22),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Color(0xFF4ADE80).copy(alpha = 0.3f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Lock,
                        contentDescription = null,
                        tint = Color(0xFF4ADE80),
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "Consistent Movie Voice Lock active (${movieAssignments.size.coerceAtLeast(lockedCount)} speaker bindings saved locally for this video).",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.85f)
                    )
                }
            }
        }

        // 4. Character Cards or Empty State
        if (characters.isEmpty()) {
            Surface(
                color = Color(0xFF181924),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Person,
                        contentDescription = null,
                        tint = VoxoraElectricPurpleBright,
                        modifier = Modifier.size(32.dp)
                    )
                    Text(
                        text = "No Characters Configured",
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White
                    )
                    Text(
                        text = "Add custom movie characters or restore the default Male and Female example profiles.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.72f)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = onResetDefaultCharacters,
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("character_restore_defaults_button")
                        ) {
                            Icon(Icons.Rounded.RestartAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Restore Defaults")
                        }
                        Button(
                            onClick = { showAddDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = VoxoraElectricPurple),
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) {
                            Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Add Character")
                        }
                    }
                }
            }
        } else if (filteredCharacters.isEmpty()) {
            Surface(
                color = Color(0xFF181924),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
            ) {
                Text(
                    text = "No ${filter.label.lowercase()} characters yet. Tap Add to create one.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.72f),
                    modifier = Modifier.padding(14.dp)
                )
            }
        } else {
            filteredCharacters.forEach { character ->
                CharacterManagementCard(
                    character = character,
                    ttsPreviewState = ttsPreviewState,
                    onEdit = { editingCharacter = character },
                    onAssignVoice = { assigningVoiceCharacter = character },
                    onPreview = { onPreviewCharacterVoice(character) },
                    onStopPreview = onStopVoicePreview,
                    onDelete = { deletingCharacter = character },
                    onPitchChange = { onUpdateCharacter(character.copy(pitchFactor = it)) },
                    onSpeedChange = { onUpdateCharacter(character.copy(speakingRate = it)) }
                )
            }
        }

        // 5. Phase 2 Speaker Diarization Readiness Footer
        Surface(
            color = Color(0xFF141622),
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, VoxoraSubtleBlue.copy(alpha = 0.28f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.GraphicEq,
                        contentDescription = null,
                        tint = VoxoraSubtleBlue,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "Speaker Diarization Auto-Assignment (Phase 2 Ready)",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = Color.White
                    )
                }
                Text(
                    text = "When offline speaker diarization is integrated, VOXORA will automatically detect each speaker (SPEAKER_01, SPEAKER_02, …) and bind them to these character IDs consistently from start to finish of the movie.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.7f)
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CharacterManagementCard(
    character: CharacterVoiceProfile,
    ttsPreviewState: TtsPreviewState,
    onEdit: () -> Unit,
    onAssignVoice: () -> Unit,
    onPreview: () -> Unit,
    onStopPreview: () -> Unit,
    onDelete: () -> Unit,
    onPitchChange: (Float) -> Unit,
    onSpeedChange: (Float) -> Unit
) {
    val activePreset = DubbingVoiceCatalog.findById(character.voiceId)
    val genderAccent = if (character.gender == VoiceGender.MALE) VoxoraSubtleBlue else VoxoraElectricPurpleBright
    val isPreviewingThis = ttsPreviewState.activePreviewCharacterId == character.characterId

    Surface(
        color = Color(0xFF181924),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(
            width = 1.dp,
            color = if (isPreviewingThis) VoxoraElectricPurpleBright else Color.White.copy(alpha = 0.1f)
        ),
        modifier = Modifier.testTag("character_card_${character.characterId}")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header Row: Avatar + Name + Unique ID + Diarization Speaker Slot
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(genderAccent.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.RecordVoiceOver,
                        contentDescription = "${character.gender.label} character",
                        tint = genderAccent,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        text = character.label,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        MetaBadge(text = character.characterId, accent = VoxoraElectricPurpleBright)
                        MetaBadge(text = character.gender.label, accent = genderAccent)
                        MetaBadge(text = character.speakerId, accent = Color(0xFF94A3B8))
                        if (character.isVoiceLockedForMovie) {
                            MetaBadge(text = "Movie Locked", accent = Color(0xFF4ADE80))
                        }
                    }
                }
            }

            // Assigned Voice Profile Slot Summary (Honest Phase 2 status)
            Surface(
                color = Color(0xFF11131C),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.07f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = activePreset.name,
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Surface(
                            color = Color(0xFFFBBF24).copy(alpha = 0.16f),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = if (activePreset.isNeuralModelInstalled) "Neural Ready" else "Model Pending",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = Color(0xFFFBBF24),
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    Text(
                        text = activePreset.nativeDisplayName,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.7f)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Emotion: ${character.emotionStyle.label} (${(character.emotionIntensity * 100).toInt()}%) · Future AI",
                            style = MaterialTheme.typography.labelSmall,
                            color = VoxoraSubtleBlue
                        )
                    }
                }
            }

            // Quick Inline Pitch & Speed Sliders
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Pitch", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.72f))
                        Text(
                            text = String.format(Locale.US, "%.2fx", character.pitchFactor),
                            style = TabularTimeStyle,
                            color = VoxoraElectricPurpleBright
                        )
                    }
                    Slider(
                        value = character.pitchFactor.coerceIn(0.5f, 1.5f),
                        onValueChange = onPitchChange,
                        valueRange = 0.5f..1.5f,
                        colors = SliderDefaults.colors(
                            thumbColor = VoxoraElectricPurpleBright,
                            activeTrackColor = VoxoraElectricPurple
                        ),
                        modifier = Modifier.testTag("character_pitch_slider_${character.characterId}")
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Speed", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.72f))
                        Text(
                            text = String.format(Locale.US, "%.2fx", character.speakingRate),
                            style = TabularTimeStyle,
                            color = VoxoraSubtleBlue
                        )
                    }
                    Slider(
                        value = character.speakingRate.coerceIn(0.5f, 1.5f),
                        onValueChange = onSpeedChange,
                        valueRange = 0.5f..1.5f,
                        colors = SliderDefaults.colors(
                            thumbColor = VoxoraSubtleBlue,
                            activeTrackColor = VoxoraSubtleBlue
                        ),
                        modifier = Modifier.testTag("character_speed_slider_${character.characterId}")
                    )
                }
            }

            HorizontalDivider(color = Color.White.copy(alpha = 0.07f))

            // Primary Character Actions: Edit, Assign Voice, Preview (Real TTS only), Delete
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onEdit,
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("character_edit_${character.characterId}")
                ) {
                    Icon(Icons.Rounded.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(5.dp))
                    Text("Edit")
                }

                OutlinedButton(
                    onClick = onAssignVoice,
                    border = BorderStroke(1.dp, VoxoraElectricPurple.copy(alpha = 0.7f)),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("character_assign_voice_${character.characterId}")
                ) {
                    Icon(Icons.Rounded.RecordVoiceOver, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(5.dp))
                    Text("Assign Voice")
                }

                // Real TTS Preview button — strictly disabled when no Bengali TTS engine is available
                OutlinedButton(
                    onClick = { if (isPreviewingThis) onStopPreview() else onPreview() },
                    enabled = ttsPreviewState.canPreview,
                    border = BorderStroke(
                        1.dp,
                        when {
                            isPreviewingThis -> Color(0xFF4ADE80)
                            ttsPreviewState.canPreview -> VoxoraSubtleBlue
                            else -> Color.White.copy(alpha = 0.12f)
                        }
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("character_preview_${character.characterId}")
                ) {
                    Icon(
                        imageVector = when {
                            isPreviewingThis -> Icons.Rounded.Stop
                            ttsPreviewState.canPreview -> Icons.AutoMirrored.Rounded.VolumeUp
                            else -> Icons.AutoMirrored.Rounded.VolumeOff
                        },
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = when {
                            isPreviewingThis -> "Stop"
                            ttsPreviewState.isChecking -> "Checking TTS"
                            ttsPreviewState.canPreview -> "Preview TTS"
                            else -> "TTS Unavailable"
                        }
                    )
                }

                OutlinedButton(
                    onClick = onDelete,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.55f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("character_delete_${character.characterId}")
                ) {
                    Icon(Icons.Rounded.DeleteOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Delete")
                }
            }
        }
    }
}

@Composable
private fun TtsStatusPill(ttsPreviewState: TtsPreviewState) {
    val color = when {
        ttsPreviewState.canPreview -> Color(0xFF4ADE80)
        ttsPreviewState.isChecking -> VoxoraSubtleBlue
        else -> Color(0xFFFBBF24)
    }
    val label = when {
        ttsPreviewState.canPreview -> "TTS READY"
        ttsPreviewState.isChecking -> "CHECKING TTS"
        else -> "NO BENGALI TTS"
    }
    Surface(
        color = color.copy(alpha = 0.16f),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.45f))
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun MetaBadge(text: String, accent: Color) {
    Surface(
        color = accent.copy(alpha = 0.16f),
        shape = RoundedCornerShape(6.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = accent,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CharacterEditorDialog(
    title: String,
    confirmLabel: String,
    initialProfile: CharacterVoiceProfile,
    onConfirm: (CharacterVoiceProfile) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember(initialProfile) { mutableStateOf(initialProfile.label) }
    var speakerSlot by remember(initialProfile) { mutableStateOf(initialProfile.speakerId) }
    var gender by remember(initialProfile) { mutableStateOf(initialProfile.gender) }
    var voiceId by remember(initialProfile) { mutableStateOf(initialProfile.voiceId) }
    var pitch by remember(initialProfile) { mutableFloatStateOf(initialProfile.pitchFactor.coerceIn(0.5f, 1.5f)) }
    var speed by remember(initialProfile) { mutableFloatStateOf(initialProfile.speakingRate.coerceIn(0.5f, 1.5f)) }
    var emotion by remember(initialProfile) { mutableStateOf(initialProfile.emotionStyle) }
    var emotionIntensity by remember(initialProfile) { mutableFloatStateOf(initialProfile.emotionIntensity.coerceIn(0f, 1f)) }
    var lockForMovie by remember(initialProfile) { mutableStateOf(initialProfile.isVoiceLockedForMovie) }

    val genderPresets = remember(gender) { DubbingVoiceCatalog.presetsForGender(gender) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = VoxoraCharcoal,
        titleContentColor = Color.White,
        textContentColor = Color.White,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Text(
                    text = "Unique ID: ${initialProfile.characterId}",
                    style = MaterialTheme.typography.labelMedium,
                    color = VoxoraElectricPurpleBright
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Character Name") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("character_editor_name_input")
                )

                OutlinedTextField(
                    value = speakerSlot,
                    onValueChange = { speakerSlot = it.uppercase().replace(" ", "_") },
                    label = { Text("Diarization Speaker Slot (e.g. SPEAKER_01)") },
                    singleLine = true,
                    supportingText = {
                        Text("Used to automatically match this character during Phase 2 speaker diarization")
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("character_editor_speaker_input")
                )

                // Gender Category
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "Voice Category",
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White.copy(alpha = 0.85f)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        VoiceGender.entries.forEach { option ->
                            FilterChip(
                                selected = gender == option,
                                onClick = {
                                    if (gender != option) {
                                        gender = option
                                        val firstPreset = DubbingVoiceCatalog.presetsForGender(option).first()
                                        voiceId = firstPreset.id
                                        pitch = firstPreset.defaultPitch
                                        speed = firstPreset.defaultRate
                                    }
                                },
                                label = { Text(option.label) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = VoxoraElectricPurple,
                                    selectedLabelColor = Color.White
                                ),
                                modifier = Modifier.testTag("character_editor_gender_${option.name.lowercase()}")
                            )
                        }
                    }
                }

                // Voice Profile Slot Assignment
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Individual Voice Profile",
                            style = MaterialTheme.typography.labelLarge,
                            color = Color.White.copy(alpha = 0.85f)
                        )
                        Text(
                            text = "Phase 2 Slot",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFFBBF24)
                        )
                    }
                    genderPresets.forEach { preset ->
                        val selected = preset.id == voiceId
                        Surface(
                            onClick = {
                                voiceId = preset.id
                                pitch = preset.defaultPitch
                                speed = preset.defaultRate
                            },
                            color = if (selected) VoxoraElectricPurple.copy(alpha = 0.22f) else Color(0xFF12131C),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(
                                1.dp,
                                if (selected) VoxoraElectricPurple else Color.White.copy(alpha = 0.08f)
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = preset.name,
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                        color = Color.White
                                    )
                                    Text(
                                        text = preset.nativeDisplayName,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.White.copy(alpha = 0.68f)
                                    )
                                }
                                if (selected) {
                                    Icon(
                                        imageVector = Icons.Rounded.CheckCircle,
                                        contentDescription = "Selected voice profile",
                                        tint = VoxoraElectricPurpleBright,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // Pitch Adjustment
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Voice Pitch", style = MaterialTheme.typography.labelLarge)
                        Text(
                            text = String.format(Locale.US, "%.2fx", pitch),
                            style = TabularTimeStyle,
                            color = VoxoraElectricPurpleBright
                        )
                    }
                    Slider(
                        value = pitch,
                        onValueChange = { pitch = it },
                        valueRange = 0.5f..1.5f,
                        colors = SliderDefaults.colors(
                            thumbColor = VoxoraElectricPurpleBright,
                            activeTrackColor = VoxoraElectricPurple
                        )
                    )
                }

                // Speed Adjustment
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Voice Speed", style = MaterialTheme.typography.labelLarge)
                        Text(
                            text = String.format(Locale.US, "%.2fx", speed),
                            style = TabularTimeStyle,
                            color = VoxoraSubtleBlue
                        )
                    }
                    Slider(
                        value = speed,
                        onValueChange = { speed = it },
                        valueRange = 0.5f..1.5f,
                        colors = SliderDefaults.colors(
                            thumbColor = VoxoraSubtleBlue,
                            activeTrackColor = VoxoraSubtleBlue
                        )
                    )
                }

                // Emotional Voice Settings (Future AI Engine)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.AutoAwesome,
                                contentDescription = null,
                                tint = VoxoraSubtleBlue,
                                modifier = Modifier.size(15.dp)
                            )
                            Text("Emotional Voice Settings", style = MaterialTheme.typography.labelLarge)
                        }
                        Text(
                            text = "Future AI Engine",
                            style = MaterialTheme.typography.labelSmall,
                            color = VoxoraSubtleBlue
                        )
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        VoiceEmotionStyle.entries.forEach { styleOption ->
                            FilterChip(
                                selected = emotion == styleOption,
                                onClick = { emotion = styleOption },
                                label = { Text(styleOption.label) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Color(0xFF28224A),
                                    selectedLabelColor = VoxoraElectricPurpleBright
                                )
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Expressive Intensity", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.72f))
                        Text("${(emotionIntensity * 100).toInt()}%", style = TabularTimeStyle, color = VoxoraSubtleBlue)
                    }
                    Slider(
                        value = emotionIntensity,
                        onValueChange = { emotionIntensity = it },
                        valueRange = 0f..1f
                    )
                }

                // Consistent Voice Lock Toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text("Lock Voice Throughout Movie", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "Keeps this character's voice assignment consistent across every scene",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.65f)
                        )
                    }
                    Switch(
                        checked = lockForMovie,
                        onCheckedChange = { lockForMovie = it }
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val safeName = name.trim().ifBlank { initialProfile.label }
                    val safeSpeaker = speakerSlot.trim().ifBlank { initialProfile.speakerId }
                    onConfirm(
                        initialProfile.copy(
                            label = safeName,
                            speakerId = safeSpeaker,
                            gender = gender,
                            voiceId = voiceId,
                            pitchFactor = pitch.coerceIn(0.5f, 1.5f),
                            speakingRate = speed.coerceIn(0.5f, 1.5f),
                            emotionStyle = emotion,
                            emotionIntensity = emotionIntensity.coerceIn(0f, 1f),
                            isVoiceLockedForMovie = lockForMovie
                        )
                    )
                },
                colors = ButtonDefaults.buttonColors(containerColor = VoxoraElectricPurple),
                modifier = Modifier.testTag("character_editor_save_button")
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun AssignVoiceDialog(
    character: CharacterVoiceProfile,
    onAssign: (gender: VoiceGender, voiceId: String, defaultPitch: Float, defaultRate: Float) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedGender by remember(character) { mutableStateOf(character.gender) }
    val presets = remember(selectedGender) { DubbingVoiceCatalog.presetsForGender(selectedGender) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = VoxoraCharcoal,
        titleContentColor = Color.White,
        textContentColor = Color.White,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Assign Voice Profile", style = MaterialTheme.typography.titleLarge)
                Text(
                    text = "${character.label} (${character.characterId})",
                    style = MaterialTheme.typography.bodySmall,
                    color = VoxoraSubtleBlue
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Select a Bengali voice profile slot. Real multi-character neural voice models will bind to these slots in Phase 2.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.72f)
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    VoiceGender.entries.forEach { option ->
                        FilterChip(
                            selected = selectedGender == option,
                            onClick = { selectedGender = option },
                            label = { Text("${option.label} Voices") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = VoxoraElectricPurple,
                                selectedLabelColor = Color.White
                            )
                        )
                    }
                }

                presets.forEach { preset ->
                    val isCurrent = character.voiceId == preset.id
                    Surface(
                        onClick = {
                            onAssign(selectedGender, preset.id, preset.defaultPitch, preset.defaultRate)
                        },
                        color = if (isCurrent) VoxoraElectricPurple.copy(alpha = 0.24f) else Color(0xFF12131C),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(
                            1.dp,
                            if (isCurrent) VoxoraElectricPurple else Color.White.copy(alpha = 0.08f)
                        ),
                        modifier = Modifier.testTag("assign_voice_option_${preset.id}")
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = preset.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = Color.White
                                )
                                if (isCurrent) {
                                    Icon(
                                        imageVector = Icons.Rounded.CheckCircle,
                                        contentDescription = "Assigned",
                                        tint = VoxoraElectricPurpleBright,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                            Text(
                                text = preset.nativeDisplayName,
                                style = MaterialTheme.typography.bodySmall,
                                color = VoxoraSubtleBlue
                            )
                            Text(
                                text = preset.description,
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.65f)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
private fun DeleteCharacterConfirmDialog(
    character: CharacterVoiceProfile,
    onConfirmDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = VoxoraCharcoal,
        titleContentColor = Color.White,
        textContentColor = Color.White,
        title = { Text("Delete Character?") },
        text = {
            Text(
                text = "Remove \"${character.label}\" (${character.characterId}) and its local voice assignment? You can add new characters at any time.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.85f)
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirmDelete,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.testTag("character_confirm_delete_button")
            ) {
                Text("Delete")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
