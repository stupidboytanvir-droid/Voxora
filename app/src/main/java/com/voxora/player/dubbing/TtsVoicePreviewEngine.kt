package com.voxora.player.dubbing

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Genuine on-device Android [TextToSpeech] voice preview engine.
 *
 * Strict honesty rules:
 * - Never generates fake audio, tones, or simulated voice previews.
 * - Strictly queries the device's installed TTS engine for Bengali (`bn-BD` / `bn-IN`).
 * - Preview is ONLY enabled when a real system TTS engine is installed AND Bengali voice data
 *   is genuinely available (`>= TextToSpeech.LANG_AVAILABLE`).
 * - Clearly distinguishes device system TTS auditioning (pitch/speed preview) from future
 *   Phase 2 multi-character neural Bengali AI voice models.
 */
class TtsVoicePreviewEngine(context: Context) {

    private val appContext = context.applicationContext
    private var textToSpeech: TextToSpeech? = null
    private var selectedBengaliLocale: Locale? = null
    @Volatile
    private var isReleased = false

    private val _state = MutableStateFlow(TtsPreviewState())
    val state: StateFlow<TtsPreviewState> = _state.asStateFlow()

    init {
        initializeEngine()
    }

    private fun initializeEngine() {
        try {
            textToSpeech = TextToSpeech(appContext) { status ->
                if (isReleased) return@TextToSpeech
                if (status != TextToSpeech.SUCCESS) {
                    _state.value = TtsPreviewState(
                        isChecking = false,
                        isRealTtsEngineAvailable = false,
                        isBengaliVoiceSupported = false,
                        statusMessage = "No Android Text-to-Speech engine is available on this device. Voice preview is disabled."
                    )
                    return@TextToSpeech
                }

                val tts = textToSpeech ?: return@TextToSpeech
                val candidateLocales = listOf(
                    Locale("bn", "BD"),
                    Locale("bn", "IN"),
                    Locale("bn")
                )

                var matchedLocale: Locale? = null
                var sawMissingData = false
                for (locale in candidateLocales) {
                    val availability = runCatching { tts.isLanguageAvailable(locale) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
                    when {
                        availability >= TextToSpeech.LANG_AVAILABLE -> {
                            matchedLocale = locale
                            break
                        }
                        availability == TextToSpeech.LANG_MISSING_DATA -> {
                            sawMissingData = true
                        }
                    }
                }

                val enginePkg = runCatching { tts.defaultEngine }.getOrNull()

                if (matchedLocale != null) {
                    val setResult = runCatching { tts.setLanguage(matchedLocale) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
                    if (setResult >= TextToSpeech.LANG_AVAILABLE) {
                        selectedBengaliLocale = matchedLocale
                        attachProgressListener(tts)
                        _state.value = TtsPreviewState(
                            isChecking = false,
                            isRealTtsEngineAvailable = true,
                            isBengaliVoiceSupported = true,
                            enginePackageName = enginePkg,
                            matchedLocaleTag = matchedLocale.toLanguageTag(),
                            statusMessage = "Device Bengali TTS available (${matchedLocale.toLanguageTag()}). Previews pitch & speed using system TTS (Phase 2 neural character voices not yet installed)."
                        )
                    } else {
                        _state.value = TtsPreviewState(
                            isChecking = false,
                            isRealTtsEngineAvailable = true,
                            isBengaliVoiceSupported = false,
                            enginePackageName = enginePkg,
                            statusMessage = "Device TTS engine found, but Bengali voice data could not be loaded. Voice preview is disabled."
                        )
                    }
                } else {
                    val reason = if (sawMissingData) {
                        "Device TTS engine is missing Bengali (বাংলা) voice data. Install Bengali voice data in Android TTS settings to enable real preview."
                    } else {
                        "Device TTS engine does not support Bengali (বাংলা). Voice preview is disabled until a real Bengali TTS engine is installed."
                    }
                    _state.value = TtsPreviewState(
                        isChecking = false,
                        isRealTtsEngineAvailable = true,
                        isBengaliVoiceSupported = false,
                        enginePackageName = enginePkg,
                        statusMessage = reason
                    )
                }
            }
        } catch (_: Throwable) {
            _state.value = TtsPreviewState(
                isChecking = false,
                isRealTtsEngineAvailable = false,
                isBengaliVoiceSupported = false,
                statusMessage = "Unable to bind to a system Text-to-Speech engine on this device. Voice preview is disabled."
            )
        }
    }

    private fun attachProgressListener(tts: TextToSpeech) {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                val charId = utteranceId?.removePrefix(UTTERANCE_PREFIX)
                _state.value = _state.value.copy(activePreviewCharacterId = charId)
            }

            override fun onDone(utteranceId: String?) {
                val charId = utteranceId?.removePrefix(UTTERANCE_PREFIX)
                if (_state.value.activePreviewCharacterId == charId) {
                    _state.value = _state.value.copy(activePreviewCharacterId = null)
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                _state.value = _state.value.copy(activePreviewCharacterId = null)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                _state.value = _state.value.copy(activePreviewCharacterId = null)
            }
        })
    }

    /**
     * Speaks a real Bengali sample phrase with the character's configured pitch and speed ONLY
     * when a genuine Bengali TTS engine is available on-device.
     */
    fun previewCharacter(profile: CharacterVoiceProfile): Boolean {
        val current = _state.value
        val tts = textToSpeech ?: return false
        if (!current.canPreview || isReleased) return false

        // Toggle off if tapping the currently speaking character
        if (current.activePreviewCharacterId == profile.characterId) {
            stopPreview()
            return false
        }

        val locale = selectedBengaliLocale ?: return false
        val utteranceId = "$UTTERANCE_PREFIX${profile.characterId}"
        val sampleText = buildBengaliPreviewSentence(profile)

        return runCatching {
            tts.stop()
            tts.setLanguage(locale)
            tts.setPitch(profile.pitchFactor.coerceIn(0.5f, 1.5f))
            tts.setSpeechRate(profile.speakingRate.coerceIn(0.5f, 1.5f))
            _state.value = _state.value.copy(activePreviewCharacterId = profile.characterId)
            val result = tts.speak(sampleText, TextToSpeech.QUEUE_FLUSH, Bundle(), utteranceId)
            if (result != TextToSpeech.SUCCESS) {
                _state.value = _state.value.copy(activePreviewCharacterId = null)
                false
            } else {
                true
            }
        }.getOrElse {
            _state.value = _state.value.copy(activePreviewCharacterId = null)
            false
        }
    }

    fun stopPreview() {
        runCatching { textToSpeech?.stop() }
        _state.value = _state.value.copy(activePreviewCharacterId = null)
    }

    fun release() {
        isReleased = true
        runCatching {
            textToSpeech?.stop()
            textToSpeech?.shutdown()
        }
        textToSpeech = null
        _state.value = _state.value.copy(activePreviewCharacterId = null)
    }

    private fun buildBengaliPreviewSentence(profile: CharacterVoiceProfile): String {
        return when (profile.gender) {
            VoiceGender.MALE -> "নমস্কার, এটি ভক্সোরা বাংলা ভয়েস প্রিভিউ। পিচ এবং স্পিড পরীক্ষা করা হচ্ছে।"
            VoiceGender.FEMALE -> "নমস্কার, এটি ভক্সোরা বাংলা ভয়েস প্রিভিউ। আপনার চরিত্রের গতি ও স্বর শোনানো হচ্ছে।"
        }
    }

    companion object {
        private const val UTTERANCE_PREFIX = "voxora_preview_"
    }
}
