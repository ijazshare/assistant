/*
 * The Machine — an offline voice assistant for Android.
 * Copyright (C) 2026 Hasan Ismail
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
package io.github.hasanismail.themachine.llm

/** Raw JNI surface for llama.cpp. [LlamaEngine] owns the handle's lifetime. */
internal object LlamaNative {

    external fun nativeLoad(modelPath: String, contextSize: Int, threads: Int): Long

    external fun nativeFree(handle: Long)

    external fun nativeDescribe(handle: Long): String

    /** True if llama.cpp accepts this GBNF. Used to isolate a bad rule. */
    external fun nativeValidateGrammar(handle: Long, grammar: String): Boolean

    /** Whether the grammar accepts [text] in full, token by token. */
    external fun nativeGrammarAccepts(handle: Long, grammar: String, text: String): Boolean

    /** grammar may be empty for free-form output; when present it is GBNF. */
    external fun nativeGenerate(handle: Long, prompt: String, grammar: String, maxTokens: Int): String
}
