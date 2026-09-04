/*
 * The Machine — an offline voice assistant for Android.
 * Copyright (C) 2026 Hasan Ismail
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
package io.github.hasanismail.themachine.stt

/**
 * Raw JNI surface for whisper.cpp. Not used directly — [WhisperEngine] owns the
 * lifetime of the handle and is the thing the rest of the app talks to.
 */
internal object WhisperNative {

    /** Returns an opaque context handle, or 0 if the model could not be loaded. */
    external fun nativeLoad(modelPath: String): Long

    external fun nativeFree(handle: Long)

    /**
     * 16 kHz mono float PCM in [-1, 1]. [prompt] biases decoding toward its words (contact
     * names); pass "" for none. Returns the transcript, or "" on failure.
     */
    external fun nativeTranscribe(handle: Long, samples: FloatArray, threads: Int, prompt: String): String
}
