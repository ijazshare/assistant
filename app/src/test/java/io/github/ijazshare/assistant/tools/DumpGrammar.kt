package io.github.ijazshare.assistant.tools

import io.github.ijazshare.assistant.llm.FunctionGemmaDialect
import org.junit.Test
import java.io.File

/** Temporary: writes generated grammars out so they can be inspected by eye. */
class DumpGrammar {
    @Test
    fun dump() {
        File("build/generated-grammar.gbnf").apply {
            parentFile?.mkdirs()
            writeText(AssistantTools.grammar)
        }
        File("build/fg-set-alarm.gbnf").writeText(
            FunctionGemmaDialect.grammar(listOf(AssistantTools.byName(AssistantTools.SET_ALARM)!!)),
        )
        File("build/fg-prompt.txt").writeText(
            FunctionGemmaDialect.buildPrompt(
                "set an alarm for 7am",
                AssistantTools.all,
                "Hasan",
                "",
            ),
        )
    }
}
