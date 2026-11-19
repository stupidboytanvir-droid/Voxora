package com.voxora.player.dubbing

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

private val Context.voxoraDubbingStore: DataStore<Preferences> by preferencesDataStore(name = "voxora_dubbing_prefs")

/**
 * Local DataStore persistence for VOXORA AI Dubbing settings, unlimited character voice profiles,
 * and per-movie speaker-to-character voice lock assignments.
 */
class DubbingPreferencesRepository(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val movieAssignmentsMapSerializer = MapSerializer(
        String.serializer(),
        ListSerializer(MovieSpeakerAssignment.serializer())
    )

    private object Keys {
        val ENABLED = booleanPreferencesKey("dubbing_enabled")
        val SOURCE_LANG_CODE = stringPreferencesKey("dubbing_source_lang")
        val TARGET_LANG_CODE = stringPreferencesKey("dubbing_target_lang")
        val OUTPUT_MODE = stringPreferencesKey("dubbing_output_mode")
        val ORIGINAL_VOLUME = floatPreferencesKey("dubbing_original_volume")
        val DUBBED_VOLUME = floatPreferencesKey("dubbing_dubbed_volume")
        val SYNC_OFFSET_MS = longPreferencesKey("dubbing_sync_offset_ms")
        val PRESERVE_BACKGROUND = booleanPreferencesKey("dubbing_preserve_background")
        val CHARACTER_PROFILES_JSON = stringPreferencesKey("dubbing_character_profiles_json")
        val CHARACTER_PROFILES_INITIALIZED = booleanPreferencesKey("dubbing_characters_initialized")
        val MOVIE_SPEAKER_ASSIGNMENTS_JSON = stringPreferencesKey("dubbing_movie_speaker_assignments_json")
    }

    val configFlow: Flow<DubbingSessionConfig> = context.voxoraDubbingStore.data.map { prefs ->
        val sourceCode = prefs[Keys.SOURCE_LANG_CODE] ?: DubbingLanguageRegistry.ENGLISH.code
        val targetCode = prefs[Keys.TARGET_LANG_CODE] ?: DubbingLanguageRegistry.BENGALI.code
        val sourceLang = DubbingLanguageRegistry.sourceLanguages.firstOrNull { it.code == sourceCode }
            ?: DubbingLanguageRegistry.ENGLISH
        val targetLang = DubbingLanguageRegistry.targetLanguages.firstOrNull { it.code == targetCode }
            ?: DubbingLanguageRegistry.BENGALI

        val outputMode = prefs[Keys.OUTPUT_MODE]?.let { name ->
            DubbingAudioOutputMode.entries.firstOrNull { it.name == name }
        } ?: DubbingAudioOutputMode.ORIGINAL_AUDIO

        val isInitialized = prefs[Keys.CHARACTER_PROFILES_INITIALIZED] ?: false
        val rawCharacters = prefs[Keys.CHARACTER_PROFILES_JSON]
        val characters = if (rawCharacters != null) {
            runCatching {
                json.decodeFromString(ListSerializer(CharacterVoiceProfile.serializer()), rawCharacters)
            }.getOrNull()?.let { decoded ->
                if (decoded.isEmpty() && !isInitialized) {
                    DubbingVoiceCatalog.defaultCharacters()
                } else {
                    normalizeLegacyProfiles(decoded)
                }
            } ?: DubbingVoiceCatalog.defaultCharacters()
        } else {
            DubbingVoiceCatalog.defaultCharacters()
        }

        DubbingSessionConfig(
            isDubbingEnabled = prefs[Keys.ENABLED] ?: false,
            sourceLanguage = sourceLang,
            targetLanguage = targetLang,
            audioOutputMode = outputMode,
            originalAudioVolume = (prefs[Keys.ORIGINAL_VOLUME] ?: 1.0f).coerceIn(0f, 1f),
            dubbedAudioVolume = (prefs[Keys.DUBBED_VOLUME] ?: 1.0f).coerceIn(0f, 1f),
            syncOffsetMs = (prefs[Keys.SYNC_OFFSET_MS] ?: 0L).coerceIn(-5_000L, 5_000L),
            preserveOriginalBackgroundAudio = prefs[Keys.PRESERVE_BACKGROUND] ?: true,
            characterVoices = characters
        )
    }

    val allMovieAssignmentsFlow: Flow<Map<String, List<MovieSpeakerAssignment>>> =
        context.voxoraDubbingStore.data.map { prefs ->
            prefs[Keys.MOVIE_SPEAKER_ASSIGNMENTS_JSON]?.let { raw ->
                runCatching {
                    json.decodeFromString(movieAssignmentsMapSerializer, raw)
                }.getOrNull()
            } ?: emptyMap()
        }

    /**
     * Ensures previously saved v1.1.0 profiles without a distinct `CHAR_NNN` ID receive
     * stable unique character IDs without losing any user edits.
     */
    private fun normalizeLegacyProfiles(profiles: List<CharacterVoiceProfile>): List<CharacterVoiceProfile> {
        val seenIds = mutableSetOf<String>()
        return profiles.mapIndexed { index, profile ->
            val resolvedId = if (profile.characterId.isBlank() || profile.characterId in seenIds) {
                "CHAR_${(index + 1).toString().padStart(3, '0')}"
            } else {
                profile.characterId
            }
            seenIds.add(resolvedId)
            profile.copy(characterId = resolvedId)
        }
    }

    suspend fun setDubbingEnabled(enabled: Boolean) {
        context.voxoraDubbingStore.edit { prefs ->
            prefs[Keys.ENABLED] = enabled
            if (enabled && prefs[Keys.OUTPUT_MODE] == null) {
                prefs[Keys.OUTPUT_MODE] = DubbingAudioOutputMode.BENGALI_DUBBED_MIX.name
            }
        }
    }

    suspend fun setSourceLanguage(language: DubbingLanguage) {
        context.voxoraDubbingStore.edit { it[Keys.SOURCE_LANG_CODE] = language.code }
    }

    suspend fun setTargetLanguage(language: DubbingLanguage) {
        context.voxoraDubbingStore.edit { it[Keys.TARGET_LANG_CODE] = language.code }
    }

    suspend fun setOutputMode(mode: DubbingAudioOutputMode) {
        context.voxoraDubbingStore.edit { it[Keys.OUTPUT_MODE] = mode.name }
    }

    suspend fun setOriginalAudioVolume(volume: Float) {
        context.voxoraDubbingStore.edit { it[Keys.ORIGINAL_VOLUME] = volume.coerceIn(0f, 1f) }
    }

    suspend fun setDubbedAudioVolume(volume: Float) {
        context.voxoraDubbingStore.edit { it[Keys.DUBBED_VOLUME] = volume.coerceIn(0f, 1f) }
    }

    suspend fun setSyncOffsetMs(offsetMs: Long) {
        context.voxoraDubbingStore.edit { it[Keys.SYNC_OFFSET_MS] = offsetMs.coerceIn(-5_000L, 5_000L) }
    }

    suspend fun setPreserveBackgroundAudio(preserve: Boolean) {
        context.voxoraDubbingStore.edit { it[Keys.PRESERVE_BACKGROUND] = preserve }
    }

    suspend fun saveCharacters(characters: List<CharacterVoiceProfile>) {
        val encoded = json.encodeToString(ListSerializer(CharacterVoiceProfile.serializer()), characters)
        context.voxoraDubbingStore.edit { prefs ->
            prefs[Keys.CHARACTER_PROFILES_INITIALIZED] = true
            prefs[Keys.CHARACTER_PROFILES_JSON] = encoded
        }
    }

    suspend fun resetDefaultCharacters() {
        saveCharacters(DubbingVoiceCatalog.defaultCharacters())
    }

    suspend fun saveMovieAssignments(
        mediaUriString: String,
        assignments: List<MovieSpeakerAssignment>
    ) {
        if (mediaUriString.isBlank()) return
        context.voxoraDubbingStore.edit { prefs ->
            val existing = prefs[Keys.MOVIE_SPEAKER_ASSIGNMENTS_JSON]?.let { raw ->
                runCatching { json.decodeFromString(movieAssignmentsMapSerializer, raw) }.getOrNull()
            }?.toMutableMap() ?: mutableMapOf()
            existing[mediaUriString] = assignments
            prefs[Keys.MOVIE_SPEAKER_ASSIGNMENTS_JSON] = json.encodeToString(movieAssignmentsMapSerializer, existing)
        }
    }

    /**
     * Keeps a movie's speaker-to-character voice lock synchronized when characters are added,
     * edited, or removed while a video is loaded.
     */
    suspend fun syncMovieAssignmentsForCharacters(
        mediaUriString: String,
        characters: List<CharacterVoiceProfile>
    ) {
        if (mediaUriString.isBlank()) return
        val synced = characters.map { character ->
            MovieSpeakerAssignment(
                diarizationSpeakerId = character.speakerId,
                characterId = character.characterId,
                lockedVoiceId = character.voiceId,
                isAutoAssigned = character.isDetectedFromDiarization
            )
        }
        saveMovieAssignments(mediaUriString, synced)
    }
}
