/**
 * Project Charlie — Whisper JNI Bridge
 *
 * Bridges Kotlin WhisperEngine ↔ whisper.cpp C API.
 * Handles: model loading/unloading, audio transcription.
 */

#include <jni.h>
#include <string>
#include <android/log.h>
#include "whisper.h"

#define TAG "CharlieWhisper"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

extern "C" {

/**
 * Load whisper model from file path.
 * Returns a pointer to whisper_context (cast to jlong), or 0 on failure.
 */
JNIEXPORT jlong JNICALL
Java_com_alpha_charlie_stt_WhisperEngine_nativeInit(
        JNIEnv *env, jobject /* thiz */, jstring model_path) {

    const char *path = env->GetStringUTFChars(model_path, nullptr);
    if (!path) {
        LOGE("Failed to get model path string");
        return 0;
    }

    LOGI("Loading whisper model: %s", path);

    struct whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;       // CPU only for Galaxy M15
    cparams.flash_attn = false;

    struct whisper_context *ctx = whisper_init_from_file_with_params(path, cparams);
    env->ReleaseStringUTFChars(model_path, path);

    if (!ctx) {
        LOGE("Failed to initialize whisper model");
        return 0;
    }

    LOGI("Whisper model loaded successfully");
    return reinterpret_cast<jlong>(ctx);
}

/**
 * Transcribe a float PCM buffer (16kHz mono) to text.
 * Returns the transcription string.
 */
JNIEXPORT jstring JNICALL
Java_com_alpha_charlie_stt_WhisperEngine_nativeTranscribe(
        JNIEnv *env, jobject /* thiz */, jlong ctx_ptr, jfloatArray audio_data) {

    auto *ctx = reinterpret_cast<struct whisper_context *>(ctx_ptr);
    if (!ctx) {
        LOGE("Whisper context is null");
        return env->NewStringUTF("");
    }

    jfloat *samples = env->GetFloatArrayElements(audio_data, nullptr);
    jsize n_samples = env->GetArrayLength(audio_data);

    if (!samples || n_samples == 0) {
        LOGE("Empty audio data");
        if (samples) env->ReleaseFloatArrayElements(audio_data, samples, JNI_ABORT);
        return env->NewStringUTF("");
    }

    LOGI("Transcribing %d samples (%.1f sec)", n_samples, (float)n_samples / 16000.0f);

    // Configure transcription parameters
    struct whisper_full_params wparams = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wparams.n_threads        = 2;        // 2 A76 cores — good balance of speed vs thermal
    wparams.print_progress   = false;
    wparams.print_timestamps = false;
    wparams.print_realtime   = false;
    wparams.print_special    = false;
    wparams.no_context       = true;     // No cross-utterance context (saves memory)
    wparams.single_segment   = true;     // We expect short commands
    wparams.language         = "en";
    wparams.suppress_blank   = true;
    wparams.suppress_nst     = true;

    int ret = whisper_full(ctx, wparams, samples, n_samples);
    env->ReleaseFloatArrayElements(audio_data, samples, JNI_ABORT);

    if (ret != 0) {
        LOGE("Whisper transcription failed with code: %d", ret);
        return env->NewStringUTF("");
    }

    // Collect all segments
    std::string result;
    int n_segments = whisper_full_n_segments(ctx);
    for (int i = 0; i < n_segments; i++) {
        const char *text = whisper_full_get_segment_text(ctx, i);
        if (text) {
            result += text;
        }
    }

    LOGI("Transcription result: \"%s\"", result.c_str());
    return env->NewStringUTF(result.c_str());
}

/**
 * Free whisper model and context.
 */
JNIEXPORT void JNICALL
Java_com_alpha_charlie_stt_WhisperEngine_nativeFree(
        JNIEnv * /* env */, jobject /* thiz */, jlong ctx_ptr) {

    auto *ctx = reinterpret_cast<struct whisper_context *>(ctx_ptr);
    if (ctx) {
        whisper_free(ctx);
        LOGI("Whisper model freed");
    }
}

} // extern "C"
