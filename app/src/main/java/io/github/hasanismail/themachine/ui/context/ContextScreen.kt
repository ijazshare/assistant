/*
 * The Machine — an offline voice assistant for Android.
 * Copyright (C) 2026 Hasan Ismail
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
package io.github.hasanismail.themachine.ui.context

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
import io.github.hasanismail.themachine.context.MachineFile
import io.github.hasanismail.themachine.context.MachineFiles
import io.github.hasanismail.themachine.settings.MachineSettings
import io.github.hasanismail.themachine.ui.machine.MachineRule
import io.github.hasanismail.themachine.ui.machine.TrackingBox
import io.github.hasanismail.themachine.ui.machine.rememberSnapProgress
import io.github.hasanismail.themachine.ui.machine.scanlines
import io.github.hasanismail.themachine.ui.theme.MachineColors
import io.github.hasanismail.themachine.ui.theme.MachineDump
import io.github.hasanismail.themachine.ui.theme.MachineLabel
import io.github.hasanismail.themachine.ui.theme.MachineReadout
import kotlinx.coroutines.Dispatchers
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
    val files = remember(context) { MachineFiles(context) }
    val settings = remember(context) { MachineSettings(context) }

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
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            MachineFiles.ALL.forEach { drafts[it.id] = files.read(it) }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MachineColors.Void)
            .scanlines()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("CONTEXT", style = MachineLabel, color = MachineColors.Admin)
            Text("READ BEFORE EVERY COMMAND", style = MachineLabel, color = MachineColors.Ghost)
        }
        MachineRule(Modifier.fillMaxWidth().height(1.dp))

        Text(
            text = "These are ordinary Markdown files on the phone. The assistant reads them " +
                "before deciding what to do, and writes tasks back into them. Nothing here " +
                "leaves the device.",
            style = MachineReadout,
            color = MachineColors.Ghost,
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
                "${MachineSettings.DEFAULT_REMOTE_LLM_URL} or your own llama.cpp server. Only the text of a " +
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

        for (spec in MachineFiles.ALL) {
            FileEditor(
                spec = spec,
                value = drafts[spec.id].orEmpty(),
                // Saved when the field loses focus, not on every keystroke. Writing the
                // whole file per character meant a task the assistant appended while the
                // screen was open was overwritten by a draft that predated it — typing
                // one letter erased the reminder just set.
                onChange = { text -> drafts[spec.id] = text },
                onCommit = {
                    val text = drafts[spec.id].orEmpty()
                    scope.launch(Dispatchers.IO) { files.write(spec, text) }
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
        color = MachineColors.Admin,
        progress = rememberSnapProgress(locked = true),
        cornerLength = 12.dp,
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(label, style = MachineLabel, color = MachineColors.Admin)
            Text(help, style = MachineReadout, color = MachineColors.Dim)
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = LocalTextStyle.current.merge(MachineDump).merge(
                    androidx.compose.ui.text.TextStyle(color = MachineColors.Bone),
                ),
                cursorBrush = SolidColor(MachineColors.Admin),
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
                    .background(MachineColors.PanelActive)
                    .padding(8.dp),
            )
            // ponytail: the blank-state hint is derived from the kind rather than a sixth parameter.
            val blankHint = when (kind) {
                FieldKind.NAME -> "Defaults to \"${MachineSettings.DEFAULT_ADMIN_NAME}\"."
                FieldKind.NUMBER -> "Not set — \"text me\" will say it has nowhere to send."
                FieldKind.SECRET -> "Not set — the remote model is off and everything stays on the phone."
                FieldKind.TEXT -> null
            }
            if (value.isBlank() && blankHint != null) {
                Text(blankHint, style = MachineReadout, color = MachineColors.Ghost)
            }
        }
    }
}

@Composable
private fun FileEditor(
    spec: MachineFile,
    value: String,
    onChange: (String) -> Unit,
    onCommit: () -> Unit,
) {
    TrackingBox(
        modifier = Modifier.fillMaxWidth(),
        color = MachineColors.Irrelevant,
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
                Text(spec.title.uppercase(), style = MachineLabel, color = MachineColors.Irrelevant)
                Text(spec.fileName, style = MachineLabel, color = MachineColors.Ghost)
            }
            Text(spec.purpose, style = MachineReadout, color = MachineColors.Dim)
            BasicTextField(
                value = value,
                onValueChange = onChange,
                textStyle = LocalTextStyle.current.merge(MachineDump).merge(
                    androidx.compose.ui.text.TextStyle(color = MachineColors.Bone),
                ),
                cursorBrush = SolidColor(MachineColors.Irrelevant),
                modifier = Modifier
                    .fillMaxWidth()
                    // Tall enough to write in, capped so one file cannot fill the screen.
                    .heightIn(min = 120.dp, max = 320.dp)
                    // Saved when the field loses focus. Saving per keystroke wrote a
                    // draft that predated anything the assistant had appended while the
                    // screen was open, so typing one letter erased a just-set reminder.
                    .onFocusChanged { if (!it.isFocused) onCommit() }
                    .background(MachineColors.PanelActive)
                    .padding(8.dp),
            )
        }
    }
}
