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

/**
 * Builds the GBNF grammar that constrains the model's output.
 *
 * This is the load-bearing idea behind using a 1B model for command parsing. The
 * sampler may only choose tokens the grammar permits, so the output is valid JSON
 * naming a real tool with declared arguments *by construction* — not because the
 * model was asked nicely and usually complies. There is no parse-and-retry loop and
 * no "the model said something odd" branch, because those states are unreachable.
 *
 * Generated in Kotlin rather than through llama.cpp's JSON-schema converter so it
 * can be unit-tested without a device or a model.
 */
object ToolGrammar {

    /**
     * One alternative per tool, each pinning its own name alongside its own argument
     * set, so the model cannot pair one tool's name with another's arguments.
     *
     * Generated rule names use '-' rather than '_' as a separator, and that is not a
     * style choice: llama.cpp's is_word_char accepts letters, digits and '-' only, so a
     * name like "v0_0" is read as the rule "v0" followed by unparseable input. The
     * parser then reports nothing but "failed to parse grammar" — no rule, no offset.
     */
    fun build(tools: List<Tool>): String {
        require(tools.isNotEmpty()) { "A grammar with no tools would permit nothing." }

        val out = StringBuilder()
        out.appendLine("root ::= " + tools.indices.joinToString(" | ") { "call$it" })
        out.appendLine()

        tools.forEachIndexed { index, tool ->
            out.appendLine(callRule(index, tool))
        }
        out.appendLine()

        tools.forEachIndexed { index, tool ->
            if (tool.params.isNotEmpty()) {
                out.append(argumentRules(index, tool))
                tool.params.forEachIndexed { p, param -> out.appendLine(valueRule(index, p, param)) }
            }
        }

        out.appendLine(commonRules())
        return out.toString()
    }

    private fun callRule(index: Int, tool: Tool): String {
        val head = """"{\"tool\":\"${tool.name}\"""""
        val args = if (tool.params.isEmpty()) {
            ""
        } else {
            """ ",\"arguments\":" args$index"""
        }
        return """call$index ::= $head$args "}""""
    }

    /**
     * Parameters in declaration order: each required one mandatory, each optional one
     * skippable, and none of them repeatable.
     *
     * That last property is why this is a chain rather than the obvious `("," pair)*`.
     * Repetition looks harmless until a tool has more than one optional parameter — the
     * model emitted `"minute":-1` eleven times in a row and ran to the token limit,
     * because nothing in the grammar said it had already given that argument.
     *
     * Two rules per position express it. `h` is the state where nothing has been emitted
     * yet, so its pair carries no leading comma; `t` is the state where something has,
     * so every further pair does. Each optional position gets an alternative that skips
     * to the next, which is what makes the order fixed but the membership free.
     */
    private fun argumentRules(index: Int, tool: Tool): String = buildString {
        appendLine("""args$index ::= "{" h$index-0 "}"""")

        tool.params.forEachIndexed { position, param ->
            val emitted = pair(param.name, "v$index-$position")
            val next = position + 1
            val head = "$emitted t$index-$next"
            val tail = """"," $emitted t$index-$next"""
            if (param.required) {
                appendLine("h$index-$position ::= $head")
                appendLine("t$index-$position ::= $tail")
            } else {
                appendLine("h$index-$position ::= $head | h$index-$next")
                appendLine("t$index-$position ::= $tail | t$index-$next")
            }
        }
        // Past the last parameter there is nothing left to emit. An empty alternative is
        // what lets the final optional argument simply stop.
        appendLine("h$index-${tool.params.size} ::= \"\"")
        appendLine("t$index-${tool.params.size} ::= \"\"")
    }

    private fun pair(name: String, valueRule: String): String =
        """"\"$name\":" $valueRule"""

    private fun valueRule(index: Int, position: Int, param: ToolParam): String {
        val rule = "v$index-$position"
        return when (param.type) {
            ParamType.STRING -> "$rule ::= string"

            ParamType.INTEGER -> "$rule ::= integer"

            ParamType.BOOLEAN -> "$rule ::= boolean"

            ParamType.ENUM -> {
                // Enumerated values are baked into the grammar, so an out-of-range
                // value is not something that has to be handled downstream.
                // The appended quote is load-bearing: a raw string cannot end in one,
                // so the four closing quotes give three to the terminator and only one
                // to the literal. Without it each alternative is left unterminated.
                val options = param.values.joinToString(" | ") { """"\"$it\"""" + '"' }
                "$rule ::= $options"
            }
        }
    }

    /**
     * The string rule is taken verbatim from llama.cpp's own grammars/json.gbnf rather
     * than hand-rolled. GBNF character-class escaping has enough corners that a
     * home-made version is a liability, and the parser reports only "failed to parse"
     * with no position — which is exactly how a hand-rolled one cost an evening here.
     *
     * There is deliberately no whitespace rule: the model has no reason to emit spaces,
     * JSON without them is still valid, and each one would be a token spent on nothing.
     */
    private fun commonRules(): String = buildString {
        appendLine(
            """string ::= "\"" ( [^"\\\x7F\x00-\x1F] | """ +
                """"\\" (["\\bfnrt] | "u" [0-9a-fA-F]{4}) )* "\""""",
        )
        appendLine("""integer ::= "-"? ("0" | [1-9] [0-9]{0,9})""")
        appendLine("""boolean ::= "true" | "false"""")
    }
}
