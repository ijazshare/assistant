/*
 * The Machine — an offline voice assistant for Android.
 * Copyright (C) 2026 Hasan Ismail
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
package io.github.hasanismail.themachine.tools

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Structural checks on the generated GBNF.
 *
 * llama.cpp reports a bad grammar as "failed to parse grammar" with no rule and no
 * offset, and a grammar that parses but is subtly wrong reports nothing at all — it just
 * makes the model behave strangely, which is a long way from the cause. Both of the bugs
 * these tests pin cost a device round-trip each to find, and neither needed one.
 */
class ToolGrammarTest {

    private val grammar = ToolGrammar.build(MachineTools.all)

    /** Rules this file generates, as opposed to the shared JSON primitives. */
    private fun generatedRules(): List<String> =
        grammar.lines().filter { it.matches(Regex("^(root|call|args|h|t|v)[0-9-]* ::=.*")) }

    @Test
    fun `every literal in a generated rule is closed`() {
        // The enum values were once built with one quote too many. The extra quote
        // opened a literal that swallowed the newline and the rules after it, silently
        // merging tool alternatives until the model could only ever answer set_alarm.
        val unbalanced = generatedRules().filter { line ->
            line.replace("\\\"", "").count { it == '"' } % 2 != 0
        }
        assertThat(unbalanced.joinToString(" / ")).isEmpty()
    }

    @Test
    fun `no argument can be repeated`() {
        // A repeat operator anywhere in the argument rules means some key may be emitted
        // twice, which is how "minute" arrived eleven times in one call.
        for (line in generatedRules()) {
            assertThat(line).doesNotContain(")*")
            assertThat(line).doesNotContain(")+")
        }
    }

    @Test
    fun `enumerated values appear as quoted json strings`() {
        assertThat(grammar).contains("""\"down\"""")
        assertThat(grammar).contains("""\"back\"""")
    }

    @Test
    fun `every referenced rule is defined`() {
        val defined = grammar.lines()
            .mapNotNull { it.substringBefore(" ::=", "").trim().ifEmpty { null } }
            .toSet()
        val referenced = generatedRules().flatMap { line ->
            // Rule references are the bare words outside any quoted literal.
            line.substringAfter("::=")
                // Escaped quotes are literal content; dropping them first means the
                // remaining quotes are unambiguously delimiters.
                .replace("\\\"", "")
                .replace(Regex("\"[^\"]*\""), " ")
                // Character classes hold digits and ranges, not rule names.
                .replace(Regex("""\[[^]]*]"""), " ")
                .split(Regex("[^A-Za-z0-9-]+"))
                .filter { it.isNotEmpty() && !it.all { c -> c.isDigit() } }
        }.toSet()
        assertThat(defined).containsAtLeastElementsIn(referenced)
    }

    @Test
    fun `a bounded argument only admits values inside its range`() {
        // set_alarm's hour is 1..12, so the rule must spell exactly those and nothing
        // that could produce 16 — the answer the model gave for "half past six in the
        // evening" back when it was free to convert to 24-hour time itself.
        val index = MachineTools.all.indexOfFirst { it.name == MachineTools.SET_ALARM }
        val hour = MachineTools.all[index].params.indexOfFirst { it.name == "hour" }
        val rule = grammar.lines().first { it.startsWith("v$index-$hour ::=") }
        assertThat(rule).isEqualTo("""v$index-$hour ::= [0-9] | "1" [0-9] | "2" [0-3]""")
    }

    @Test
    fun `each tool contributes exactly one alternative to root`() {
        val root = grammar.lines().first { it.startsWith("root ::=") }
        assertThat(root.split("|")).hasSize(MachineTools.all.size)
    }

    @Test
    fun `a required argument cannot be skipped`() {
        // set_alarm's hour is required, so its head rule must have no skip alternative.
        val index = MachineTools.all.indexOfFirst { it.name == MachineTools.SET_ALARM }
        val head = grammar.lines().first { it.startsWith("h$index-0 ::=") }
        assertThat(head).doesNotContain("|")
    }

    @Test
    fun `an optional argument can be skipped`() {
        val index = MachineTools.all.indexOfFirst { it.name == MachineTools.SET_TIMER }
        // Every one of set_timer's parameters is optional, so position 0 must offer a
        // path that omits it.
        val head = grammar.lines().first { it.startsWith("h$index-0 ::=") }
        assertThat(head).contains("| h$index-1")
    }
}
