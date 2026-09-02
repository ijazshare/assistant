/*
 * The Machine — an offline voice assistant for Android.
 * Copyright (C) 2026 Hasan Ismail
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
package io.github.hasanismail.themachine.models

import android.content.Context
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Which part of the pipeline an asset belongs to. */
enum class ModelRole { STT, LLM, TTS }

/** Assets that arrive compressed and have to be unpacked after verification. */
enum class ArchiveFormat { NONE, TAR_BZ2 }

/**
 * One downloadable asset.
 *
 * [sha256] is the contract: the download is only accepted if the bytes hash to this.
 * That makes a wrong value here a loud failure rather than a silently corrupt model,
 * which is why it is safe to ship checksums read from a mirror.
 */
@Serializable
data class ModelAsset(
    val id: String,
    val role: ModelRole,
    val displayName: String,
    val detail: String,
    val fileName: String,
    val url: String,
    val sha256: String,
    val byteSize: Long,
    val minRamMb: Int,
    val isDefault: Boolean = false,
    val license: String = "",
    val source: String = "",
    val archive: ArchiveFormat = ArchiveFormat.NONE,
    @SerialName("extractedByteSize") val extractedByteSize: Long = 0,
) {
    /**
     * Worst-case free space needed to install this: the download itself, plus the
     * unpacked result while the archive is still on disk. Getting this wrong is how a
     * download dies at 95% on a full phone.
     */
    val installFootprintBytes: Long
        get() = byteSize + if (archive == ArchiveFormat.NONE) 0 else extractedByteSize
}

@Serializable
data class ModelRegistryFile(
    val registryVersion: Int,
    val verifiedOn: String = "",
    val note: String = "",
    val assets: List<ModelAsset>,
)

/**
 * Reads the asset catalogue shipped in assets/models. Versioned so a future build can
 * ship a newer registry and migrate, rather than silently reinterpreting the old one.
 */
class ModelRegistry(registryJson: String) {

    /** Reads the catalogue shipped in assets/models. */
    constructor(context: Context) : this(
        context.assets.open(REGISTRY_PATH).bufferedReader().use { it.readText() },
    )

    private val parsed: ModelRegistryFile =
        Json { ignoreUnknownKeys = true }
            .decodeFromString(ModelRegistryFile.serializer(), registryJson)

    val version: Int get() = parsed.registryVersion

    val all: List<ModelAsset> get() = parsed.assets

    fun byRole(role: ModelRole): List<ModelAsset> = all.filter { it.role == role }

    fun byId(id: String): ModelAsset? = all.firstOrNull { it.id == id }

    /** What a fresh install downloads if the user just says yes. */
    fun defaults(): List<ModelAsset> = all.filter { it.isDefault }

    /**
     * Whether every part of the pipeline has *something* it can run with.
     *
     * Deliberately not "are the defaults installed": someone who chooses base.en over
     * tiny.en has a perfectly working speech recogniser, and reporting that as a missing
     * model is simply wrong. The pipeline needs one asset per role, not one specific one.
     */
    fun rolesSatisfied(isReady: (ModelAsset) -> Boolean): Pair<Int, Int> {
        val roles = ModelRole.entries
        return roles.count { role -> byRole(role).any(isReady) } to roles.size
    }

    companion object {
        const val REGISTRY_PATH = "models/registry-v1.json"
    }
}
