/*
 * The Machine — an offline voice assistant for Android.
 * Copyright (C) 2026 Hasan Ismail
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 *
 * ---------------------------------------------------------------------------
 * P1 bridge: enough JNI to prove both engines linked, loaded and picked the
 * right CPU backend on a real device. The transcription and inference entry
 * points arrive in P3 and P4.
 */

#include <jni.h>
#include <android/log.h>

#include <unistd.h>

#include <cstdio>
#include <mutex>
#include <string>

#include "whisper.h"
#include "llama.h"
#include "ggml-backend.h"

#define LOG_TAG "TheMachine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

std::once_flag g_init_once;
size_t g_backend_count = 0;

// ggml is built with GGML_BACKEND_DL, so each CPU feature variant is a separate
// libggml-cpu-android_armv*.so that has to be dlopen'd before any device shows
// up in the registry. Doing this more than once is harmless but pointless.
void initialise_backends(const char *lib_dir) {
    std::call_once(g_init_once, [lib_dir]() {
        if (lib_dir != nullptr && lib_dir[0] != '\0') {
            // Preferred when the caller knows where the libraries physically live;
            // with extractNativeLibs=false that directory may not exist, in which
            // case ggml falls back to plain dlopen by soname below.
            ggml_backend_load_all_from_path(lib_dir);
        }
        if (ggml_backend_reg_count() == 0) {
            ggml_backend_load_all();
        }
        llama_backend_init();
        g_backend_count = ggml_backend_reg_count();
        LOGI("ggml backends registered: %zu (devices: %zu)",
             g_backend_count, ggml_backend_dev_count());
    });
}

jstring to_jstring(JNIEnv *env, const std::string &s) {
    return env->NewStringUTF(s.c_str());
}

std::string scoped_utf8(JNIEnv *env, jstring s) {
    if (s == nullptr) {
        return {};
    }
    const char *chars = env->GetStringUTFChars(s, nullptr);
    std::string out = chars != nullptr ? chars : "";
    if (chars != nullptr) {
        env->ReleaseStringUTFChars(s, chars);
    }
    return out;
}

} // namespace

extern "C" {

JNIEXPORT jint JNICALL
Java_io_github_hasanismail_themachine_nativebridge_NativeBridge_nativeInit(
        JNIEnv *env, jobject /* this */, jstring libDir) {
    const std::string dir = scoped_utf8(env, libDir);
    initialise_backends(dir.c_str());
    return static_cast<jint>(g_backend_count);
}

JNIEXPORT jstring JNICALL
Java_io_github_hasanismail_themachine_nativebridge_NativeBridge_nativeWhisperVersion(
        JNIEnv *env, jobject /* this */) {
    return to_jstring(env, whisper_version());
}

JNIEXPORT jstring JNICALL
Java_io_github_hasanismail_themachine_nativebridge_NativeBridge_nativeLlamaVersion(
        JNIEnv *env, jobject /* this */) {
    return to_jstring(env, llama_version());
}

JNIEXPORT jstring JNICALL
Java_io_github_hasanismail_themachine_nativebridge_NativeBridge_nativeWhisperSystemInfo(
        JNIEnv *env, jobject /* this */) {
    return to_jstring(env, whisper_print_system_info());
}

JNIEXPORT jstring JNICALL
Java_io_github_hasanismail_themachine_nativebridge_NativeBridge_nativeLlamaSystemInfo(
        JNIEnv *env, jobject /* this */) {
    return to_jstring(env, llama_print_system_info());
}

/**
 * The interesting one. Lists every registered backend and device so the debug
 * screen can show WHICH libggml-cpu-android_armv*.so actually won the runtime
 * scoring — the whole point of building with GGML_CPU_ALL_VARIANTS. If this
 * comes back empty, backend dlopen failed and inference would silently fall
 * back to nothing.
 */
JNIEXPORT jstring JNICALL
Java_io_github_hasanismail_themachine_nativebridge_NativeBridge_nativeBackendReport(
        JNIEnv *env, jobject /* this */) {
    std::string out;
    const size_t regs = ggml_backend_reg_count();
    const size_t devs = ggml_backend_dev_count();

    out += "registries: " + std::to_string(regs) + "\n";
    for (size_t i = 0; i < regs; i++) {
        ggml_backend_reg_t reg = ggml_backend_reg_get(i);
        out += "  [reg] ";
        out += ggml_backend_reg_name(reg);
        out += "\n";
    }

    out += "devices: " + std::to_string(devs) + "\n";
    for (size_t i = 0; i < devs; i++) {
        ggml_backend_dev_t dev = ggml_backend_dev_get(i);
        size_t free_mem = 0;
        size_t total_mem = 0;
        ggml_backend_dev_memory(dev, &free_mem, &total_mem);

        char line[512];
        snprintf(line, sizeof(line), "  [dev] %s — %s (%.1f GiB free / %.1f GiB total)\n",
                 ggml_backend_dev_name(dev),
                 ggml_backend_dev_description(dev),
                 static_cast<double>(free_mem) / (1024.0 * 1024.0 * 1024.0),
                 static_cast<double>(total_mem) / (1024.0 * 1024.0 * 1024.0));
        out += line;
    }
    return to_jstring(env, out);
}

JNIEXPORT jboolean JNICALL
Java_io_github_hasanismail_themachine_nativebridge_NativeBridge_nativeSupportsMmap(
        JNIEnv * /* env */, jobject /* this */) {
    return llama_supports_mmap() ? JNI_TRUE : JNI_FALSE;
}

/**
 * PAGE_SIZE is undefined under NDK r27+ when building for 16 KB page support,
 * so this asks the kernel. Surfaced in the debug screen because a 16 KB device
 * will refuse to load an unaligned .so at all, and that is worth seeing plainly
 * rather than discovering as a load failure.
 */
JNIEXPORT jlong JNICALL
Java_io_github_hasanismail_themachine_nativebridge_NativeBridge_nativePageSize(
        JNIEnv * /* env */, jobject /* this */) {
    return static_cast<jlong>(sysconf(_SC_PAGESIZE));
}

// ---------------------------------------------------------------------------
// Whisper: speech to text.
//
// The context is created once per session and reused across utterances — loading
// a model per utterance would dominate the latency budget. The handle is an
// opaque jlong so Kotlin never sees a raw pointer type.
// ---------------------------------------------------------------------------

JNIEXPORT jlong JNICALL
Java_io_github_hasanismail_themachine_stt_WhisperNative_nativeLoad(
        JNIEnv *env, jobject /* this */, jstring modelPath) {
    const std::string path = scoped_utf8(env, modelPath);
    initialise_backends(nullptr);

    whisper_context_params params = whisper_context_default_params();
    // CPU only in v1; GPU is a later experiment. mmap keeps a warm reload cheap.
    params.use_gpu = false;
    params.flash_attn = false;

    whisper_context *ctx = whisper_init_from_file_with_params(path.c_str(), params);
    if (ctx == nullptr) {
        LOGE("whisper: failed to load %s", path.c_str());
        return 0;
    }
    LOGI("whisper: loaded %s", path.c_str());
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_io_github_hasanismail_themachine_stt_WhisperNative_nativeFree(
        JNIEnv * /* env */, jobject /* this */, jlong handle) {
    if (handle == 0) return;
    whisper_free(reinterpret_cast<whisper_context *>(handle));
}

/**
 * Transcribes 16 kHz mono float PCM in [-1, 1].
 *
 * Tuned for short commands rather than transcription of long audio: greedy
 * sampling, a single segment, no timestamps, English forced, and no carry-over
 * context between utterances so one misheard command cannot bias the next.
 */
JNIEXPORT jstring JNICALL
Java_io_github_hasanismail_themachine_stt_WhisperNative_nativeTranscribe(
        JNIEnv *env, jobject /* this */, jlong handle, jfloatArray samples, jint threads) {
    if (handle == 0) return env->NewStringUTF("");
    auto *ctx = reinterpret_cast<whisper_context *>(handle);

    const jsize count = env->GetArrayLength(samples);
    jfloat *pcm = env->GetFloatArrayElements(samples, nullptr);
    if (pcm == nullptr) return env->NewStringUTF("");

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads        = threads > 0 ? threads : 4;
    params.language         = "en";
    params.translate        = false;
    params.no_context       = true;
    params.single_segment   = true;
    params.no_timestamps    = true;
    params.print_progress   = false;
    params.print_realtime   = false;
    params.print_timestamps = false;
    params.print_special    = false;
    params.suppress_blank   = true;
    params.suppress_nst     = true;
    params.temperature      = 0.0f;

    const int rc = whisper_full(ctx, params, pcm, count);
    env->ReleaseFloatArrayElements(samples, pcm, JNI_ABORT);

    if (rc != 0) {
        LOGE("whisper: whisper_full failed (%d)", rc);
        return env->NewStringUTF("");
    }

    std::string text;
    const int segments = whisper_full_n_segments(ctx);
    for (int i = 0; i < segments; i++) {
        const char *segment = whisper_full_get_segment_text(ctx, i);
        if (segment != nullptr) text += segment;
    }
    return to_jstring(env, text);
}

} // extern "C"
