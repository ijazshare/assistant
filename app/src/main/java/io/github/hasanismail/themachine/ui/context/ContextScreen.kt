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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
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
    LaunchedEffect(Unit) {
        adminName = settings.adminNameNow()
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

        NameField(
            value = adminName,
            onChange = { name ->
                adminName = name
                scope.launch { settings.setAdminName(name) }
            },
        )

        for (spec in MachineFiles.ALL) {
            FileEditor(
                spec = spec,
                value = drafts[spec.id].orEmpty(),
                onChange = { text ->
                    drafts[spec.id] = text
                    scope.launch(Dispatchers.IO) { files.write(spec, text) }
                },
            )
        }
    }
}

/**
 * The one setting that is not a file. It is asked for separately because the assistant
 * uses it as a form of address in every prompt, and burying it in a Markdown file would
 * make something the model depends on look optional.
 */
@Composable
private fun NameField(value: String, onChange: (String) -> Unit) {
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
            Text("ADMIN", style = MachineLabel, color = MachineColors.Admin)
            Text(
                "What the assistant should call you.",
                style = MachineReadout,
                color = MachineColors.Dim,
            )
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = LocalTextStyle.current.merge(MachineDump).merge(
                    androidx.compose.ui.text.TextStyle(color = MachineColors.Bone),
                ),
                cursorBrush = SolidColor(MachineColors.Admin),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words,
                    imeAction = ImeAction.Done,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MachineColors.PanelActive)
                    .padding(8.dp),
            )
            if (value.isBlank()) {
                Text(
                    "Defaults to \"${MachineSettings.DEFAULT_ADMIN_NAME}\".",
                    style = MachineReadout,
                    color = MachineColors.Ghost,
                )
            }
        }
    }
}

@Composable
private fun FileEditor(spec: MachineFile, value: String, onChange: (String) -> Unit) {
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
                    .background(MachineColors.PanelActive)
                    .padding(8.dp),
            )
        }
    }
}
