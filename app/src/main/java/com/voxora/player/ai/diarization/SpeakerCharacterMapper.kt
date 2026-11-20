package com.voxora.player.ai.diarization

import com.voxora.player.ai.translation.TranslatedSegment
import com.voxora.player.dubbing.CharacterVoiceProfile
import com.voxora.player.dubbing.DubbingVoiceCatalog
import com.voxora.player.dubbing.MovieSpeakerAssignment
import com.voxora.player.dubbing.RecognizedSpeechSegment
import com.voxora.player.dubbing.VoiceEmotionStyle
import com.voxora.player.dubbing.VoiceGender
import kotlin.math.abs

/**
 * Output of mapping Sherpa-ONNX detected speakers (`0, 1, 2, ...`) to persistent VOXORA
 * [CharacterVoiceProfile]s and aligning dialogue timestamps.
 */
data class SpeakerCharacterMappingResult(
    val updatedCharacters: List<CharacterVoiceProfile>,
    val movieAssignments: List<MovieSpeakerAssignment>,
    val speakerToCharacterMap: Map<Int, String>,
    val segmentIndexToCharacterId: Map<Int, String>,
    val newlyCreatedCharacterIds: List<String>
)

/**
 * Pure, deterministic alignment and auto-mapping engine bridging Phase 4 Sherpa-ONNX
 * [SpeakerSegment]s with VOXORA's Character Management (DataStore), Whisper Transcripts,
 * and Translated Bengali Segments (Room DB).
 */
object SpeakerCharacterMapper {

    private const val NEAREST_SPEAKER_FALLBACK_WINDOW_MS = 1_500L

    /**
     * Finds the [SpeakerSegment.speakerIndex] with the maximum temporal overlap against the
     * dialogue interval `[startMs, endMs]`.
     *
     * If two segments share zero direct overlap due to minor ASR/VAD boundary jitter, falls back
     * to the nearest speaker turn within [NEAREST_SPEAKER_FALLBACK_WINDOW_MS]; otherwise returns null.
     */
    fun findBestSpeakerIndexForInterval(
        startMs: Long,
        endMs: Long,
        speakerSegments: List<SpeakerSegment>
    ): Int? {
        if (speakerSegments.isEmpty()) return null
        val safeEnd = endMs.coerceAtLeast(startMs)

        val overlapBySpeaker = mutableMapOf<Int, Long>()
        for (seg in speakerSegments) {
            val overlapStart = maxOf(startMs, seg.startMs)
            val overlapEnd = minOf(safeEnd, seg.endMs)
            val overlap = overlapEnd - overlapStart
            if (overlap > 0L) {
                overlapBySpeaker[seg.speakerIndex] = (overlapBySpeaker[seg.speakerIndex] ?: 0L) + overlap
            }
        }

        if (overlapBySpeaker.isNotEmpty()) {
            return overlapBySpeaker.entries
                .sortedWith(compareByDescending<Map.Entry<Int, Long>> { it.value }.thenBy { it.key })
                .first()
                .key
        }

        // Small boundary-gap fallback (strictly bounded, never random)
        val midMs = (startMs + safeEnd) / 2L
        var bestSpeaker: Int? = null
        var bestDistance = Long.MAX_VALUE
        for (seg in speakerSegments) {
            val dist = when {
                midMs < seg.startMs -> seg.startMs - midMs
                midMs > seg.endMs -> midMs - seg.endMs
                else -> 0L
            }
            if (dist <= NEAREST_SPEAKER_FALLBACK_WINDOW_MS && dist < bestDistance) {
                bestDistance = dist
                bestSpeaker = seg.speakerIndex
            }
        }
        return bestSpeaker
    }

    /**
     * Maps every detected speaker cluster (`0, 1, 2, ...`) to existing VOXORA [CharacterVoiceProfile]s.
     *
     * - Preserves existing [MovieSpeakerAssignment] locks for the current video first.
     * - Reuses available saved characters (e.g., `Male Character`, `Female Character`) before creating new ones.
     * - If more speakers are detected than saved characters, automatically creates `"Character 3"`,
     *   `"Character 4"`, ... with collision-free IDs so unlimited speakers work seamlessly.
     */
    fun mapSpeakersToCharacters(
        speakerSegments: List<SpeakerSegment>,
        dialogueIntervals: List<Pair<Long, Long>>,
        savedCharacters: List<CharacterVoiceProfile>,
        existingMovieAssignments: List<MovieSpeakerAssignment>,
        existingManualOverrides: Map<Int, String> = emptyMap()
    ): SpeakerCharacterMappingResult {
        val workingCharacters = savedCharacters.toMutableList()
        val charactersById = workingCharacters.associateBy { it.characterId }.toMutableMap()
        val speakerToCharacter = linkedMapOf<Int, String>()
        val assignmentsBySpeakerId = linkedMapOf<String, MovieSpeakerAssignment>()
        val newlyCreatedIds = mutableListOf<String>()

        // 1. Preserve valid existing per-movie locks
        for (assignment in existingMovieAssignments) {
            val matched = charactersById[assignment.characterId] ?: continue
            assignmentsBySpeakerId[assignment.diarizationSpeakerId.uppercase()] = assignment.copy(
                lockedVoiceId = if (matched.isVoiceLockedForMovie) assignment.lockedVoiceId else matched.voiceId
            )
        }

        // 2. Discover distinct detected speaker indices in chronological order of first appearance
        val distinctSpeakerIndices = speakerSegments
            .sortedBy { it.startMs }
            .map { it.speakerIndex }
            .distinct()

        val usedCharacterIds = mutableSetOf<String>()

        // Honor already-locked assignments for detected speakers first
        for (speakerIdx in distinctSpeakerIndices) {
            val slotId = speakerSlotId(speakerIdx)
            val existingLock = assignmentsBySpeakerId[slotId]
            if (existingLock != null && existingLock.characterId in charactersById) {
                speakerToCharacter[speakerIdx] = existingLock.characterId
                usedCharacterIds.add(existingLock.characterId)
            }
        }

        // 3. Assign remaining speakers to unused saved characters, or auto-create "Character N"
        for (speakerIdx in distinctSpeakerIndices) {
            if (speakerIdx in speakerToCharacter) continue
            val slotId = speakerSlotId(speakerIdx)

            val directSlotMatch = workingCharacters.firstOrNull {
                it.speakerId.equals(slotId, ignoreCase = true) && it.characterId !in usedCharacterIds
            }
            val nextUnused = directSlotMatch ?: workingCharacters.firstOrNull {
                it.characterId !in usedCharacterIds
            }

            val chosen = if (nextUnused != null) {
                nextUnused
            } else {
                val gender = if (workingCharacters.size % 2 == 0) VoiceGender.MALE else VoiceGender.FEMALE
                val preset = DubbingVoiceCatalog.nextVoicePresetForGender(gender, workingCharacters)
                val created = CharacterVoiceProfile(
                    characterId = DubbingVoiceCatalog.nextUniqueCharacterId(workingCharacters),
                    speakerId = slotId,
                    label = DubbingVoiceCatalog.nextDefaultCharacterName(workingCharacters),
                    voiceId = preset.id,
                    gender = gender,
                    pitchFactor = preset.defaultPitch,
                    speakingRate = preset.defaultRate,
                    emotionStyle = VoiceEmotionStyle.NATURAL,
                    isDetectedFromDiarization = true,
                    isVoiceLockedForMovie = true,
                    createdAtMs = System.currentTimeMillis()
                )
                workingCharacters.add(created)
                charactersById[created.characterId] = created
                newlyCreatedIds.add(created.characterId)
                created
            }

            usedCharacterIds.add(chosen.characterId)
            speakerToCharacter[speakerIdx] = chosen.characterId
            assignmentsBySpeakerId[slotId] = MovieSpeakerAssignment(
                diarizationSpeakerId = slotId,
                characterId = chosen.characterId,
                lockedVoiceId = chosen.voiceId,
                isAutoAssigned = true
            )
        }

        // 4. Align each dialogue line with its best-overlapping speaker -> characterId
        val segmentToCharacter = linkedMapOf<Int, String>()
        dialogueIntervals.forEachIndexed { index, (startMs, endMs) ->
            val manualCharId = existingManualOverrides[index]?.takeIf { it in charactersById }
            if (manualCharId != null) {
                segmentToCharacter[index] = manualCharId
            } else {
                val bestSpeaker = findBestSpeakerIndexForInterval(startMs, endMs, speakerSegments)
                val mappedCharId = bestSpeaker?.let { speakerToCharacter[it] }
                if (mappedCharId != null) {
                    segmentToCharacter[index] = mappedCharId
                }
            }
        }

        return SpeakerCharacterMappingResult(
            updatedCharacters = workingCharacters,
            movieAssignments = assignmentsBySpeakerId.values.toList(),
            speakerToCharacterMap = speakerToCharacter,
            segmentIndexToCharacterId = segmentToCharacter,
            newlyCreatedCharacterIds = newlyCreatedIds
        )
    }

    /**
     * Applies resolved character IDs onto translated segments while preserving any existing character
     * assignment when a segment index is not present in [segmentIndexToCharacterId].
     */
    fun applyCharactersToTranslatedSegments(
        segments: List<TranslatedSegment>,
        segmentIndexToCharacterId: Map<Int, String>
    ): List<TranslatedSegment> {
        if (segments.isEmpty() || segmentIndexToCharacterId.isEmpty()) return segments
        return segments.map { segment ->
            val assigned = segmentIndexToCharacterId[segment.index]
            if (assigned != null) segment.copy(characterId = assigned) else segment
        }
    }

    /**
     * Updates Whisper [RecognizedSpeechSegment.speakerId] to reflect each line's assigned character ID.
     */
    fun applyCharactersToTranscriptSegments(
        segments: List<RecognizedSpeechSegment>,
        segmentIndexToCharacterId: Map<Int, String>
    ): List<RecognizedSpeechSegment> {
        if (segments.isEmpty() || segmentIndexToCharacterId.isEmpty()) return segments
        return segments.mapIndexed { index, segment ->
            val assigned = segmentIndexToCharacterId[index]
            if (assigned != null) segment.copy(speakerId = assigned) else segment
        }
    }

    fun speakerSlotId(speakerIndex: Int): String {
        return "SPEAKER_${(speakerIndex.coerceAtLeast(0) + 1).toString().padStart(2, '0')}"
    }
}
