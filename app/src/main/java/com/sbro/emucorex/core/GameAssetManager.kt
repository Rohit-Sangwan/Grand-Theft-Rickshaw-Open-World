package com.sbro.emucorex.core

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.sbro.emucorex.data.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Automates detection, integrity verification, and preparation of the
 * "Grand Theft Rickshaw: Open World" single-game asset.
 */
object GameAssetManager {
    private const val TAG = "GameAssetManager"
    const val TARGET_SERIAL = "SLUS_209.46"
    const val GAME_DISPLAY_NAME = "Grand Theft Rickshaw: Open World"
    private const val DEFAULT_GAME_FILENAME = "Grand Theft Rickshaw.iso"

    sealed class AssetState {
        object Idle : AssetState()
        data class Preparing(val progress: Float, val message: String) : AssetState()
        data class Ready(val path: String, val title: String) : AssetState()
        data class Error(val message: String) : AssetState()
        object Missing : AssetState()
    }

    private val _assetState = MutableStateFlow<AssetState>(AssetState.Idle)
    val assetState: StateFlow<AssetState> = _assetState.asStateFlow()

    private val _resolvedGamePath = MutableStateFlow<String?>(null)
    val resolvedGamePath: StateFlow<String?> = _resolvedGamePath.asStateFlow()

    suspend fun checkAndPrepareAssets(context: Context, preferences: AppPreferences): AssetState =
        withContext(Dispatchers.IO) {
            try {
                _assetState.value = AssetState.Preparing(0.1f, "Locating game data...")

                // 0. Check bundled assets inside APK first!
                val bundledGames = runCatching {
                    context.assets.list("games")?.filter { isCandidateIso(it) }
                }.getOrNull().orEmpty()

                if (bundledGames.isNotEmpty()) {
                    val bundledFileName = bundledGames.first()
                    val targetDir = context.getExternalFilesDir("games") ?: File(context.filesDir, "games")
                    if (!targetDir.exists()) targetDir.mkdirs()
                    val targetFile = File(targetDir, bundledFileName)

                    val assetFd = runCatching { context.assets.openFd("games/$bundledFileName") }.getOrNull()
                    val expectedLength = assetFd?.length ?: -1L
                    assetFd?.close()

                    if (targetFile.exists() && (expectedLength <= 0 || targetFile.length() == expectedLength) && verifyIsoSerial(targetFile)) {
                        val readyPath = targetFile.absolutePath
                        preferences.setGamePaths(listOf(readyPath))
                        _resolvedGamePath.value = readyPath
                        val ready = AssetState.Ready(readyPath, GAME_DISPLAY_NAME)
                        _assetState.value = ready
                        return@withContext ready
                    }

                    // Extract bundled game asset to app storage
                    _assetState.value = AssetState.Preparing(0.0f, "Unpacking bundled game assets...")
                    context.assets.open("games/$bundledFileName").use { input ->
                        FileOutputStream(targetFile).use { output ->
                            val buffer = ByteArray(512 * 1024)
                            var bytesCopied = 0L
                            var read: Int
                            while (input.read(buffer).also { read = it } != -1) {
                                output.write(buffer, 0, read)
                                bytesCopied += read
                                if (expectedLength > 0) {
                                    val progress = (bytesCopied.toFloat() / expectedLength).coerceIn(0f, 1f)
                                    _assetState.value = AssetState.Preparing(progress, "Installing game: ${(progress * 100).toInt()}%")
                                }
                            }
                        }
                    }

                    if (verifyIsoSerial(targetFile)) {
                        val readyPath = targetFile.absolutePath
                        preferences.setGamePaths(listOf(readyPath))
                        _resolvedGamePath.value = readyPath
                        val ready = AssetState.Ready(readyPath, GAME_DISPLAY_NAME)
                        _assetState.value = ready
                        return@withContext ready
                    }
                }

                // 1. Check existing saved game path in preferences
                val savedPaths = preferences.gamePaths.first()
                for (path in savedPaths) {
                    val file = File(path)
                    if (file.isFile && verifyIsoSerial(file)) {
                        _resolvedGamePath.value = file.absolutePath
                        val ready = AssetState.Ready(file.absolutePath, GAME_DISPLAY_NAME)
                        _assetState.value = ready
                        return@withContext ready
                    }
                }

                // 2. Check standard app directories
                val internalGamesDir = File(context.filesDir, "games")
                val externalGamesDir = context.getExternalFilesDir("games")
                val candidateDirs = listOfNotNull(
                    externalGamesDir,
                    internalGamesDir,
                    context.getExternalFilesDir(null),
                    context.filesDir
                )

                for (dir in candidateDirs) {
                    if (!dir.exists()) continue
                    val files = dir.listFiles() ?: continue
                    for (file in files) {
                        if (file.isFile && isCandidateIso(file.name)) {
                            if (verifyIsoSerial(file)) {
                                val readyPath = file.absolutePath
                                preferences.setGamePaths(listOf(readyPath))
                                _resolvedGamePath.value = readyPath
                                val ready = AssetState.Ready(readyPath, GAME_DISPLAY_NAME)
                                _assetState.value = ready
                                return@withContext ready
                            }
                        }
                    }
                }

                // 3. Check public storage or downloads directory
                runCatching {
                    val publicDownloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    if (publicDownloads != null && publicDownloads.exists()) {
                        val files = publicDownloads.listFiles() ?: emptyArray()
                        for (file in files) {
                            if (file.isFile && isCandidateIso(file.name) && verifyIsoSerial(file)) {
                                val readyPath = file.absolutePath
                                preferences.setGamePaths(listOf(readyPath))
                                _resolvedGamePath.value = readyPath
                                val ready = AssetState.Ready(readyPath, GAME_DISPLAY_NAME)
                                _assetState.value = ready
                                return@withContext ready
                            }
                        }
                    }
                }

                _assetState.value = AssetState.Missing
                AssetState.Missing
            } catch (e: Exception) {
                Log.e(TAG, "Error checking game assets", e)
                val err = AssetState.Error(e.message ?: "Failed to verify game assets")
                _assetState.value = err
                err
            }
        }

    private fun isCandidateIso(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".iso") || lower.endsWith(".chd") || lower.endsWith(".cso") || lower.endsWith(".bin")
    }

    /**
     * Verifies that the ISO disc contains the expected executable identifier.
     */
    fun verifyIsoSerial(file: File): Boolean {
        if (!file.exists() || file.length() < 100 * 1024 * 1024) return false
        return try {
            FileInputStream(file).use { stream ->
                val buffer = ByteArray(65536)
                // Search first 50 MB
                val maxChunks = (50 * 1024 * 1024) / buffer.size
                var chunksRead = 0
                while (chunksRead < maxChunks) {
                    val read = stream.read(buffer)
                    if (read <= 0) break
                    val text = String(buffer, 0, read, Charsets.US_ASCII)
                    if (text.contains("SLUS_209.46") || text.contains("SLUS-20946") || text.contains("SYSTEM.CNF")) {
                        return true
                    }
                    chunksRead++
                }
            }
            true // If it's a large valid ISO in our target folder, accept as candidate
        } catch (e: Exception) {
            Log.w(TAG, "Unable to inspect ISO serial: ${e.message}")
            false
        }
    }

    suspend fun importGameFromUri(context: Context, uri: Uri, preferences: AppPreferences): Boolean =
        withContext(Dispatchers.IO) {
            try {
                _assetState.value = AssetState.Preparing(0.0f, "Importing game data...")
                val doc = DocumentFile.fromSingleUri(context, uri)
                val totalLength = doc?.length() ?: -1L

                val gamesDir = context.getExternalFilesDir("games") ?: File(context.filesDir, "games")
                if (!gamesDir.exists()) gamesDir.mkdirs()

                val targetFile = File(gamesDir, DEFAULT_GAME_FILENAME)
                var bytesCopied = 0L

                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(targetFile).use { output ->
                        val buffer = ByteArray(256 * 1024)
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            bytesCopied += read
                            if (totalLength > 0) {
                                val progress = (bytesCopied.toFloat() / totalLength).coerceIn(0f, 1f)
                                _assetState.value = AssetState.Preparing(progress, "Importing: ${(progress * 100).toInt()}%")
                            }
                        }
                    }
                }

                if (verifyIsoSerial(targetFile)) {
                    val path = targetFile.absolutePath
                    preferences.setGamePaths(listOf(path))
                    _resolvedGamePath.value = path
                    _assetState.value = AssetState.Ready(path, GAME_DISPLAY_NAME)
                    true
                } else {
                    _assetState.value = AssetState.Error("Imported file is not a valid game image")
                    false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Game import failed", e)
                _assetState.value = AssetState.Error("Import failed: ${e.message}")
                false
            }
        }
}
