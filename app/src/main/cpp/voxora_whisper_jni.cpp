#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <cstring>
#include <string>
#include <vector>

#include "whisper.h"
#include "ggml.h"

#define LOG_TAG "VoxoraWhisperJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

std::atomic<bool> g_abort_requested{false};

struct TranscriptionCallbackState {
    JNIEnv* env = nullptr;
    jobject callback_obj = nullptr;
    jmethodID on_progress_mid = nullptr;
    jmethodID on_segment_mid = nullptr;
    int64_t chunk_offset_ms = 0;
};

bool voxora_abort_callback(void* /*user_data*/) {
    return g_abort_requested.load(std::memory_order_acquire);
}

void voxora_progress_callback(
        struct whisper_context* /*ctx*/,
        struct whisper_state* /*state*/,
        int progress,
        void* user_data) {
    auto* cb = static_cast<TranscriptionCallbackState*>(user_data);
    if (!cb || !cb->env || !cb->callback_obj || !cb->on_progress_mid) {
        return;
    }
    if (progress < 0) progress = 0;
    if (progress > 100) progress = 100;
    cb->env->CallVoidMethod(cb->callback_obj, cb->on_progress_mid, static_cast<jint>(progress));
    if (cb->env->ExceptionCheck()) {
        cb->env->ExceptionClear();
    }
}

void voxora_new_segment_callback(
        struct whisper_context* ctx,
        struct whisper_state* /*state*/,
        int n_new,
        void* user_data) {
    auto* cb = static_cast<TranscriptionCallbackState*>(user_data);
    if (!cb || !cb->env || !cb->callback_obj || !cb->on_segment_mid || !ctx) {
        return;
    }

    const int total_segments = whisper_full_n_segments(ctx);
    const int start_index = total_segments - n_new;
    if (start_index < 0) return;

    for (int i = start_index; i < total_segments; ++i) {
        const char* raw_text = whisper_full_get_segment_text(ctx, i);
        if (!raw_text) continue;
        // Whisper returns timestamps in centiseconds (10ms units)
        const int64_t t0_ms = cb->chunk_offset_ms + (whisper_full_get_segment_t0(ctx, i) * 10LL);
        const int64_t t1_ms = cb->chunk_offset_ms + (whisper_full_get_segment_t1(ctx, i) * 10LL);

        jstring jtext = cb->env->NewStringUTF(raw_text);
        if (jtext) {
            cb->env->CallVoidMethod(
                    cb->callback_obj,
                    cb->on_segment_mid,
                    static_cast<jlong>(t0_ms),
                    static_cast<jlong>(t1_ms),
                    jtext
            );
            cb->env->DeleteLocalRef(jtext);
        }
        if (cb->env->ExceptionCheck()) {
            cb->env->ExceptionClear();
            break;
        }
    }
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_voxora_player_dubbing_whisper_WhisperNativeBridge_nativeInitContext(
        JNIEnv* env,
        jobject /*thiz*/,
        jstring model_path_str) {
    if (!model_path_str) {
        LOGE("nativeInitContext called with null model_path");
        return 0L;
    }
    const char* model_path = env->GetStringUTFChars(model_path_str, nullptr);
    if (!model_path) {
        LOGE("Failed to extract model_path UTF chars");
        return 0L;
    }

    LOGI("Initializing whisper.cpp context from: %s", model_path);
    struct whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false; // Pure CPU ARM64/x86_64 execution for maximum Android stability
    cparams.flash_attn = false;

    struct whisper_context* ctx = whisper_init_from_file_with_params(model_path, cparams);
    env->ReleaseStringUTFChars(model_path_str, model_path);

    if (!ctx) {
        LOGE("whisper_init_from_file_with_params failed");
        return 0L;
    }

    LOGI("whisper.cpp model loaded successfully (vocab=%d, multilingual=%d)",
         whisper_n_vocab(ctx),
         whisper_is_multilingual(ctx));
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_com_voxora_player_dubbing_whisper_WhisperNativeBridge_nativeFreeContext(
        JNIEnv* /*env*/,
        jobject /*thiz*/,
        jlong context_ptr) {
    if (context_ptr == 0L) return;
    auto* ctx = reinterpret_cast<struct whisper_context*>(context_ptr);
    whisper_free(ctx);
    LOGI("Released whisper.cpp native context");
}

JNIEXPORT void JNICALL
Java_com_voxora_player_dubbing_whisper_WhisperNativeBridge_nativeRequestAbort(
        JNIEnv* /*env*/,
        jobject /*thiz*/) {
    g_abort_requested.store(true, std::memory_order_release);
    LOGI("Requested abort for active whisper.cpp transcription");
}

JNIEXPORT void JNICALL
Java_com_voxora_player_dubbing_whisper_WhisperNativeBridge_nativeResetAbort(
        JNIEnv* /*env*/,
        jobject /*thiz*/) {
    g_abort_requested.store(false, std::memory_order_release);
}

JNIEXPORT jint JNICALL
Java_com_voxora_player_dubbing_whisper_WhisperNativeBridge_nativeTranscribeChunk(
        JNIEnv* env,
        jobject /*thiz*/,
        jlong context_ptr,
        jint num_threads,
        jfloatArray audio_data,
        jlong chunk_offset_ms,
        jstring language_str,
        jobject callback_obj) {
    if (context_ptr == 0L || !audio_data) {
        LOGE("nativeTranscribeChunk invalid arguments");
        return -1;
    }

    auto* ctx = reinterpret_cast<struct whisper_context*>(context_ptr);
    const jsize n_samples = env->GetArrayLength(audio_data);
    if (n_samples <= 0) {
        return 0;
    }

    jfloat* samples = env->GetFloatArrayElements(audio_data, nullptr);
    if (!samples) {
        LOGE("Failed to pin float audio samples");
        return -2;
    }

    const char* lang_chars = language_str ? env->GetStringUTFChars(language_str, nullptr) : nullptr;
    std::string raw_lang = (lang_chars && std::strlen(lang_chars) > 0) ? lang_chars : "auto";
    if (lang_chars) {
        env->ReleaseStringUTFChars(language_str, lang_chars);
    }

    bool translate_to_english = false;
    std::string lang = raw_lang;
    const std::string translate_suffix = ":translate";
    if (raw_lang.size() >= translate_suffix.size() &&
        raw_lang.compare(raw_lang.size() - translate_suffix.size(), translate_suffix.size(), translate_suffix) == 0) {
        translate_to_english = true;
        lang = raw_lang.substr(0, raw_lang.size() - translate_suffix.size());
        if (lang.empty()) {
            lang = "auto";
        }
    }

    if (lang != "auto" && whisper_lang_id(lang.c_str()) < 0) {
        LOGW("Unsupported Whisper language hint '%s'; falling back to auto", lang.c_str());
        lang = "auto";
    }

    TranscriptionCallbackState cb_state;
    cb_state.env = env;
    cb_state.callback_obj = callback_obj;
    cb_state.chunk_offset_ms = static_cast<int64_t>(chunk_offset_ms);
    if (callback_obj) {
        jclass cb_cls = env->GetObjectClass(callback_obj);
        if (cb_cls) {
            cb_state.on_progress_mid = env->GetMethodID(cb_cls, "onProgress", "(I)V");
            cb_state.on_segment_mid = env->GetMethodID(cb_cls, "onNewSegment", "(JJLjava/lang/String;)V");
            env->DeleteLocalRef(cb_cls);
        }
    }

    struct whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.greedy.best_of = 1;
    params.beam_search.beam_size = 1;
    params.temperature = 0.0f;
    params.temperature_inc = 0.0f;
    params.no_speech_thold = 0.65f;
    params.entropy_thold = 2.2f;
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.translate = translate_to_english && (whisper_is_multilingual(ctx) != 0);
    params.language = (whisper_is_multilingual(ctx) != 0) ? lang.c_str() : "en";
    params.n_threads = (num_threads > 0) ? (num_threads > 4 ? 4 : num_threads) : 2;
    params.offset_ms = 0;
    params.no_context = true;
    params.single_segment = false;
    params.suppress_blank = true;
    params.suppress_nst = true;

    params.abort_callback = voxora_abort_callback;
    params.abort_callback_user_data = nullptr;

    if (cb_state.on_progress_mid) {
        params.progress_callback = voxora_progress_callback;
        params.progress_callback_user_data = &cb_state;
    }
    if (cb_state.on_segment_mid) {
        params.new_segment_callback = voxora_new_segment_callback;
        params.new_segment_callback_user_data = &cb_state;
    }

    whisper_reset_timings(ctx);
    const int rc = whisper_full(ctx, params, samples, static_cast<int>(n_samples));

    env->ReleaseFloatArrayElements(audio_data, samples, JNI_ABORT);

    if (g_abort_requested.load(std::memory_order_acquire)) {
        LOGI("whisper_full aborted by user");
        return -99; // Cancelled by user
    }

    if (rc != 0) {
        LOGE("whisper_full returned error code %d", rc);
    }
    return static_cast<jint>(rc);
}

JNIEXPORT jint JNICALL
Java_com_voxora_player_dubbing_whisper_WhisperNativeBridge_nativeGetSegmentCount(
        JNIEnv* /*env*/,
        jobject /*thiz*/,
        jlong context_ptr) {
    if (context_ptr == 0L) return 0;
    auto* ctx = reinterpret_cast<struct whisper_context*>(context_ptr);
    return static_cast<jint>(whisper_full_n_segments(ctx));
}

JNIEXPORT jstring JNICALL
Java_com_voxora_player_dubbing_whisper_WhisperNativeBridge_nativeGetSegmentText(
        JNIEnv* env,
        jobject /*thiz*/,
        jlong context_ptr,
        jint index) {
    if (context_ptr == 0L) return env->NewStringUTF("");
    auto* ctx = reinterpret_cast<struct whisper_context*>(context_ptr);
    const char* text = whisper_full_get_segment_text(ctx, index);
    return env->NewStringUTF(text ? text : "");
}

JNIEXPORT jlong JNICALL
Java_com_voxora_player_dubbing_whisper_WhisperNativeBridge_nativeGetSegmentStartMs(
        JNIEnv* /*env*/,
        jobject /*thiz*/,
        jlong context_ptr,
        jint index) {
    if (context_ptr == 0L) return 0L;
    auto* ctx = reinterpret_cast<struct whisper_context*>(context_ptr);
    return static_cast<jlong>(whisper_full_get_segment_t0(ctx, index) * 10LL);
}

JNIEXPORT jlong JNICALL
Java_com_voxora_player_dubbing_whisper_WhisperNativeBridge_nativeGetSegmentEndMs(
        JNIEnv* /*env*/,
        jobject /*thiz*/,
        jlong context_ptr,
        jint index) {
    if (context_ptr == 0L) return 0L;
    auto* ctx = reinterpret_cast<struct whisper_context*>(context_ptr);
    return static_cast<jlong>(whisper_full_get_segment_t1(ctx, index) * 10LL);
}

JNIEXPORT jstring JNICALL
Java_com_voxora_player_dubbing_whisper_WhisperNativeBridge_nativeGetSystemInfo(
        JNIEnv* env,
        jobject /*thiz*/) {
    const char* info = whisper_print_system_info();
    return env->NewStringUTF(info ? info : "whisper.cpp");
}

} // extern "C"
