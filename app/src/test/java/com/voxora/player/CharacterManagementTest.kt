package com.voxora.player

import com.voxora.player.dubbing.CharacterVoiceProfile
import com.voxora.player.dubbing.DefaultSpeakerDiarizationCharacterMapper
import com.voxora.player.dubbing.DubbingVoiceCatalog
import com.voxora.player.dubbing.MovieSpeakerAssignment
import com.voxora.player.dubbing.SpeakerTurn
import com.voxora.player.dubbing.TtsPreviewState
import com.voxora.player.dubbing.VoiceEmotionStyle
import com.voxora.player.dubbing.VoiceGender
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CharacterManagementTest {

    @Test
    fun `default characters include Male Character and Female Character with unique IDs`() {
        val defaults = DubbingVoiceCatalog.defaultCharacters()
        assertEquals(2, defaults.size)

        val male = defaults[0]
        val female = defaults[1]

        assertEquals("Male Character", male.label)
        assertEquals(VoiceGender.MALE, male.gender)
        assertEquals("CHAR_001", male.characterId)
        assertEquals("SPEAKER_01", male.speakerId)
        assertTrue(male.isVoiceLockedForMovie)

        assertEquals("Female Character", female.label)
        assertEquals(VoiceGender.FEMALE, female.gender)
        assertEquals("CHAR_002", female.characterId)
        assertEquals("SPEAKER_02", female.speakerId)
        assertTrue(female.isVoiceLockedForMovie)

        assertNotEquals(male.characterId, female.characterId)
        assertNotEquals(male.voiceId, female.voiceId)
    }

    @Test
    fun `adding unlimited characters produces collision-free IDs and sequential names even after deletions`() {
        val cast = DubbingVoiceCatalog.defaultCharacters().toMutableList()

        // Add Character 3 through Character 12
        for (i in 3..12) {
            val gender = if (i % 2 == 0) VoiceGender.FEMALE else VoiceGender.MALE
            val preset = DubbingVoiceCatalog.nextVoicePresetForGender(gender, cast)
            val newId = DubbingVoiceCatalog.nextUniqueCharacterId(cast)
            val newSpeaker = DubbingVoiceCatalog.nextAvailableSpeakerId(cast)
            val suggestedName = DubbingVoiceCatalog.nextDefaultCharacterName(cast)

            assertEquals("Character $i", suggestedName)
            cast.add(
                CharacterVoiceProfile(
                    characterId = newId,
                    speakerId = newSpeaker,
                    label = suggestedName,
                    voiceId = preset.id,
                    gender = gender,
                    pitchFactor = preset.defaultPitch,
                    speakingRate = preset.defaultRate,
                    emotionStyle = VoiceEmotionStyle.NATURAL
                )
            )
        }

        assertEquals(12, cast.size)
        assertEquals(12, cast.map { it.characterId }.toSet().size)
        assertEquals(12, cast.map { it.speakerId }.toSet().size)

        // Delete several middle characters and verify next generated ID never collides
        cast.removeAll { it.characterId in setOf("CHAR_002", "CHAR_005", "CHAR_009") }
        assertEquals(9, cast.size)

        val nextIdAfterDelete = DubbingVoiceCatalog.nextUniqueCharacterId(cast)
        val nextSpeakerAfterDelete = DubbingVoiceCatalog.nextAvailableSpeakerId(cast)
        assertFalse(cast.any { it.characterId == nextIdAfterDelete })
        assertFalse(cast.any { it.speakerId == nextSpeakerAfterDelete })
        assertEquals("CHAR_013", nextIdAfterDelete)
        assertEquals("SPEAKER_13", nextSpeakerAfterDelete)
    }

    @Test
    fun `voice presets honestly report neural models are not yet installed`() {
        assertTrue(DubbingVoiceCatalog.bengaliPresets.isNotEmpty())
        assertTrue(DubbingVoiceCatalog.bengaliPresets.none { it.isNeuralModelInstalled })
    }

    @Test
    fun `tts preview state only allows preview when real engine and Bengali support both exist`() {
        assertFalse(TtsPreviewState(isChecking = true, isRealTtsEngineAvailable = true, isBengaliVoiceSupported = true).canPreview)
        assertFalse(TtsPreviewState(isChecking = false, isRealTtsEngineAvailable = false, isBengaliVoiceSupported = false).canPreview)
        assertFalse(TtsPreviewState(isChecking = false, isRealTtsEngineAvailable = true, isBengaliVoiceSupported = false).canPreview)
        assertTrue(TtsPreviewState(isChecking = false, isRealTtsEngineAvailable = true, isBengaliVoiceSupported = true).canPreview)
    }

    @Test
    fun `speaker diarization mapper keeps each character voice strictly consistent across the whole movie`() {
        val mapper = DefaultSpeakerDiarizationCharacterMapper()
        val initialCharacters = DubbingVoiceCatalog.defaultCharacters()

        // Scene 1: 3 speakers appear (SPEAKER_01 Male, SPEAKER_02 Female, SPEAKER_03 Male)
        val scene1Turns = listOf(
            SpeakerTurn("SPEAKER_01", 0L, 4_000L, VoiceGender.MALE),
            SpeakerTurn("SPEAKER_02", 4_200L, 8_000L, VoiceGender.FEMALE),
            SpeakerTurn("SPEAKER_03", 8_500L, 12_000L, VoiceGender.MALE),
            SpeakerTurn("SPEAKER_01", 12_500L, 16_000L, VoiceGender.MALE)
        )

        val result1 = mapper.assignSpeakersForMovie(
            detectedTurns = scene1Turns,
            savedCharacters = initialCharacters,
            existingMovieAssignments = emptyList()
        )

        // SPEAKER_01 -> Male Character, SPEAKER_02 -> Female Character, SPEAKER_03 -> Auto-created Character 3
        assertEquals(3, result1.updatedCharacters.size)
        assertEquals(1, result1.newlyCreatedCharacterIds.size)
        assertEquals(3, result1.movieAssignments.size)

        val speaker1Scene1 = mapper.resolveCharacterForSegment("SPEAKER_01", result1.movieAssignments, result1.updatedCharacters)
        val speaker2Scene1 = mapper.resolveCharacterForSegment("SPEAKER_02", result1.movieAssignments, result1.updatedCharacters)
        val speaker3Scene1 = mapper.resolveCharacterForSegment("SPEAKER_03", result1.movieAssignments, result1.updatedCharacters)

        assertNotNull(speaker1Scene1)
        assertNotNull(speaker2Scene1)
        assertNotNull(speaker3Scene1)
        assertEquals("CHAR_001", speaker1Scene1!!.characterId)
        assertEquals("CHAR_002", speaker2Scene1!!.characterId)
        assertEquals("Character 3", speaker3Scene1!!.label)
        assertTrue(speaker3Scene1.isDetectedFromDiarization)

        // Scene 2 later in the movie: same speakers return + a 4th speaker arrives.
        // Existing speakers MUST keep their exact character IDs & locked voices.
        val scene2Turns = listOf(
            SpeakerTurn("SPEAKER_03", 90_000L, 95_000L, VoiceGender.MALE),
            SpeakerTurn("SPEAKER_04", 95_500L, 100_000L, VoiceGender.FEMALE),
            SpeakerTurn("SPEAKER_01", 101_000L, 106_000L, VoiceGender.MALE)
        )

        val result2 = mapper.assignSpeakersForMovie(
            detectedTurns = scene2Turns,
            savedCharacters = result1.updatedCharacters,
            existingMovieAssignments = result1.movieAssignments
        )

        assertEquals(4, result2.updatedCharacters.size)
        assertEquals(4, result2.movieAssignments.size)

        val speaker1Scene2 = mapper.resolveCharacterForSegment("SPEAKER_01", result2.movieAssignments, result2.updatedCharacters)
        val speaker3Scene2 = mapper.resolveCharacterForSegment("SPEAKER_03", result2.movieAssignments, result2.updatedCharacters)
        val speaker4Scene2 = mapper.resolveCharacterForSegment("SPEAKER_04", result2.movieAssignments, result2.updatedCharacters)

        assertEquals(speaker1Scene1.characterId, speaker1Scene2!!.characterId)
        assertEquals(speaker1Scene1.voiceId, speaker1Scene2.voiceId)
        assertEquals(speaker3Scene1.characterId, speaker3Scene2!!.characterId)
        assertEquals(speaker3Scene1.voiceId, speaker3Scene2.voiceId)
        assertEquals("Character 4", speaker4Scene2!!.label)
        assertEquals(VoiceGender.FEMALE, speaker4Scene2.gender)
    }

    @Test
    fun `locked movie speaker assignment preserves locked voice even if global template drifts`() {
        val mapper = DefaultSpeakerDiarizationCharacterMapper()
        val char = DubbingVoiceCatalog.defaultCharacters().first().copy(
            voiceId = "bn_male_kabir",
            isVoiceLockedForMovie = true
        )
        val movieLock = listOf(
            MovieSpeakerAssignment(
                diarizationSpeakerId = "SPEAKER_01",
                characterId = char.characterId,
                lockedVoiceId = "bn_male_aarav",
                isAutoAssigned = false
            )
        )

        val resolved = mapper.resolveCharacterForSegment("SPEAKER_01", movieLock, listOf(char))
        assertNotNull(resolved)
        assertEquals("bn_male_aarav", resolved!!.voiceId)
    }
}
