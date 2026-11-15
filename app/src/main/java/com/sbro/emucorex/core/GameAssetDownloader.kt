package com.sbro.emucorex.core

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.sbro.emucorex.BuildConfig
import com.sbro.emucorex.data.AppPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * In-game asset downloader providing a PUBG/Call of Duty Mobile-style
 * initial resource download screen:
 * - Real-time progress bar (0-100%)
 * - Download speed (MB/s) and downloaded size (MB/GB)
 * - Resume support using HTTP Range headers
 * - Auto-verification and instant transition to "PLAY GAME"
 */
object GameAssetDownloader {
    private const val TAG = "GameAssetDownloader"
    private const val BUFFER_SIZE = 128 * 1024 // 128 KB

    sealed class DownloadState {
        object Idle : DownloadState()
        data class Downloading(
            val progress: Float,
            val downloadedBytes: Long,
            val totalBytes: Long,
            val speedMbPerSec: Float,
            val statusText: String
        ) : DownloadState()
        object Verifying : DownloadState()
        data class Completed(val file: File) : DownloadState()
        data class Error(val message: String, val canResume: Boolean) : DownloadState()
    }

    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

    @Volatile
    private var isCancelled = false

    fun cancelDownload() {
        isCancelled = true
    }

    suspend fun startDownload(
        context: Context,
        urlString: String,
        targetFileName: String = "Grand Theft Rickshaw.iso",
        preferences: AppPreferences
    ): Boolean = withContext(Dispatchers.IO) {
        isCancelled = false
        val targetDir = context.getExternalFilesDir("games") ?: File(context.filesDir, "games")
        if (!targetDir.exists()) targetDir.mkdirs()

        val targetFile = File(targetDir, targetFileName)
        val tempFile = File(targetDir, "$targetFileName.part")

        try {
            var existingLength = if (tempFile.exists()) tempFile.length() else 0L

            val url = URL(urlString)
            var connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "HEAD"
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.connect()

            val responseCode = connection.responseCode
            val contentLength = connection.contentLengthLong
            val acceptRanges = connection.getHeaderField("Accept-Ranges")?.contains("bytes", ignoreCase = true) == true
            connection.disconnect()

            val totalBytes = if (contentLength > 0) contentLength else -1L

            // If existing file is already complete
            if (totalBytes > 0 && existingLength >= totalBytes) {
                tempFile.renameTo(targetFile)
                _downloadState.value = DownloadState.Verifying
                if (GameAssetManager.verifyIsoSerial(targetFile)) {
                    preferences.setGamePaths(listOf(targetFile.absolutePath))
                    _downloadState.value = DownloadState.Completed(targetFile)
                    GameAssetManager.checkAndPrepareAssets(context, preferences)
                    return@withContext true
                }
            }

            // Reopen connection with Range if supported
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 20_000
            connection.readTimeout = 20_000

            if (existingLength > 0 && acceptRanges) {
                connection.setRequestProperty("Range", "bytes=$existingLength-")
            } else {
                existingLength = 0L
                if (tempFile.exists()) tempFile.delete()
            }

            connection.connect()
            val getResponseCode = connection.responseCode
            if (getResponseCode != HttpURLConnection.HTTP_OK && getResponseCode != HttpURLConnection.HTTP_PARTIAL) {
                throw Exception("Server returned HTTP $getResponseCode: ${connection.responseMessage}")
            }

            val isPartial = getResponseCode == HttpURLConnection.HTTP_PARTIAL
            val inputStream = connection.inputStream
            val outputStream = if (existingLength > 0 && isPartial) {
                RandomAccessFile(tempFile, "rw").apply { seek(existingLength) }
            } else {
                existingLength = 0L
                FileOutputStream(tempFile)
            }

            val buffer = ByteArray(BUFFER_SIZE)
            var bytesRead: Int
            var totalBytesRead = existingLength
            var lastUpdateTime = SystemClock.elapsedRealtime()
            var bytesSinceLastUpdate = 0L

            try {
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    if (isCancelled) {
                        throw CancellationException("Download paused by user")
                    }

                    if (outputStream is RandomAccessFile) {
                        outputStream.write(buffer, 0, bytesRead)
                    } else if (outputStream is FileOutputStream) {
                        outputStream.write(buffer, 0, bytesRead)
                    }

                    totalBytesRead += bytesRead
                    bytesSinceLastUpdate += bytesRead

                    val now = SystemClock.elapsedRealtime()
                    val timeDelta = now - lastUpdateTime
                    if (timeDelta >= 500) { // Update UI every 500ms
                        val speedMbPerSec = (bytesSinceLastUpdate.toFloat() / (1024 * 1024)) / (timeDelta.toFloat() / 1000f)
                        val progress = if (totalBytes > 0) (totalBytesRead.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f

                        val downloadedMb = totalBytesRead.toFloat() / (1024 * 1024)
                        val totalMb = totalBytes.toFloat() / (1024 * 1024)
                        val downloadedGb = downloadedMb / 1024f
                        val totalGb = totalMb / 1024f
                        val statusText = if (totalBytes > 0) {
                            if (totalGb >= 1.0f) {
                                String.format(Locale.US, "%.2f GB / %.2f GB (%.1f MB/s)", downloadedGb, totalGb, speedMbPerSec)
                            } else {
                                String.format(Locale.US, "%.1f MB / %.1f MB (%.1f MB/s)", downloadedMb, totalMb, speedMbPerSec)
                            }
                        } else {
                            String.format(Locale.US, "%.1f MB (%.1f MB/s)", downloadedMb, speedMbPerSec)
                        }

                        _downloadState.value = DownloadState.Downloading(
                            progress = progress,
                            downloadedBytes = totalBytesRead,
                            totalBytes = totalBytes,
                            speedMbPerSec = speedMbPerSec,
                            statusText = statusText
                        )

                        lastUpdateTime = now
                        bytesSinceLastUpdate = 0L
                    }
                }
            } finally {
                runCatching { inputStream.close() }
                runCatching {
                    if (outputStream is RandomAccessFile) outputStream.close()
                    else if (outputStream is FileOutputStream) outputStream.close()
                }
                connection.disconnect()
            }

            _downloadState.value = DownloadState.Verifying

            // Rename part to final target
            if (tempFile.exists()) {
                if (targetFile.exists()) targetFile.delete()
                tempFile.renameTo(targetFile)
            }

            if (GameAssetManager.verifyIsoSerial(targetFile)) {
                preferences.setGamePaths(listOf(targetFile.absolutePath))
                _downloadState.value = DownloadState.Completed(targetFile)
                GameAssetManager.checkAndPrepareAssets(context, preferences)
                true
            } else {
                _downloadState.value = DownloadState.Error("Game file verification failed", canResume = false)
                false
            }
        } catch (e: CancellationException) {
            _downloadState.value = DownloadState.Error("Download paused", canResume = true)
            false
        } catch (e: Exception) {
            Log.e(TAG, "Download failed", e)
            _downloadState.value = DownloadState.Error("Download error: ${e.message}", canResume = true)
            false
        }
    }
}
