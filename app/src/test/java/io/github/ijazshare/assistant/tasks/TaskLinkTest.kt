/*
 * Assistant — an offline voice assistant for Android.
 * Copyright (C) 2026 Hasan Ismail
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
package io.github.ijazshare.assistant.tasks

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** The vendored contract (spec 13.4): the routing phrases, every response parses, and the copy is not stale. */
class TaskLinkTest {
    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResource("/tasks-contract/v1/$name")) { name }.readText()

    @Test
    fun `the routing phrases agree with the shared fixture`() {
        val lines = fixture("routing.jsonl").lines().filter { it.isNotBlank() && !it.startsWith("#") }
        assertThat(lines).isNotEmpty()
        lines.forEach {
            val o: JsonObject = Json.parseToJsonElement(it).jsonObject
            val said = o["utterance"]!!.jsonPrimitive.content
            assertWithMessage(said).that(TaskRouting.claims(said)).isEqualTo(o["claim"]!!.jsonPrimitive.boolean)
        }
    }

    @Test
    fun `undo phrases are the four and not cancel that`() {
        listOf("undo", "okay undo that", "Scratch that.", "take that back please").forEach {
            assertWithMessage(it).that(TaskRouting.isUndo(it)).isTrue()
        }
        listOf("cancel that", "undone the dishes", "remind me to undo the bolt").forEach {
            assertWithMessage(it).that(TaskRouting.isUndo(it)).isFalse()
        }
    }

    @Test
    fun `fillers are stripped repeatedly and the clock words are never claimed`() {
        assertThat(TaskRouting.claims("okay, so please remind me to call Amira")).isTrue()
        assertThat(TaskRouting.claims("remind me when the timer ends")).isFalse()
        assertThat(TaskRouting.claims("When I get home, remind me to buy milk")).isTrue()
        assertThat(TaskRouting.claims("when I leave work remind me to call Sam")).isTrue()
        assertThat(TaskRouting.claims("when I get home turn on the lights")).isFalse()
        assertThat(TaskRouting.claims("brain dump call the garage")).isTrue()
        assertThat(TaskRouting.claims("tell me a long story about a brain dump")).isFalse()
    }

    @Test
    fun `every response fixture parses and says what it means`() {
        val names = File(javaClass.getResource("/tasks-contract/v1")!!.toURI()).list()!!.filter {
            it.startsWith("response-")
        }
        assertThat(names).isNotEmpty()
        names.forEach { name ->
            val r = TaskContract.decode(fixture(name))
            assertWithMessage(name).that(r).isNotNull()
            assertThat(r!!.status).isIn(listOf("done", "needs_input", "declined", "not_mine", "error"))
            if (r.status == "needs_input") assertThat(r.conversation!!.id).isNotEmpty()
            if (r.status == "error") assertThat(r.error!!.code).isNotEmpty()
        }
        val question = TaskContract.decode(fixture("response-08-needs-input-choice.json"))!!
        assertThat(
            question.conversation!!.options.map {
                it.label
            },
        ).containsExactly("Create it", "Without a list").inOrder()
        assertThat(TaskContract.decode(fixture("response-23-undo.json"))!!.speech).startsWith("Undone.")
        assertThat(TaskContract.decode(fixture("response-01-created.json"))!!.undo!!.token).isEqualTo("u_41fe")
        assertThat(TaskContract.decode(fixture("response-26-migrate.json"))!!.doneAll).isTrue()
        assertThat(TaskContract.decode(fixture("response-29-status.json"))!!.health).isNotNull()
    }

    @Test
    fun `a request carries what the contract says and nothing of the transcript but itself`() {
        val json = TaskContract.encode(
            TaskRequest("utterance", "remind me to call Amira at 5", requestId = "r-1", speechEndMillis = 5),
            nowMillis = 10,
            zone = "Europe/London",
            version = "1",
        )
        val o = Json.parseToJsonElement(json).jsonObject
        assertThat(o["v"]!!.jsonPrimitive.content).isEqualTo("1")
        assertThat(o["utterance"]!!.jsonPrimitive.content).isEqualTo("remind me to call Amira at 5")
        assertThat(o["context"]!!.jsonObject["speech_end_epoch_ms"]!!.jsonPrimitive.content).isEqualTo("5")
        assertThat(o["context"]!!.jsonObject["routed_by"]!!.jsonPrimitive.content).isEqualTo("keyword")
    }

    @Test
    fun `a long list goes in chunks of at most 200 lines`() {
        val chunks = chunkLines(List(450) { "- [ ] task $it" })
        assertThat(chunks.map { it.size }).containsExactly(200, 200, 50).inOrder()
        assertThat(chunkLines(emptyList())).hasSize(1)
    }

    @Test
    fun `the vendored contract matches the Tasks repo when it is checked out`() {
        val manifest = File("/home/ijaz/assistant-dev/tasks-app/contract/v1/MANIFEST.sha256")
        assumeTrue(manifest.isFile)
        val theirs = manifest.readLines().filter { it.isNotBlank() }.toSet()
        val ours = File(javaClass.getResource("/tasks-contract/v1/MANIFEST.sha256")!!.toURI()).readLines()
            .filter { it.isNotBlank() }.toSet()
        assertThat(ours).isEqualTo(theirs)
        // And the files themselves are what the manifest says they are.
        val dir = File(javaClass.getResource("/tasks-contract/v1")!!.toURI())
        ours.forEach {
            val (hash, name) = it.split(Regex("\\s+"), limit = 2)
            val bytes = File(dir, name.trim().removePrefix("*")).readBytes()
            val sum = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { b -> "%02x".format(b) }
            assertWithMessage(name).that(sum).isEqualTo(hash)
        }
    }
}
