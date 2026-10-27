/*
 * Assistant — an offline voice assistant for Android.
 * Copyright (C) 2026 Hasan Ismail
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
package io.github.ijazshare.assistant.ui.context

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.getSystemService
import io.github.ijazshare.assistant.context.AssistantFile
import io.github.ijazshare.assistant.context.AssistantFiles
import io.github.ijazshare.assistant.settings.AssistantSettings
import io.github.ijazshare.assistant.ui.assistant.AssistantRule
import io.github.ijazshare.assistant.ui.assistant.TrackingBox
import io.github.ijazshare.assistant.ui.assistant.rememberSnapProgress
import io.github.ijazshare.assistant.ui.assistant.scanlines
import io.github.ijazshare.assistant.ui.theme.AssistantColors
import io.github.ijazshare.assistant.ui.theme.AssistantDump
import io.github.ijazshare.assistant.ui.theme.AssistantLabel
import io.github.ijazshare.assistant.ui.theme.AssistantReadout
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What the assistant knows about you.
 *
 * Everything here is a plain Markdown file in app-private storage, edited in place.
 * Nothing is hidden in a database, and nothing is uploaded — this screen and a text
 * editor see exactly the same bytes.
 */
@Composable
fun ContextScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val files = remember(context) { AssistantFiles(context) }
    val settings = remember(context) { AssistantSettings(context) }

    var adminName by remember { mutableStateOf("") }
    var ownNumber by remember { mutableStateOf("") }
    var remoteUrl by remember { mutableStateOf("") }
    var remoteKey by remember { mutableStateOf("") }
    var remoteModel by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        adminName = settings.adminNameNow()
        ownNumber = settings.ownNumberNow().orEmpty()
        val (url, key, model) = settings.remoteLlmFieldsNow()
        remoteUrl = url
        remoteKey = key
        remoteModel = model
    }

    val drafts = remember { mutableStateMapOf<String, String>() }
    // What each file held when its draft was loaded or last saved.
    val loaded = remember { mutableStateMapOf<String, String>() }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            AssistantFiles.ALL.forEach {
                val text = files.read(it)
                loaded[it.id] = text
                drafts[it.id] = text
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AssistantColors.Void)
            .scanlines()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("CONTEXT", style = AssistantLabel, color = AssistantColors.Admin)
            Text("READ BEFORE EVERY COMMAND", style = AssistantLabel, color = AssistantColors.Ghost)
        }
        AssistantRule(Modifier.fillMaxWidth().height(1.dp))

        Text(
            text = "These are ordinary Markdown files on the phone. The assistant reads them " +
                "before deciding what to do, and writes tasks back into them. Nothing here " +
                "leaves the device.",
            style = AssistantReadout,
            color = AssistantColors.Ghost,
        )

        SettingField(
            label = "ADMIN",
            help = "What the assistant should call you.",
            value = adminName,
            onChange = { name ->
                adminName = name
                scope.launch { settings.setAdminName(name) }
            },
            kind = FieldKind.NAME,
        )
        SettingField(
            label = "YOUR NUMBER",
            help = "Where \"text me\" goes. Nothing else ever resolves to you.",
            value = ownNumber,
            onChange = { number ->
                ownNumber = number
                scope.launch { settings.setOwnNumber(number) }
            },
            kind = FieldKind.NUMBER,
        )
        SettingField(
            label = "REMOTE MODEL · URL",
            help = "Optional. An OpenAI-compatible base URL for general-knowledge answers, e.g. " +
                "${AssistantSettings.DEFAULT_REMOTE_LLM_URL} or your own llama.cpp server. Only the text of a " +
                "question is ever sent, never audio. Leave all three blank to stay fully offline.",
            value = remoteUrl,
            onChange = { url ->
                remoteUrl = url
                scope.launch { settings.setRemoteLlmUrl(url) }
            },
        )
        SettingField(
            label = "REMOTE MODEL · API KEY",
            help = "The bearer key for that endpoint. Stored only on this phone.",
            value = remoteKey,
            onChange = { key ->
                remoteKey = key
                scope.launch { settings.setRemoteLlmKey(key) }
            },
            kind = FieldKind.SECRET,
        )
        SettingField(
            label = "REMOTE MODEL · NAME",
            help = "The model id, e.g. openai/gpt-4o-mini on OpenRouter, or the name your own server exposes.",
            value = remoteModel,
            onChange = { model ->
                remoteModel = model
                scope.launch { settings.setRemoteLlmModel(model) }
            },
        )

        for (spec in AssistantFiles.ALL) {
            FileEditor(
                spec = spec,
                value = drafts[spec.id].orEmpty(),
                // Saved when the field loses focus, and only if the file still holds what the
                // draft started from. Saving regardless meant a task the assistant appended
                // while the screen was open was overwritten by a draft that predated it.
                onChange = { text -> drafts[spec.id] = text },
                onCommit = {
                    val text = drafts[spec.id]
                    val base = loaded[spec.id]
                    // A field also reports losing focus when it first appears, before anything
                    // has loaded. Nothing loaded or nothing typed means nothing to save.
                    if (text != null && base != null && text != base) {
                        // Started at once, and the write not cancellable: focus is also lost as
                        // the screen closes, and a queued save would be cancelled with it.
                        scope.launch(start = CoroutineStart.UNDISPATCHED) {
                            val saved = withContext(Dispatchers.IO + NonCancellable) {
                                files.writeIfUnchanged(spec, base, text)
                            }
                            val current = if (saved) text else withContext(Dispatchers.IO) { files.read(spec) }
                            loaded[spec.id] = current
                            if (!saved) {
                                // What they typed exists nowhere else.
                                context.getSystemService<ClipboardManager>()
                                    ?.setPrimaryClip(ClipData.newPlainText(spec.title, text))
                                drafts[spec.id] = current
                                Toast.makeText(
                                    context,
                                    "${spec.title} changed while you were editing, so your edit was not saved. " +
                                        "It is on the clipboard.",
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        }
                    }
                },
            )
        }
    }
}

/** What a setting field holds, which decides its keyboard, masking and blank-state hint. */
private enum class FieldKind { NAME, NUMBER, TEXT, SECRET }

/**
 * The settings that are not files: the name the assistant addresses you by, the number
 * "text me" goes to, and the optional remote model. Asked for separately because the
 * assistant depends on them, and burying them in a Markdown file would make them look optional.
 */
@Composable
private fun SettingField(
    label: String,
    help: String,
    value: String,
    onChange: (String) -> Unit,
    kind: FieldKind = FieldKind.TEXT,
) {
    TrackingBox(
        modifier = Modifier.fillMaxWidth(),
        color = AssistantColors.Admin,
        progress = rememberSnapProgress(locked = true),
        cornerLength = 12.dp,
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(label, style = AssistantLabel, color = AssistantColors.Admin)
            Text(help, style = AssistantReadout, color = AssistantColors.Dim)
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = LocalTextStyle.current.merge(AssistantDump).merge(
                    androidx.compose.ui.text.TextStyle(color = AssistantColors.Bone),
                ),
                cursorBrush = SolidColor(AssistantColors.Admin),
                visualTransformation =
                if (kind == FieldKind.SECRET) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(
                    keyboardType = when (kind) {
                        FieldKind.NUMBER -> KeyboardType.Phone
                        FieldKind.SECRET -> KeyboardType.Password
                        FieldKind.NAME, FieldKind.TEXT -> KeyboardType.Text
                    },
                    capitalization =
                    if (kind == FieldKind.NAME) KeyboardCapitalization.Words else KeyboardCapitalization.None,
                    imeAction = ImeAction.Done,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AssistantColors.PanelActive)
                    .padding(8.dp),
            )
            // ponytail: the blank-state hint is derived from the kind rather than a sixth parameter.
            val blankHint = when (kind) {
                FieldKind.NAME -> "Defaults to \"${AssistantSettings.DEFAULT_ADMIN_NAME}\"."
                FieldKind.NUMBER -> "Not set — \"text me\" will say it has nowhere to send."
                FieldKind.SECRET -> "Not set — the remote model is off and everything stays on the phone."
                FieldKind.TEXT -> null
            }
            if (value.isBlank() && blankHint != null) {
                Text(blankHint, style = AssistantReadout, color = AssistantColors.Ghost)
            }
        }
    }
}

@Composable
private fun FileEditor(
    spec: AssistantFile,
    value: String,
    onChange: (String) -> Unit,
    onCommit: () -> Unit,
) {
    TrackingBox(
        modifier = Modifier.fillMaxWidth(),
        color = AssistantColors.Irrelevant,
        progress = rememberSnapProgress(locked = true),
        cornerLength = 12.dp,
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(spec.title.uppercase(), style = AssistantLabel, color = AssistantColors.Irrelevant)
                Text(spec.fileName, style = AssistantLabel, color = AssistantColors.Ghost)
            }
            Text(spec.purpose, style = AssistantReadout, color = AssistantColors.Dim)
            BasicTextField(
                value = value,
                onValueChange = onChange,
                textStyle = LocalTextStyle.current.merge(AssistantDump).merge(
                    androidx.compose.ui.text.TextStyle(color = AssistantColors.Bone),
                ),
                cursorBrush = SolidColor(AssistantColors.Irrelevant),
                modifier = Modifier
                    .fillMaxWidth()
                    // Tall enough to write in, capped so one file cannot fill the screen.
                    .heightIn(min = 120.dp, max = 320.dp)
                    // Saved when the field loses focus. Saving per keystroke wrote a
                    // draft that predated anything the assistant had appended while the
                    // screen was open, so typing one letter erased a just-set reminder.
                    .onFocusChanged { if (!it.isFocused) onCommit() }
                    .background(AssistantColors.PanelActive)
                    .padding(8.dp),
            )
        }
    }
}
