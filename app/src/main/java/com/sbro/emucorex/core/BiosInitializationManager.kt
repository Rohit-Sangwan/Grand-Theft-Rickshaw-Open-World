package com.sbro.emucorex.core

import android.content.Context
import android.net.Uri
import android.util.Log
import com.sbro.emucorex.data.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Manages BIOS verification, automatic directory preparation, and registration
 * for Grand Theft Rickshaw in full compliance with store and copyright policies.
 */
object BiosInitializationManager {
    private const val TAG = "BiosInitManager"

    sealed class BiosState {
        object Idle : BiosState()
        object Preparing : BiosState()
        data class Ready(val path: String) : BiosState()
        data class Missing(val message: String) : BiosState()
        data class Error(val message: String) : BiosState()
    }

    private val _biosState = MutableStateFlow<BiosState>(BiosState.Idle)
    val biosState: StateFlow<BiosState> = _biosState.asStateFlow()

    suspend fun checkAndPrepareBios(context: Context, preferences: AppPreferences): BiosState =
        withContext(Dispatchers.IO) {
            try {
                _biosState.value = BiosState.Preparing

                // 1. Check existing configured BIOS path in preferences
                val currentPath = preferences.biosPath.first()
                if (!currentPath.isNullOrBlank() && BiosValidator.hasUsableBiosFiles(context, currentPath)) {
                    val ready = BiosState.Ready(currentPath)
                    _biosState.value = ready
                    return@withContext ready
                }

                // 2. Prepare internal BIOS directory
                val internalBiosDir = File(context.filesDir, "bios")
                val externalBiosDir = context.getExternalFilesDir("bios")
                val candidateDirs = listOfNotNull(internalBiosDir, externalBiosDir)

                for (dir in candidateDirs) {
                    if (!dir.exists()) dir.mkdirs()
                    if (BiosValidator.hasUsableBiosFiles(context, dir.absolutePath)) {
                        preferences.setBiosPath(dir.absolutePath)
                        val ready = BiosState.Ready(dir.absolutePath)
                        _biosState.value = ready
                        return@withContext ready
                    }
                }

                // 3. Check if an authorized BIOS was bundled in assets
                runCatching {
                    val assetManager = context.assets
                    val list = assetManager.list("bios") ?: emptyArray()
                    val biosCandidates = list.filter { name ->
                        name.endsWith(".bin", ignoreCase = true) || name.endsWith(".rom", ignoreCase = true)
                    }
                    if (biosCandidates.isNotEmpty()) {
                        val targetDirs = listOfNotNull(internalBiosDir, externalBiosDir)
                        for (targetDir in targetDirs) {
                            if (!targetDir.exists()) targetDir.mkdirs()
                            for (name in biosCandidates) {
                                val outFile = File(targetDir, name)
                                if (!outFile.exists() || outFile.length() == 0L) {
                                    assetManager.open("bios/$name").use { input ->
                                        FileOutputStream(outFile).use { output ->
                                            input.copyTo(output)
                                        }
                                    }
                                }
                            }
                        }
                        val chosenDir = internalBiosDir.takeIf { BiosValidator.hasUsableBiosFiles(context, it.absolutePath) }
                            ?: externalBiosDir?.takeIf { BiosValidator.hasUsableBiosFiles(context, it.absolutePath) }

                        if (chosenDir != null) {
                            preferences.setBiosPath(chosenDir.absolutePath)
                            val ready = BiosState.Ready(chosenDir.absolutePath)
                            _biosState.value = ready
                            return@withContext ready
                        }
                    }
                }

                val missing = BiosState.Missing("PS2 BIOS is required to boot the emulation engine.")
                _biosState.value = missing
                missing
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize BIOS", e)
                val err = BiosState.Error("BIOS initialization error: ${e.message}")
                _biosState.value = err
                err
            }
        }

    suspend fun importBiosFromUri(context: Context, uri: Uri, preferences: AppPreferences): Boolean =
        withContext(Dispatchers.IO) {
            try {
                _biosState.value = BiosState.Preparing
                val targetDir = context.getExternalFilesDir("bios") ?: File(context.filesDir, "bios")
                if (!targetDir.exists()) targetDir.mkdirs()

                val fileName = uri.lastPathSegment?.substringAfterLast('/') ?: "bios_import"
                val isArchive = BiosArchiveExtractor.isArchiveName(fileName)

                if (isArchive) {
                    val tempArchive = File(context.cacheDir, fileName)
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(tempArchive).use { output ->
                            input.copyTo(output)
                        }
                    }
                    val extracted = BiosArchiveExtractor.extractBiosFiles(tempArchive, targetDir)
                    tempArchive.delete()
                    if (extracted.isNotEmpty()) {
                        preferences.setBiosPath(targetDir.absolutePath)
                        _biosState.value = BiosState.Ready(targetDir.absolutePath)
                        return@withContext true
                    }
                }

                // If not an archive or extraction yielded no files, try direct copy
                val outFile = File(targetDir, fileName)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(outFile).use { output ->
                        input.copyTo(output)
                    }
                }

                if (BiosValidator.hasUsableBiosFiles(context, targetDir.absolutePath)) {
                    preferences.setBiosPath(targetDir.absolutePath)
                    _biosState.value = BiosState.Ready(targetDir.absolutePath)
                    true
                } else {
                    _biosState.value = BiosState.Error("Selected file is not a valid PS2 BIOS.")
                    false
                }
            } catch (e: Exception) {
                Log.e(TAG, "BIOS import failed", e)
                _biosState.value = BiosState.Error("Import failed: ${e.message}")
                false
            }
        }
}
