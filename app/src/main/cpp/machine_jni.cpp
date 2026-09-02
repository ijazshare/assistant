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
#include <stdexcept>
#include <cctype>
#include <chrono>
#include <cmath>
#include <string>
#include <vector>

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
/**
 * Forwards ggml and llama's own diagnostics into logcat.
 *
 * Without this they go to stderr, which on Android goes nowhere — so a rejected
 * grammar or a malformed GGUF reports only that it failed, never why. Worth wiring
 * once rather than guessing at every failure.
 */
void forward_native_logs() {
    const auto sink = [](ggml_log_level level, const char *text, void * /* user */) {
        if (text == nullptr) return;
        const int priority = level == GGML_LOG_LEVEL_ERROR ? ANDROID_LOG_ERROR
                           : level == GGML_LOG_LEVEL_WARN  ? ANDROID_LOG_WARN
                           : ANDROID_LOG_INFO;
        __android_log_write(priority, "TheMachineNative", text);
    };
    ggml_log_set(sink, nullptr);
    llama_log_set(sink, nullptr);
}

void initialise_backends(const char *lib_dir) {
    std::call_once(g_init_once, [lib_dir]() {
        forward_native_logs();
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

// ---------------------------------------------------------------------------
// llama: turning a transcript into a tool call.
//
// The model is constrained by a GBNF grammar supplied from Kotlin, so its output
// is valid JSON matching the tool schema by construction rather than by hope.
// That is what makes a 1B model usable for this: it cannot emit prose, an
// unknown tool name, or a trailing apology, because the sampler will not let it.
// ---------------------------------------------------------------------------

namespace {

struct llama_session {
    llama_model   *model = nullptr;
    llama_context *ctx   = nullptr;
    const llama_vocab *vocab = nullptr;
    int n_ctx = 0;
    // Prompt tokens whose keys and values are still resident in the context, so that an
    // unchanged prefix does not have to be prefilled again. See generate().
    std::vector<llama_token> cached;
};

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_io_github_hasanismail_themachine_llm_LlamaNative_nativeLoad(
        JNIEnv *env, jobject /* this */, jstring modelPath, jint contextSize, jint threads) {
    const std::string path = scoped_utf8(env, modelPath);
    initialise_backends(nullptr);

    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0; // CPU only in v1
    // MMAP rather than MLOCK: mapping keeps a warm reload cheap because the pages
    // stay in the file cache, while pinning a gigabyte in RAM on a phone is how you
    // get the assistant killed to make room for whatever the user does next.
    // (use_mmap/use_mlock became this enum in llama.cpp v0.3.0.)
    model_params.load_mode = LLAMA_LOAD_MODE_MMAP;

    llama_model *model = llama_model_load_from_file(path.c_str(), model_params);
    if (model == nullptr) {
        LOGE("llama: failed to load %s", path.c_str());
        return 0;
    }

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = contextSize > 0 ? static_cast<uint32_t>(contextSize) : 2048;
    // Sizing the batch to the whole context made ggml reserve a 518 MiB compute buffer
    // for a 1B model, which costs both memory and cache locality on a phone. The prompt
    // is a few hundred tokens, so a batch that comfortably covers it is enough.
    // Must be at least as large as the longest prompt: llama asserts rather than
    // splitting when a batch exceeds it, and a 570-token prompt against the previous
    // 512 aborted the process inside llama_context::decode. Unlike n_ubatch this costs
    // no compute buffer, so it simply matches the context.
    ctx_params.n_batch  = ctx_params.n_ctx;
    // Kept small on purpose. llama reserves a logits slab for every token a micro-batch
    // could produce, so with Gemma's 262144-entry vocabulary each step of n_ubatch costs
    // about a megabyte of compute buffer: raising this to 512 asked for 518 MiB and the
    // process was killed reserving it. Prefill is a one-off anyway now that an unchanged
    // prefix is reused, so there is nothing to win by making it larger.
    ctx_params.n_ubatch = 128;
    ctx_params.n_threads   = threads > 0 ? threads : 4;
    // Prefill is governed by n_threads_batch, which otherwise stays at llama's default
    // of 4 no matter what n_threads is set to.
    ctx_params.n_threads_batch = ctx_params.n_threads;
    // Gemma 3 interleaves sliding-window attention layers with a 1024-token window, and
    // by default llama keeps only that window for them. Reusing a cached prefix then
    // reads keys the cache no longer holds: replies stayed syntactically legal, because
    // the grammar saw to that, while their contents collapsed to the same few tokens
    // regardless of what was asked. A full-size window costs some memory and makes the
    // reuse sound.
    ctx_params.swa_full = true;

    llama_context *ctx = llama_init_from_model(model, ctx_params);
    if (ctx == nullptr) {
        LOGE("llama: failed to create context");
        llama_model_free(model);
        return 0;
    }

    auto *session = new llama_session();
    session->model = model;
    session->ctx   = ctx;
    session->vocab = llama_model_get_vocab(model);
    session->n_ctx = static_cast<int>(ctx_params.n_ctx);

    LOGI("llama: loaded %s (ctx %d, threads %d)", path.c_str(), session->n_ctx, ctx_params.n_threads);
    return reinterpret_cast<jlong>(session);
}

JNIEXPORT void JNICALL
Java_io_github_hasanismail_themachine_llm_LlamaNative_nativeFree(
        JNIEnv * /* env */, jobject /* this */, jlong handle) {
    if (handle == 0) return;
    auto *session = reinterpret_cast<llama_session *>(handle);
    if (session->ctx != nullptr)   llama_free(session->ctx);
    if (session->model != nullptr) llama_model_free(session->model);
    delete session;
}

JNIEXPORT jstring JNICALL
Java_io_github_hasanismail_themachine_llm_LlamaNative_nativeDescribe(
        JNIEnv *env, jobject /* this */, jlong handle) {
    if (handle == 0) return env->NewStringUTF("");
    auto *session = reinterpret_cast<llama_session *>(handle);
    char buf[512];
    llama_model_desc(session->model, buf, sizeof(buf));
    std::string out = buf;
    out += "  |  params ";
    out += std::to_string(llama_model_n_params(session->model) / 1000000);
    out += "M  |  ctx ";
    out += std::to_string(session->n_ctx);
    out += "  |  vocab ";
    out += std::to_string(llama_vocab_n_tokens(session->vocab));
    return to_jstring(env, out);
}

/**
 * Runs the prompt and returns the completion.
 *
 * grammar may be empty for free-form output; when present it is GBNF and the
 * sampler is constrained by it. Sampling is greedy either way: this is parsing,
 * not writing, and a temperature above zero only invents variation we would then
 * have to defend against.
 */
/**
 * Returns true if llama.cpp accepts the grammar. Exists because a rejected grammar
 * reports only "failed to parse" with no position, so the only way to find the
 * offending rule is to try candidates quickly.
 */
/**
 * Returns true if the grammar accepts [textStr] in full.
 *
 * Parsing a grammar and accepting a string are very different claims, and only the
 * second one is what the sampler actually relies on: a grammar can parse cleanly and
 * still admit only its first alternative. This walks the candidate through exactly the
 * apply-then-accept path the sampling loop uses, so a disagreement between the two would
 * itself be the bug.
 */
JNIEXPORT jboolean JNICALL
Java_io_github_hasanismail_themachine_llm_LlamaNative_nativeGrammarAccepts(
        JNIEnv *env, jobject /* this */, jlong handle, jstring grammarStr, jstring textStr) {
    if (handle == 0) return JNI_FALSE;
    auto *session = reinterpret_cast<llama_session *>(handle);
    const std::string grammar = scoped_utf8(env, grammarStr);
    const std::string text = scoped_utf8(env, textStr);

    llama_sampler *g = nullptr;
    try {
        g = llama_sampler_init_grammar(session->vocab, grammar.c_str(), "root");
    } catch (const std::exception &e) {
        LOGE("llama: grammar threw: %s", e.what());
    }
    if (g == nullptr) return JNI_FALSE;

    // Tokenised as a continuation of a newline, then with that newline dropped.
    // SentencePiece prepends a space to text tokenised on its own, so the candidate
    // arrived as " {" — which the grammar rightly refused, making every tool look
    // unreachable when the fault was entirely in how the test asked the question.
    const std::string probe = "\n" + text;
    const int n = -llama_tokenize(session->vocab, probe.c_str(), static_cast<int32_t>(probe.size()),
                                  nullptr, 0, false, false);
    std::vector<llama_token> raw(n > 0 ? n : 0);
    if (n <= 0 || llama_tokenize(session->vocab, probe.c_str(), static_cast<int32_t>(probe.size()),
                                 raw.data(), n, false, false) < 0) {
        llama_sampler_free(g);
        return JNI_FALSE;
    }

    // Drop every leading token that is nothing but whitespace: the newline this probe
    // added, and the separate space token SentencePiece puts in front of it. The
    // candidates themselves all begin with '{', so nothing real is discarded.
    std::vector<llama_token> tokens;
    for (llama_token id : raw) {
        if (tokens.empty()) {
            char piece[256];
            const int len = llama_token_to_piece(session->vocab, id, piece, sizeof(piece), 0, true);
            bool blank = true;
            for (int c = 0; c < len; c++) {
                if (!std::isspace(static_cast<unsigned char>(piece[c]))) { blank = false; break; }
            }
            if (blank) continue;
        }
        tokens.push_back(id);
    }

    bool accepted = true;
    for (size_t i = 0; i < tokens.size(); i++) {
        llama_token_data one_data[1] = {{tokens[i], 0.0f, 0.0f}};
        llama_token_data_array one = {one_data, 1, -1, false};
        llama_sampler_apply(g, &one);
        if (!std::isfinite(one_data[0].logit)) {
            char piece[256];
            const int len = llama_token_to_piece(session->vocab, tokens[i], piece, sizeof(piece), 0, true);
            LOGE("llama: grammar rejected token %zu of %zu: '%.*s'", i, tokens.size(),
                 len > 0 ? len : 0, piece);
            accepted = false;
            break;
        }
        llama_sampler_accept(g, tokens[i]);
    }
    llama_sampler_free(g);
    return accepted ? JNI_TRUE : JNI_FALSE;
}

/**
 * Writes the keys and values for the tokens currently cached, so the next session can
 * start where this one left off.
 *
 * Prefix reuse already removes prefill from every command after the first. This removes
 * it from the first as well: the file is a few megabytes and reads back in a fraction of
 * a second, against the ten-plus seconds prefilling four hundred tokens costs on a
 * thermally throttled phone.
 */
JNIEXPORT jboolean JNICALL
Java_io_github_hasanismail_themachine_llm_LlamaNative_nativeSaveState(
        JNIEnv *env, jobject /* this */, jlong handle, jstring pathStr) {
    if (handle == 0) return JNI_FALSE;
    auto *session = reinterpret_cast<llama_session *>(handle);
    if (session->cached.empty()) return JNI_FALSE;
    const std::string path = scoped_utf8(env, pathStr);

    // Trimmed back to exactly the tokens being recorded. The cache also holds whatever
    // was generated after the prompt, and the token list handed to llama has to describe
    // the cells it is about to walk: with a longer cache than list, state_write_data read
    // past the end of the buffer and took the process down with a SIGSEGV inside memmove.
    llama_memory_seq_rm(llama_get_memory(session->ctx), 0,
                        static_cast<llama_pos>(session->cached.size()), -1);

    // Written beside its destination and moved into place, so an interrupted write
    // cannot leave a truncated cache that would be loaded and believed.
    const std::string temp = path + ".part";
    size_t written = 0;
    try {
        written = llama_state_seq_save_file(
                session->ctx, temp.c_str(), 0, session->cached.data(), session->cached.size());
    } catch (const std::exception &e) {
        LOGE("llama: state save threw: %s", e.what());
    }
    if (written == 0) {
        LOGE("llama: could not save state");
        remove(temp.c_str());
        return JNI_FALSE;
    }
    remove(path.c_str());
    if (rename(temp.c_str(), path.c_str()) != 0) {
        LOGE("llama: could not move state into place");
        remove(temp.c_str());
        return JNI_FALSE;
    }
    LOGI("llama: saved %zu tokens of state (%zu bytes)", session->cached.size(), written);
    return JNI_TRUE;
}

/**
 * Restores a previously saved cache, leaving the context untouched if it cannot be read.
 *
 * A stale or mismatched file is a normal thing to find — the model may have changed, or
 * the prompt — so failure here is quiet and simply means the next prompt is prefilled.
 */
JNIEXPORT jboolean JNICALL
Java_io_github_hasanismail_themachine_llm_LlamaNative_nativeLoadState(
        JNIEnv *env, jobject /* this */, jlong handle, jstring pathStr) {
    if (handle == 0) return JNI_FALSE;
    auto *session = reinterpret_cast<llama_session *>(handle);
    const std::string path = scoped_utf8(env, pathStr);

    std::vector<llama_token> tokens(static_cast<size_t>(session->n_ctx));
    size_t count = 0;
    size_t read = 0;
    try {
        read = llama_state_seq_load_file(session->ctx, path.c_str(), 0,
                                         tokens.data(), tokens.size(), &count);
    } catch (const std::exception &e) {
        LOGE("llama: state load threw: %s", e.what());
        read = 0;
    }
    if (read == 0 || count == 0) return JNI_FALSE;

    tokens.resize(count);
    session->cached = tokens;
    LOGI("llama: restored %zu tokens of state", count);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_io_github_hasanismail_themachine_llm_LlamaNative_nativeValidateGrammar(
        JNIEnv *env, jobject /* this */, jlong handle, jstring grammarStr) {
    if (handle == 0) return JNI_FALSE;
    auto *session = reinterpret_cast<llama_session *>(handle);
    const std::string grammar = scoped_utf8(env, grammarStr);
    llama_sampler *g = llama_sampler_init_grammar(session->vocab, grammar.c_str(), "root");
    if (g == nullptr) return JNI_FALSE;
    llama_sampler_free(g);
    return JNI_TRUE;
}

JNIEXPORT jstring JNICALL
Java_io_github_hasanismail_themachine_llm_LlamaNative_nativeGenerate(
        JNIEnv *env, jobject /* this */, jlong handle, jstring promptStr, jstring grammarStr,
        jint maxTokens, jstring stopAtStr) {
    if (handle == 0) return env->NewStringUTF("");
    auto *session = reinterpret_cast<llama_session *>(handle);

    const std::string prompt  = scoped_utf8(env, promptStr);
    const std::string grammar = scoped_utf8(env, grammarStr);
    // Generation ends as soon as the output contains this, if it is non-empty. The
    // caller uses it to stop a tool call the moment its name is known, when the rest of
    // the call is about to be thrown away anyway.
    const std::string stop_at = scoped_utf8(env, stopAtStr);

    const int n_prompt = -llama_tokenize(
            session->vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()),
            nullptr, 0, true, true);
    if (n_prompt <= 0 || n_prompt >= session->n_ctx) {
        LOGE("llama: prompt does not fit (%d tokens, ctx %d)", n_prompt, session->n_ctx);
        return env->NewStringUTF("");
    }

    std::vector<llama_token> tokens(n_prompt);
    if (llama_tokenize(session->vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()),
                       tokens.data(), n_prompt, true, true) < 0) {
        LOGE("llama: tokenize failed");
        return env->NewStringUTF("");
    }

    // Every request repeats the same tool declarations and examples and differs only in
    // the closing user turn, so the overwhelming majority of these tokens were already
    // prefilled by the previous command. Keep the identical leading run and re-prefill
    // only the tail: the divergent suffix is evicted from the cache and everything up to
    // it is reused as-is. One token is always left to decode, since a batch of nothing
    // produces no logits to sample from.
    size_t reuse = 0;
    if (getenv("MACHINE_NO_PREFIX_REUSE") == nullptr) {
        while (reuse < session->cached.size() && reuse + 1 < tokens.size() &&
               session->cached[reuse] == tokens[reuse]) {
            reuse++;
        }
    }
    if (!llama_memory_seq_rm(llama_get_memory(session->ctx), 0,
                             static_cast<llama_pos>(reuse), -1)) {
        // Some cache layouts refuse a partial removal; a full reset is always valid.
        llama_memory_clear(llama_get_memory(session->ctx), true);
        reuse = 0;
    }
    session->cached = tokens;

    LOGI("llama: prompt is %d tokens, %zu reused", n_prompt, reuse);

    // The grammar is held on its own rather than inside a sampler chain, so that it can
    // be consulted for a single token instead of the whole vocabulary. See the sampling
    // loop below for why that distinction is worth this much ceremony.
    llama_sampler *grammar_sampler = nullptr;
    if (!grammar.empty()) {
        // llama_sampler_init_grammar throws rather than returning null for some
        // malformed grammars, and an uncaught C++ exception here takes the whole app
        // down. A bad grammar is a bug worth fixing, but it must never be a crash.
        try {
            grammar_sampler = llama_sampler_init_grammar(session->vocab, grammar.c_str(), "root");
        } catch (const std::exception &e) {
            LOGE("llama: grammar threw: %s", e.what());
        }
        if (grammar_sampler == nullptr) {
            LOGE("llama: grammar rejected");
            return env->NewStringUTF("");
        }
    }

    std::string out;

    // The prompt batch is built by hand rather than with llama_batch_get_one, which
    // leaves batch.logits null. llama.cpp reads that as "produce logits for every
    // token", so the output projection — 262144 x 1152 for Gemma — ran once per prompt
    // token and materialised a ~420 MB logits buffer, of which all but the last row was
    // discarded. Marking only the final token cut prefill from ~14.5 s to a fraction of
    // it, and is the single largest win in the pipeline.
    const size_t n_new = tokens.size() - reuse;
    llama_batch prompt_batch = llama_batch_init(static_cast<int32_t>(n_new), 0, 1);
    for (size_t i = 0; i < n_new; i++) {
        prompt_batch.token[i] = tokens[reuse + i];
        prompt_batch.pos[i] = static_cast<llama_pos>(reuse + i);
        prompt_batch.n_seq_id[i] = 1;
        prompt_batch.seq_id[i][0] = 0;
        prompt_batch.logits[i] = false;
    }
    prompt_batch.logits[n_new - 1] = true;
    prompt_batch.n_tokens = static_cast<int32_t>(n_new);
    llama_batch batch = prompt_batch;

    // Reused for every generated token; batch_init allocates, so it is not done in-loop.
    llama_batch step = llama_batch_init(1, 0, 1);
    step.n_tokens = 1;
    step.n_seq_id[0] = 1;
    step.seq_id[0][0] = 0;
    step.logits[0] = true;
    llama_pos n_past = static_cast<llama_pos>(tokens.size());
    // Everything below can throw from inside ggml or the grammar sampler. Whatever has
    // been produced so far is returned instead of terminating; the caller treats an
    // empty or partial result as "I did not understand", which is recoverable.
    try {

    const auto t_start = std::chrono::steady_clock::now();
    int64_t prefill_us = 0;
    int64_t decode_us = 0;
    int64_t sample_us = 0;
    int produced_total = 0;

    const int n_vocab = llama_vocab_n_tokens(session->vocab);
    // Scratch for the rare full-vocabulary pass; allocated once, not per token.
    std::vector<llama_token_data> candidates(n_vocab);
    int64_t grammar_fallbacks = 0;

    const int limit = maxTokens > 0 ? maxTokens : 200;
    for (int produced = 0; produced < limit; produced++) {
        const auto t_decode = std::chrono::steady_clock::now();
        if (llama_decode(session->ctx, batch) != 0) {
            LOGE("llama: decode failed");
            session->cached.clear();
            break;
        }
        const auto t_sampled = std::chrono::steady_clock::now();
        const int64_t this_decode =
            std::chrono::duration_cast<std::chrono::microseconds>(t_sampled - t_decode).count();
        if (produced == 0) prefill_us = this_decode; else decode_us += this_decode;

        const float *logits = llama_get_logits_ith(session->ctx, -1);
        if (logits == nullptr) {
            LOGE("llama: no logits");
            break;
        }

        // Greedy pick first, ignoring the grammar. This is a plain scan the compiler
        // vectorises; the grammar is the expensive part and is deliberately not involved
        // yet.
        llama_token token = 0;
        float best = logits[0];
        for (int i = 1; i < n_vocab; i++) {
            if (logits[i] > best) {
                best = logits[i];
                token = i;
            }
        }

        if (grammar_sampler != nullptr) {
            // Checking one token instead of all of them is the whole optimisation.
            // Gemma's vocabulary is 262k entries, and running the grammar across every
            // one of them per step costs far more than the model's own forward pass —
            // it was the difference between ~11 s and well under a second per reply.
            // Once the model is emitting well-formed output its top pick is almost
            // always already legal, so the full sweep below is a rarely-taken path.
            llama_token_data one_data[1] = {{token, best, 0.0f}};
            llama_token_data_array one = {one_data, 1, -1, false};
            llama_sampler_apply(grammar_sampler, &one);

            if (!std::isfinite(one_data[0].logit)) {
                grammar_fallbacks++;
                for (int i = 0; i < n_vocab; i++) {
                    candidates[i] = {i, logits[i], 0.0f};
                }
                llama_token_data_array all = {candidates.data(), static_cast<size_t>(n_vocab), -1, false};
                llama_sampler_apply(grammar_sampler, &all);

                token = -1;
                float best_valid = -INFINITY;
                for (size_t i = 0; i < all.size; i++) {
                    if (std::isfinite(all.data[i].logit) && all.data[i].logit > best_valid) {
                        best_valid = all.data[i].logit;
                        token = all.data[i].id;
                    }
                }
                if (token < 0) {
                    LOGE("llama: grammar left no legal token");
                    break;
                }
            }
            // Exactly one accept per token. llama_sampler_sample would have done this
            // internally, but it is not being used here; accepting twice aborts with
            // "Unexpected empty grammar stack after accepting piece".
            llama_sampler_accept(grammar_sampler, token);
        }

        if (llama_vocab_is_eog(session->vocab, token)) break;

        char piece[256];
        const int n = llama_token_to_piece(session->vocab, token, piece, sizeof(piece), 0, true);
        if (n > 0) out.append(piece, n);

        produced_total++;
        // After the accept above, never before it: the grammar has already advanced past
        // this token, and stopping here leaves it consistent.
        if (!stop_at.empty()) {
            const size_t at = out.find(stop_at);
            if (at != std::string::npos) {
                // Cut exactly at the marker. One token can carry the marker and the
                // start of what follows, and the caller closes this fragment into JSON:
                // an overshoot left it holding half an argument and unparseable.
                out.resize(at + stop_at.size());
                break;
            }
        }
        sample_us += std::chrono::duration_cast<std::chrono::microseconds>(
            std::chrono::steady_clock::now() - t_sampled).count();

        step.token[0] = token;
        step.pos[0] = n_past++;
        batch = step;
    }
    {
        const int64_t total_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now() - t_start).count();
        LOGI("llama: %d prompt tokens, %d generated | prefill %lld ms, decode %lld ms (%.1f tok/s), sample %lld ms | total %lld ms",
             n_prompt, produced_total,
             static_cast<long long>(prefill_us / 1000),
             static_cast<long long>(decode_us / 1000),
             produced_total > 1 ? (produced_total - 1) * 1e6 / static_cast<double>(decode_us ? decode_us : 1) : 0.0,
             static_cast<long long>(sample_us / 1000),
             static_cast<long long>(total_ms));
    }
    if (grammar_fallbacks > 0) {
        LOGI("llama: %lld full-vocabulary grammar passes", static_cast<long long>(grammar_fallbacks));
    }

    } catch (const std::exception &e) {
        LOGE("llama: generation threw: %s", e.what());
        session->cached.clear();
    }

    if (grammar_sampler != nullptr) llama_sampler_free(grammar_sampler);
    llama_batch_free(prompt_batch);
    llama_batch_free(step);
    return to_jstring(env, out);
}

} // extern "C"
