/*
 * Assistant — an offline voice assistant for Android.
 * Copyright (C) 2026 Hasan Ismail
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
package io.github.ijazshare.assistant.assistant

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * Sums, worked out by code rather than by a model.
 *
 * The 1B routed "that's 4 times 37" to set_timer, and the larger model said 96,000 times
 * 30,000 was nine hundred sixty million. A model recalls arithmetic; it does not do it.
 *
 * So this answers only when the *whole* sentence is a sum: a little filler, numbers,
 * operators, nothing else. "Call 6-1-1", "set a timer for 4 minutes" and "2 plus 2
 * minutes" all have something left over, so they return null and go on to the model as
 * before. Declining is always safe; answering a sentence that was not a sum is not.
 */
object Arithmetic {

    private val SAYABLE = BigDecimal.TEN.pow(30)
    private const val LONGEST_POWER = 100
    private const val PLACES = 4
    private const val GROUP = 3

    // Before the first number only: "what's", "how much is", "tell me the answer to".
    private val LEADING = setOf(
        "what", "whats", "thats", "is", "how", "much", "calculate", "compute", "tell", "me", "please", "the",
        "answer", "to",
    )
    private val TRAILING = setOf("equal", "equals", "please")

    // Symbols a speech recogniser may write instead of the word, in the order they apply.
    private val SYMBOLS = listOf(
        Regex("""(?<![a-z])x(?![a-z])""") to "times",
        Regex("[*×]") to "times",
        Regex("[÷/]") to "divided by",
        Regex("""\+""") to "plus",
        // Only a spaced hyphen: "6-1-1" is a phone number and "ninety-six" is a number.
        Regex("""\s-\s|−""") to "minus",
        Regex("%") to "percent",
    )
    private val THOUSANDS_COMMA = Regex("""(?<=\d),(?=\d{3}(?!\d))""")
    private val WORD_HYPHEN = Regex("(?<=[a-z])-(?=[a-z])")

    // Each phrase and the words it is said back with.
    private val ADDITIVE = mapOf("plus" to "plus", "minus" to "minus")
    private val MULTIPLICATIVE = mapOf(
        "times" to "times",
        "multiplied by" to "times",
        "divided by" to "divided by",
        "over" to "divided by",
    )
    private val TIGHT = mapOf("to the power of" to "to the power of", "percent of" to "percent of")
    private val POSTFIX = mapOf("squared" to "squared", "cubed" to "cubed")
    private val ROOT = mapOf("square root of" to "square root of")

    private enum class Problem { ZERO, UNSAYABLE }

    /** The spoken reply, or null if [transcript] is not nothing but an arithmetic question. */
    fun answer(transcript: String): String? {
        val sum = Reading(words(transcript))
        val result = sum.expression()
        if (!sum.finished || sum.said.size < 2) return null
        return when (sum.problem) {
            Problem.ZERO -> "You can't divide by zero."
            Problem.UNSAYABLE -> null
            null -> "${sum.said.joinToString(" ")} is ${say(result, rounded = true)}."
        }
    }

    private fun words(transcript: String): List<String> {
        var text = transcript.lowercase().replace(Regex("""[?.!\s]+$"""), "")
        text = text.replace(THOUSANDS_COMMA, "").replace("'", "").replace("’", "")
        text = SYMBOLS.fold(text) { said, (symbol, word) -> said.replace(symbol, " $word ") }
        val all = text.replace(WORD_HYPHEN, " ").replace(",", " ").trim().split(Regex("""\s+"""))
        return all.dropWhile { it in LEADING }.dropLastWhile { it in TRAILING }
    }

    /** Digits with US grouping; a result is first rounded, and a small one keeps its first digits. */
    private fun say(value: BigDecimal, rounded: Boolean = false): String {
        var shown = value
        if (rounded) {
            shown = value.setScale(PLACES, RoundingMode.HALF_UP)
            // 1 divided by 30,000 is not "0".
            if (shown.signum() == 0 && value.signum() != 0) shown = value.round(MathContext(PLACES))
        }
        val parts = shown.abs().stripTrailingZeros().toPlainString().split('.')
        val grouped = parts[0].reversed().chunked(GROUP).joinToString(",").reversed()
        val sign = if (shown.signum() < 0) "negative " else ""
        return sign + grouped + parts.getOrNull(1)?.let { ".$it" }.orEmpty()
    }

    /**
     * One pass over the words. A malformed or unsayable sum is recorded, not thrown, so the
     * whole sentence is always read to its end before anything is answered: "2 divided by 0
     * minutes" must be declined, not told it cannot divide by zero.
     */
    private class Reading(private val words: List<String>) {
        private var at = 0
        private var malformed = false
        val said = mutableListOf<String>()
        var problem: Problem? = null
            private set

        val finished get() = at == words.size && !malformed

        fun expression(): BigDecimal {
            var total = product()
            var op = take(ADDITIVE)
            while (op != null) {
                val next = product()
                total = fit(if (op == "plus") total + next else total - next)
                op = take(ADDITIVE)
            }
            return total
        }

        private fun product(): BigDecimal {
            var total = tight()
            var op = take(MULTIPLICATIVE)
            while (op != null) {
                val next = tight()
                total = if (op == "times") fit(total * next) else divide(total, next)
                op = take(MULTIPLICATIVE)
            }
            return total
        }

        // Power, squared, cubed and percent: left to right, so a chain is read as it is said.
        private fun tight(): BigDecimal {
            var total = operand()
            while (true) {
                val op = take(TIGHT) ?: take(POSTFIX) ?: return total
                total = when (op) {
                    "squared" -> fit(total * total)
                    "cubed" -> fit(total * total * total)
                    "percent of" -> fit(total.multiply(operand()).movePointLeft(2))
                    else -> power(total, operand())
                }
            }
        }

        private fun operand(): BigDecimal {
            val root = take(ROOT) != null
            val value = number()
            return when {
                value == null -> fail(null)
                root -> squareRoot(value)
                else -> value
            }
        }

        private fun number(): BigDecimal? {
            val first = words.getOrNull(at) ?: return null
            val value = if (DIGITS.matches(first)) {
                at++
                BigDecimal(first)
            } else {
                val end = (at until words.size).firstOrNull { !Spoken.fits(words[it], it == at) } ?: words.size
                Spoken.value(words.subList(at, end))?.also { at = end }?.toBigDecimal()
            } ?: return null
            said += say(value)
            return fit(value)
        }

        /** The phrase from [table] that comes next, said back in its canonical words. */
        private fun take(table: Map<String, String>): String? {
            val (phrase, canonical) = table.entries.firstOrNull { (phrase, _) ->
                val size = phrase.count { it == ' ' } + 1
                at + size <= words.size && words.subList(at, at + size).joinToString(" ") == phrase
            } ?: return null
            at += phrase.count { it == ' ' } + 1
            said += canonical
            return canonical
        }

        private fun divide(a: BigDecimal, b: BigDecimal): BigDecimal =
            if (b.signum() == 0) fail(Problem.ZERO) else fit(a.divide(b, MathContext.DECIMAL128))

        // Whole exponents only: the exact power of anything sayable stays small enough to hold.
        private fun power(base: BigDecimal, exponent: BigDecimal): BigDecimal {
            val whole = exponent.stripTrailingZeros().scale() <= 0 && exponent.signum() >= 0
            val small = exponent <= BigDecimal(LONGEST_POWER)
            return if (whole && small) fit(base.pow(exponent.toInt())) else fail(Problem.UNSAYABLE)
        }

        // Newton's method: BigDecimal.sqrt needs a newer Android than this app supports.
        private fun squareRoot(value: BigDecimal): BigDecimal {
            if (value.signum() < 0) return fail(Problem.UNSAYABLE)
            if (value.signum() == 0) return value
            var guess = BigDecimal(Math.sqrt(value.toDouble()))
            repeat(NEWTON_STEPS) {
                guess = (guess + value.divide(guess, MathContext.DECIMAL128)).divide(TWO, MathContext.DECIMAL128)
            }
            return guess
        }

        // Nothing is said that is too big to read out.
        private fun fit(value: BigDecimal) = if (value.abs() < SAYABLE) value else fail(Problem.UNSAYABLE)

        /** Records the first problem; null is a sentence that was not a sum at all. */
        private fun fail(why: Problem?): BigDecimal {
            if (why == null) {
                malformed = true
            } else if (problem == null) {
                problem = why
            }
            return BigDecimal.ZERO
        }
    }

    /** Numbers said in words, from zero up to the billions. */
    private object Spoken {
        private val SMALL = listOf(
            "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven",
            "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen",
        ).withIndex().associate { (index, word) -> word to index + 1L }
        private val TENS = listOf("twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")
            .withIndex().associate { (index, word) -> word to (index + 2) * 10L }
        private val SCALES = mapOf("thousand" to 1_000L, "million" to 1_000_000L, "billion" to 1_000_000_000L)
        private const val HUNDRED = 100L
        private const val UNITS = 10L

        /** Whether [word] can be part of a spoken number; "a" only opens one: "a hundred". */
        fun fits(word: String, first: Boolean) =
            word in SMALL || word in TENS || word in SCALES || word in setOf("zero", "hundred", "and") ||
                (first && word == "a")

        /** The number [run] says, or null if it is not one ("five six", "one hundred hundred"). */
        fun value(run: List<String>): Long? = plain(run)?.let { if (it == listOf("zero")) 0L else total(it) }

        private fun big(word: String?) = word != null && (word == "hundred" || word in SCALES)

        // "a hundred" is "one hundred"; "and" belongs only straight after hundred or a scale.
        private fun plain(run: List<String>): List<String>? {
            val words = run.toMutableList()
            if (words.firstOrNull() == "a" && big(words.getOrNull(1))) words[0] = "one"
            val placed = words.withIndex().all { (i, word) ->
                word != "and" || (big(words.getOrNull(i - 1)) && i < words.lastIndex)
            }
            return words.filter { it != "and" }.takeIf { placed }
        }

        // Groups of a thousand from the largest scale down, each scale used once.
        private fun total(words: List<String>): Long? {
            var sum = 0L
            var lastScale = Long.MAX_VALUE
            var rest = words
            while (rest.isNotEmpty()) {
                val cut = rest.indexOfFirst { it in SCALES }
                val scale = if (cut < 0) 1L else SCALES.getValue(rest[cut])
                val part = underThousand(if (cut < 0) rest else rest.subList(0, cut)) ?: return null
                if (scale >= lastScale) return null
                sum += part * scale
                lastScale = scale
                rest = if (cut < 0) emptyList() else rest.subList(cut + 1, rest.size)
            }
            return sum
        }

        private fun underThousand(words: List<String>): Long? {
            val hundred = words.getOrNull(1) == "hundred"
            val hundreds = if (hundred) SMALL[words[0]]?.takeIf { it < UNITS } else 0L
            val tail = tens(if (hundred) words.drop(2) else words)
            return if (words.isEmpty() || hundreds == null || tail == null) null else hundreds * HUNDRED + tail
        }

        // Up to two words: "five", "fifteen", "twenty", "twenty five".
        private fun tens(words: List<String>): Long? = when (words.size) {
            0 -> 0L
            1 -> SMALL[words[0]] ?: TENS[words[0]]
            2 -> TENS[words[0]]?.let { ten -> SMALL[words[1]]?.takeIf { it < UNITS }?.plus(ten) }
            else -> null
        }
    }

    private val DIGITS = Regex("""\d+(\.\d+)?""")
    private val TWO = BigDecimal(2)
    private const val NEWTON_STEPS = 6
}
