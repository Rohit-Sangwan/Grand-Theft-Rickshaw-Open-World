package com.sbro.emucorex.core

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.sbro.emucorex.BuildConfig
import com.sbro.emucorex.MainActivity
import com.sbro.emucorex.R
import com.sbro.emucorex.data.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Foreground Service enabling smooth background downloading of the 4.2 GB open world
 * assets for Grand Theft Rickshaw without stopping if the user minimizes or switches apps.
 */
class GameDownloadService : Service() {

    companion object {
        const val CHANNEL_ID = "game_asset_download_channel"
        const val NOTIFICATION_ID = 20946

        const val ACTION_START = "com.sbro.emucorex.action.START_DOWNLOAD"
        const val ACTION_PAUSE = "com.sbro.emucorex.action.PAUSE_DOWNLOAD"

        fun start(context: Context, url: String = BuildConfig.DEFAULT_GAME_DOWNLOAD_URL) {
            val intent = Intent(context, GameDownloadService::class.java).apply {
                action = ACTION_START
                putExtra("url", url)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun pause(context: Context) {
            val intent = Intent(context, GameDownloadService::class.java).apply {
                action = ACTION_PAUSE
            }
            context.startService(intent)
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private var downloadJob: Job? = null
    private var observerJob: Job? = null
    private lateinit var notificationManager: NotificationManager

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> {
                GameAssetDownloader.cancelDownload()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START, null -> {
                val url = intent?.getStringExtra("url") ?: BuildConfig.DEFAULT_GAME_DOWNLOAD_URL
                startForegroundDownload(url)
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundDownload(url: String) {
        val initialNotification = buildNotification(
            progress = 0,
            statusText = "Initializing high-speed asset download...",
            isIndeterminate = true
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                initialNotification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, initialNotification)
        }

        // Observe download state to update notification
        observerJob?.cancel()
        observerJob = serviceScope.launch {
            GameAssetDownloader.downloadState.collectLatest { state ->
                when (state) {
                    is GameAssetDownloader.DownloadState.Downloading -> {
                        val percent = (state.progress * 100).toInt()
                        val updated = buildNotification(
                            progress = percent,
                            statusText = state.statusText,
                            isIndeterminate = false
                        )
                        notificationManager.notify(NOTIFICATION_ID, updated)
                    }
                    is GameAssetDownloader.DownloadState.Verifying -> {
                        val updated = buildNotification(
                            progress = 100,
                            statusText = "Verifying game integrity...",
                            isIndeterminate = true
                        )
                        notificationManager.notify(NOTIFICATION_ID, updated)
                    }
                    is GameAssetDownloader.DownloadState.Completed -> {
                        val done = buildCompletionNotification("Download complete! Tap to play Grand Theft Rickshaw.")
                        notificationManager.notify(NOTIFICATION_ID, done)
                        stopForeground(STOP_FOREGROUND_DETACH)
                        stopSelf()
                    }
                    is GameAssetDownloader.DownloadState.Error -> {
                        val err = buildCompletionNotification("Download paused: ${state.message}")
                        notificationManager.notify(NOTIFICATION_ID, err)
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                    GameAssetDownloader.DownloadState.Idle -> Unit
                }
            }
        }

        // Start the actual download worker if not already running
        if (GameAssetDownloader.downloadState.value !is GameAssetDownloader.DownloadState.Downloading) {
            downloadJob?.cancel()
            downloadJob = serviceScope.launch {
                val prefs = AppPreferences(applicationContext)
                GameAssetDownloader.startDownload(
                    context = applicationContext,
                    urlString = url,
                    preferences = prefs
                )
            }
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Game Asset Downloader",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Displays live progress for in-game resource packages"
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildNotification(
        progress: Int,
        statusText: String,
        isIndeterminate: Boolean
    ): android.app.Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val pauseIntent = Intent(this, GameDownloadService::class.java).apply {
            action = ACTION_PAUSE
        }
        val pausePendingIntent = PendingIntent.getService(
            this,
            1,
            pauseIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Grand Theft Rickshaw: Open World")
            .setContentText(statusText)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, progress, isIndeterminate)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_media_pause, "Pause", pausePendingIntent)
            .build()
    }

    private fun buildCompletionNotification(message: String): android.app.Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Grand Theft Rickshaw")
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
