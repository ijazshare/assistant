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

import android.util.Log
import io.github.hasanismail.themachine.settings.RemoteLlmConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * Answers a general-knowledge question with a remote model over the OpenAI chat-completions
 * API — the lingua franca that OpenRouter, llama.cpp's server, vLLM and Ollama all speak, so
 * the endpoint swaps to a box at home by changing a URL and nothing else.
 *
 * Only the question text and the user's own notes are sent; audio never is, and speech
 * recognition, tool routing and speech synthesis stay on the phone. Every failure — no
 * network, a timeout, a non-2xx, an unparseable body — returns null so the caller falls back
 * to the on-device model exactly as if no remote had been configured.
 */
class RemoteAnswerer(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build(),
) {

    suspend fun answer(
        config: RemoteLlmConfig,
        question: String,
        adminName: String,
        userContext: String,
    ): Completion? = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        val body = runCatching { post(config, requestBody(config.model, question, adminName, userContext)) }
            .onFailure { Log.w(TAG, "remote answer failed", it) }
            .getOrNull() ?: return@withContext null
        val text = replyText(body) ?: return@withContext null
        val millis = (System.nanoTime() - started) / NANOS_PER_MILLI
        Log.i(TAG, "remote answered via ${config.model} in $millis ms")
        Completion(text, millis, via = config.model)
    }

    /** The response body, or null on a non-2xx. Throws on a network failure; the caller catches. */
    private fun post(config: RemoteLlmConfig, json: String): String? {
        val request = Request.Builder()
            .url(config.baseUrl.trimEnd('/') + "/chat/completions")
            .header("Authorization", "Bearer ${config.apiKey}")
            .post(json.toRequestBody(JSON_MEDIA))
            .build()
        return client.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                response.body.string()
            } else {
                Log.w(TAG, "remote answer failed: HTTP ${response.code}")
                null
            }
        }
    }

    @Serializable
    private data class Message(val role: String, val content: String)

    @Serializable
    private data class ChatRequest(
        val model: String,
        val messages: List<Message>,
        @SerialName("max_tokens") val maxTokens: Int,
        val temperature: Double,
    )

    @Serializable
    private data class Choice(val message: Message)

    @Serializable
    private data class ChatResponse(val choices: List<Choice> = emptyList())

    companion object {
        private const val TAG = "TheMachine"
        private const val CONNECT_TIMEOUT_SECONDS = 8L
        private const val READ_TIMEOUT_SECONDS = 20L
        private const val NANOS_PER_MILLI = 1_000_000
        private const val MAX_TOKENS = 160
        private const val TEMPERATURE = 0.2
        private const val CONTEXT_BUDGET = 1500
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private val NOW_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE HH:mm, d MMMM yyyy")
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        /**
         * The same honesty rules the on-device answerer is held to, minus the "offline"
         * framing: a remote model still has no live data, still must not invent a number,
         * and still cannot act on the phone from a spoken reply.
         */
        fun systemPrompt(adminName: String, userContext: String, now: LocalDateTime = LocalDateTime.now()): String =
            buildString {
                append(
                    "You are $adminName's assistant, answering by voice on their phone. Answer " +
                        "general-knowledge questions — established facts, definitions, arithmetic — in " +
                        "ONE short plain sentence suitable to be read aloud. If a correct answer would need " +
                        "live or changing information you do not have — weather, news, prices, scores, or " +
                        "how many of something there are right now — say you cannot know that, instead of " +
                        "guessing. Never invent a number you are not sure of. Do not mention searching or " +
                        "browsing. You cannot send a message, place a call, set an alarm or otherwise act on " +
                        "the phone from this reply; if asked to do one of those, say you cannot do that here " +
                        "— never claim you did it.",
                )
                if (userContext.isNotBlank()) {
                    append("\n\nAbout $adminName:\n").append(userContext.take(CONTEXT_BUDGET))
                }
                append("\n\nNow: ").append(now.format(NOW_FORMAT))
            }

        /** The chat-completions request as JSON. Public so it can be checked without a network. */
        fun requestBody(model: String, question: String, adminName: String, userContext: String): String =
            json.encodeToString(
                ChatRequest.serializer(),
                ChatRequest(
                    model = model,
                    messages = listOf(
                        Message("system", systemPrompt(adminName, userContext)),
                        Message("user", question),
                    ),
                    maxTokens = MAX_TOKENS,
                    temperature = TEMPERATURE,
                ),
            )

        /** The first choice's text, trimmed, or null if the body has none. Public for the same reason. */
        fun replyText(body: String): String? = runCatching {
            json.decodeFromString(ChatResponse.serializer(), body)
                .choices.firstOrNull()?.message?.content?.trim()?.takeIf { it.isNotEmpty() }
        }.getOrNull()
    }
}
