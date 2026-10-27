/*
 * Assistant — an offline voice assistant for Android.
 * Copyright (C) 2026 Hasan Ismail
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
package io.github.hasanismail.themachine.assistant

/**
 * Decides whether a screen request wants the words read out or a question answered.
 *
 * The tool schema has an optional `question`, and a 1B model fills it perhaps half the
 * time — "read the screen and summarise it" came back as a bare `read_screen`, and the
 * assistant recited a chat log at the user instead of summarising it. Leaving the
 * decision to the small model means the feature works or not depending on its mood.
 *
 * So the transcript decides, and the model's argument is only taken as corroboration.
 * The default is to read verbatim, which is the cheap and literal thing; escalation
 * happens only on a word that asks for something to be *done* with the text. Getting
 * this wrong in the safe direction costs a recital, which is what used to happen anyway.
 */
object ScreenQuestion {

    /**
     * The instruction to answer about the screen, or null to read it out word for word.
     *
     * Returns the user's own sentence rather than the model's paraphrase: it is what they
     * actually asked, it costs nothing to carry, and the larger model reads it better
     * than a 1B's summary of it.
     */
    fun of(transcript: String, modelArgument: String? = null): String? {
        val supplied = modelArgument?.trim().orEmpty()
        if (supplied.isNotEmpty()) return transcript.trim().ifEmpty { supplied }

        val words = transcript.lowercase().split(NON_LETTER).filter { it.isNotEmpty() }
        val asks = words.any { word -> ANALYSIS.any { word.startsWith(it) } }
        return if (asks) transcript.trim() else null
    }

    /**
     * Whether the user pointed at the screen at all, which read_screen needs to be theirs.
     *
     * The router reaches for read_screen when it has nothing better: questions it could
     * not place, and above all speech that was never meant for it — on a recorded
     * benchmark it was the 4B's commonest wrong call on background speech, while every
     * request there that really was about the screen said one of these words. Without one
     * the call is the model's guess, and a guess reads out whatever happens to be open.
     */
    fun pointsAtScreen(transcript: String): Boolean =
        transcript.lowercase().split(NON_LETTER).any { word ->
            word in POINTING || TEXT_ONLY.any { word.startsWith(it) }
        }

    private val POINTING = setOf("this", "these", "here", "screen", "page", "read", "say", "says")

    /**
     * Requests that can only be about text in front of the user, so "summarise it" and
     * "translate that" need no pointing word. Not "explain" or "mean": "explain gravity"
     * and "what does exorbitant mean" are questions, and the model sends those here too.
     */
    private val TEXT_ONLY = listOf("summar", "translat", "tldr")

    /**
     * Prefixes, not whole words, so "summarise", "summarize", "summary" and "summarising"
     * all count without listing each.
     *
     * Deliberately excludes "read", "say" and "what does" — those are the verbatim
     * requests, and treating them as questions would send every plain read through a
     * three-gigabyte model for no benefit.
     */
    private val ANALYSIS = listOf(
        "summar", "tldr",
        "explain", "explanation",
        "translat",
        "mean", "means", "meaning",
        "wrong", "error",
        "should",
    )

    private val NON_LETTER = Regex("[^a-z]+")
}
