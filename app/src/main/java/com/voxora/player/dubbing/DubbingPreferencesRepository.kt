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
import kotlinx.serialization.json.Json

private val Context.voxoraDubbingStore: DataStore<Preferences> by preferencesDataStore(name = "voxora_dubbing_prefs")

/**
 * Local DataStore persistence for VOXORA AI Dubbing Phase 1 settings and character voice profiles.
 */
class DubbingPreferencesRepository(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

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

        val characters = prefs[Keys.CHARACTER_PROFILES_JSON]?.let { raw ->
            runCatching {
                json.decodeFromString(ListSerializer(CharacterVoiceProfile.serializer()), raw)
            }.getOrNull()?.takeIf { it.isNotEmpty() }
        } ?: DubbingVoiceCatalog.defaultCharacters()

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
        context.voxoraDubbingStore.edit { it[Keys.CHARACTER_PROFILES_JSON] = encoded }
    }
}
