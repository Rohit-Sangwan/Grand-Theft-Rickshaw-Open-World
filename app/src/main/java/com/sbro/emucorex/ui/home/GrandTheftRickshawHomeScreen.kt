package com.sbro.emucorex.ui.home

import android.app.Activity
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sbro.emucorex.BuildConfig
import com.sbro.emucorex.ads.AdMobBanner
import com.sbro.emucorex.ads.AdMobManager
import com.sbro.emucorex.core.BiosInitializationManager
import com.sbro.emucorex.core.GameAssetDownloader
import com.sbro.emucorex.core.GameAssetManager
import com.sbro.emucorex.core.GameDownloadService
import com.sbro.emucorex.data.AppPreferences
import com.sbro.emucorex.data.SaveStateRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Grand Theft Rickshaw: Open World - Dedicated Standalone Game Home Screen.
 *
 * Implements a modern AAA mobile landscape interface:
 * - Dynamic animated Rickshaw & City Canvas visual running in the background
 * - In-game lightweight Settings dialog (graphics, audio, haptics, controls)
 * - In-game Info dialog (player compatibility, hardware requirements, attribution)
 * - Built-in PUBG-style asset download with live progress bar and speed
 * - Option to continue download in background via Android Foreground Service
 * - Instant "PLAY GAME" launch once assets are ready
 * - Landscape-first layout with zero raw emulator menus
 */
@Composable
fun GrandTheftRickshawHomeScreen(
    onPlayGame: (gamePath: String, saveSlot: Int?) -> Unit,
    onOpenSettings: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val preferences = remember { AppPreferences(context) }
    val saveRepo = remember { SaveStateRepository(context) }

    val assetState by GameAssetManager.assetState.collectAsState()
    val resolvedGamePath by GameAssetManager.resolvedGamePath.collectAsState()
    val biosState by BiosInitializationManager.biosState.collectAsState()
    val downloadState by GameAssetDownloader.downloadState.collectAsState()
    val upscaleMultiplier by preferences.upscaleMultiplier.collectAsState(initial = 1.0f)

    var latestSaveSlot by remember { mutableStateOf<Int?>(null) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showInfoDialog by remember { mutableStateOf(false) }

    // File import fallback launchers (kept unobtrusive)
    val gameImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            GameAssetManager.importGameFromUri(context, uri, preferences)
        }
    }

    val biosImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            BiosInitializationManager.importBiosFromUri(context, uri, preferences)
        }
    }

    // Initialize AdMob and asset checks on start
    LaunchedEffect(Unit) {
        if (context is Activity) {
            AdMobManager.initialize(context)
        }
        GameAssetManager.checkAndPrepareAssets(context, preferences)
        BiosInitializationManager.checkAndPrepareBios(context, preferences)
    }

    // PUBG-style: Once asset check confirms game assets are missing, automatically begin download
    LaunchedEffect(assetState) {
        if (assetState is GameAssetManager.AssetState.Missing && downloadState is GameAssetDownloader.DownloadState.Idle) {
            GameAssetDownloader.startDownload(
                context = context,
                urlString = BuildConfig.DEFAULT_GAME_DOWNLOAD_URL,
                preferences = preferences
            )
        }
    }

    // Check for existing save state when game path is resolved
    LaunchedEffect(resolvedGamePath) {
        val path = resolvedGamePath
        if (!path.isNullOrBlank()) {
            withContext(Dispatchers.IO) {
                latestSaveSlot = saveRepo.findLatestSlot(path, GameAssetManager.TARGET_SERIAL)
            }
        }
    }

    val isGameReady = assetState is GameAssetManager.AssetState.Ready
    val isBiosReady = biosState is BiosInitializationManager.BiosState.Ready
    val canLaunch = isGameReady && isBiosReady

    val isCurrentlyDownloading = downloadState is GameAssetDownloader.DownloadState.Downloading
    val isCurrentlyVerifying = downloadState is GameAssetDownloader.DownloadState.Verifying ||
        assetState is GameAssetManager.AssetState.Preparing

    Box(
        modifier = modifier.fillMaxSize()
    ) {
        // 1. Dynamic Animated Rickshaw & Night City Artwork in Background
        RickshawCityCanvas(modifier = Modifier.fillMaxSize())

        // 2. Subtle Vignette Scrim (Darker on Left Pane for crisp UI readability)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            Color(0xF5070A14),
                            Color(0xE60A0E1A),
                            Color(0x880B1024),
                            Color(0x22070A14),
                            Color(0x55070A14)
                        )
                    )
                )
        )

        // 3. Main Landscape Layout
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 14.dp)
        ) {
            // Top Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Studio & Game Edition Branding
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Surface(
                        color = Color(0xFFF59E0B).copy(alpha = 0.15f),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, Color(0xFFF59E0B).copy(alpha = 0.5f))
                    ) {
                        Text(
                            text = "HARYANVI DEVELOPER STUDIOS",
                            color = Color(0xFFF59E0B),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.5.sp,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }

                    Text(
                        text = "• OPEN WORLD EDITION",
                        color = Color(0xFF94A3B8),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                // Header Action Buttons: Info & Settings
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Surface(
                        onClick = { showInfoDialog = true },
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFF1E293B).copy(alpha = 0.85f),
                        border = BorderStroke(1.dp, Color(0xFF334155))
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Info,
                                contentDescription = "Game & Player Info",
                                tint = Color(0xFF38BDF8),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "INFO",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Surface(
                        onClick = { showSettingsDialog = true },
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFF1E293B).copy(alpha = 0.85f),
                        border = BorderStroke(1.dp, Color(0xFF334155))
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Settings,
                                contentDescription = "Game Settings",
                                tint = Color(0xFFF59E0B),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "SETTINGS",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            // Body Split: Left HUD & Right Feature Teasers
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // LEFT PANE: Hero Title, Status, and Play / Download HUD
                Column(
                    modifier = Modifier
                        .weight(1.15f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "GRAND THEFT RICKSHAW",
                        color = Color(0xFFF8FAFC),
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 0.5.sp
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "OPEN WORLD",
                            color = Color(0xFFF59E0B),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 3.sp
                        )
                        Text(
                            text = "•",
                            color = Color(0xFF64748B),
                            fontSize = 12.sp
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(Icons.Rounded.Person, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(13.dp))
                            Text("1 Player Offline", color = Color(0xFF94A3B8), fontSize = 11.sp)
                        }
                        Text(
                            text = "•",
                            color = Color(0xFF64748B),
                            fontSize = 12.sp
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(Icons.Rounded.SportsEsports, contentDescription = null, tint = Color(0xFF34D399), modifier = Modifier.size(13.dp))
                            Text("Touch & Gamepad", color = Color(0xFF94A3B8), fontSize = 11.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = "Drive and drift your tuned auto-rickshaw across bustling bazaars, open expressways, and vibrant night districts.",
                        color = Color(0xFF94A3B8),
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    // HUD Container Card
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = when {
                                canLaunch -> Color(0xFF064E3B).copy(alpha = 0.45f)
                                isCurrentlyDownloading -> Color(0xFF0F223D).copy(alpha = 0.75f)
                                else -> Color(0xFF1E293B).copy(alpha = 0.75f)
                            }
                        ),
                        border = BorderStroke(
                            1.dp,
                            when {
                                canLaunch -> Color(0xFF10B981)
                                isCurrentlyDownloading -> Color(0xFF38BDF8)
                                else -> Color(0xFF334155)
                            }
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp)
                        ) {
                            // Status Header
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = when {
                                        canLaunch -> Icons.Rounded.CheckCircle
                                        isCurrentlyDownloading || isCurrentlyVerifying -> Icons.Rounded.Refresh
                                        else -> Icons.Rounded.Warning
                                    },
                                    contentDescription = null,
                                    tint = when {
                                        canLaunch -> Color(0xFF34D399)
                                        isCurrentlyDownloading -> Color(0xFF38BDF8)
                                        isCurrentlyVerifying -> Color(0xFFFBBF24)
                                        else -> Color(0xFFF59E0B)
                                    },
                                    modifier = Modifier.size(20.dp)
                                )

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = when {
                                            canLaunch -> "Game Ready to Play"
                                            isCurrentlyDownloading -> "DOWNLOADING ADDITIONAL ASSETS"
                                            isCurrentlyVerifying -> "Verifying Game Integrity..."
                                            !isGameReady -> "Game Assets Required (4.21 GB)"
                                            !isBiosReady -> "System Core Initializing..."
                                            else -> "Preparing Launch..."
                                        },
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp
                                    )
                                    Text(
                                        text = when {
                                            canLaunch -> "All resources verified. Ready to cruise!"
                                            isCurrentlyDownloading -> (downloadState as GameAssetDownloader.DownloadState.Downloading).statusText
                                            isCurrentlyVerifying -> "Checking files & calibrating ARM64 engine..."
                                            !isGameReady -> "Auto-downloading open world textures and maps inside the app"
                                            else -> "Engine ready"
                                        },
                                        color = Color(0xFF94A3B8),
                                        fontSize = 11.sp
                                    )
                                }
                            }

                            // Active Download Progress Bar (PUBG / Mobile Game style)
                            if (isCurrentlyDownloading) {
                                val downloadingState = downloadState as GameAssetDownloader.DownloadState.Downloading
                                val progress = downloadingState.progress
                                Spacer(modifier = Modifier.height(10.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "Downloading high-resolution textures & map...",
                                        color = Color(0xFF38BDF8),
                                        fontSize = 10.sp
                                    )
                                    Text(
                                        text = "${(progress * 100).toInt()}%",
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp
                                    )
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                LinearProgressIndicator(
                                    progress = { progress },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(7.dp),
                                    color = Color(0xFF38BDF8),
                                    trackColor = Color(0xFF1E293B),
                                )
                                Spacer(modifier = Modifier.height(10.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Option to Continue Download in Background
                                    OutlinedButton(
                                        onClick = {
                                            GameDownloadService.start(context)
                                            Toast.makeText(context, "Asset download continuing in background notification", Toast.LENGTH_SHORT).show()
                                        },
                                        shape = RoundedCornerShape(10.dp),
                                        border = BorderStroke(1.dp, Color(0xFF38BDF8).copy(alpha = 0.6f)),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF38BDF8))
                                    ) {
                                        Icon(Icons.Rounded.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Continue in Background", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                    }

                                    // Pause button
                                    TextButton(
                                        onClick = {
                                            GameDownloadService.pause(context)
                                        }
                                    ) {
                                        Icon(Icons.Rounded.Pause, contentDescription = null, tint = Color(0xFFF87171), modifier = Modifier.size(14.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Pause", color = Color(0xFFF87171), fontSize = 11.sp)
                                    }
                                }
                            } else if (isCurrentlyVerifying && assetState is GameAssetManager.AssetState.Preparing) {
                                val progress = (assetState as GameAssetManager.AssetState.Preparing).progress
                                Spacer(modifier = Modifier.height(8.dp))
                                LinearProgressIndicator(
                                    progress = { progress },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(6.dp),
                                    color = Color(0xFFF59E0B),
                                    trackColor = Color(0xFF1E293B),
                                )
                            }

                            // Start / Resume Button when not ready & not downloading
                            if (!canLaunch && !isCurrentlyDownloading && !isCurrentlyVerifying) {
                                Spacer(modifier = Modifier.height(10.dp))
                                val isResume = (downloadState as? GameAssetDownloader.DownloadState.Error)?.canResume == true
                                Button(
                                    onClick = {
                                        scope.launch {
                                            GameAssetDownloader.startDownload(
                                                context = context,
                                                urlString = BuildConfig.DEFAULT_GAME_DOWNLOAD_URL,
                                                preferences = preferences
                                            )
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (isResume) Color(0xFFD97706) else Color(0xFF0284C7)
                                    ),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        if (isResume) "RESUME ASSET DOWNLOAD" else "START ASSET DOWNLOAD",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    TextButton(onClick = { gameImportLauncher.launch(arrayOf("*/*")) }) {
                                        Text("Or Select Local ISO", color = Color(0xFF94A3B8), fontSize = 10.sp)
                                    }
                                    if (!isBiosReady) {
                                        TextButton(onClick = { biosImportLauncher.launch(arrayOf("*/*")) }) {
                                            Text("Import BIOS", color = Color(0xFFF59E0B), fontSize = 10.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // PLAY GAME & RESUME SAVE Buttons (when ready)
                    if (canLaunch) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = {
                                    val path = resolvedGamePath
                                    if (path != null) {
                                        if (context is Activity) {
                                            AdMobManager.showInterstitialIfReady(context) {
                                                onPlayGame(path, null)
                                            }
                                        } else {
                                            onPlayGame(path, null)
                                        }
                                    } else {
                                        Toast.makeText(context, "Game file not ready", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(52.dp),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981))
                            ) {
                                Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(24.dp), tint = Color.White)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "PLAY GAME",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Black,
                                    letterSpacing = 1.sp,
                                    color = Color.White
                                )
                            }

                            if (latestSaveSlot != null) {
                                OutlinedButton(
                                    onClick = {
                                        val path = resolvedGamePath
                                        if (path != null) {
                                            onPlayGame(path, latestSaveSlot)
                                        }
                                    },
                                    modifier = Modifier
                                        .weight(0.9f)
                                        .height(52.dp),
                                    shape = RoundedCornerShape(14.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFF59E0B)),
                                    border = BorderStroke(1.dp, Color(0xFFF59E0B))
                                ) {
                                    Icon(Icons.Rounded.Restore, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "RESUME (SLOT $latestSaveSlot)",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }
                    }
                }

                // RIGHT PANE: Visual Teaser Showcase & Quick Graphics Preset
                Column(
                    modifier = Modifier
                        .weight(0.85f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    // Feature highlights floating over canvas
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A).copy(alpha = 0.7f)),
                        border = BorderStroke(1.dp, Color(0xFF334155).copy(alpha = 0.8f))
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("🌆", fontSize = 14.sp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text("DESI OPEN WORLD", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                    Text("Explore high-speed alleys, highways, and roadside dhabas", color = Color(0xFF94A3B8), fontSize = 10.sp)
                                }
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("🛺", fontSize = 14.sp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text("CUSTOM RICKSHAW", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                    Text("Drift physics, nitrous boosts, and neon underglow", color = Color(0xFF94A3B8), fontSize = 10.sp)
                                }
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("⚡", fontSize = 14.sp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text("100% OFFLINE PLAY", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                    Text("No online server required. Instant local save states", color = Color(0xFF94A3B8), fontSize = 10.sp)
                                }
                            }
                        }
                    }

                    // Quick Graphics Preset Selector
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A).copy(alpha = 0.7f)),
                        border = BorderStroke(1.dp, Color(0xFF334155).copy(alpha = 0.8f))
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(Icons.Rounded.Tune, contentDescription = null, tint = Color(0xFFF59E0B), modifier = Modifier.size(15.dp))
                                Text(
                                    text = "GRAPHICS QUALITY",
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                val presets = listOf(
                                    "Smooth (1x)" to 1.0f,
                                    "Balanced (1.5x)" to 1.5f,
                                    "Ultra (2x)" to 2.0f
                                )
                                presets.forEach { (label, mult) ->
                                    val isSelected = upscaleMultiplier == mult
                                    FilterChip(
                                        selected = isSelected,
                                        onClick = {
                                            scope.launch { preferences.setUpscaleMultiplier(mult) }
                                        },
                                        label = { Text(label, fontSize = 10.sp) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = Color(0xFFF59E0B),
                                            selectedLabelColor = Color.Black,
                                            containerColor = Color(0xFF1E293B),
                                            labelColor = Color.White
                                        ),
                                        border = FilterChipDefaults.filterChipBorder(
                                            borderColor = if (isSelected) Color(0xFFF59E0B) else Color(0xFF334155),
                                            enabled = true,
                                            selected = isSelected
                                        )
                                    )
                                }
                            }
                        }
                    }

                    // Footer attribution
                    Text(
                        text = "Grand Theft Rickshaw • v1.0.0 • Haryanvi Developer",
                        color = Color(0xFF64748B),
                        fontSize = 10.sp,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                }
            }

            // Optional compact AdMob banner at very bottom
            AdMobBanner(modifier = Modifier.fillMaxWidth().height(48.dp))
        }
    }

    // In-Game Dedicated Settings Dialog
    if (showSettingsDialog) {
        GameSettingsDialog(
            preferences = preferences,
            onDismiss = { showSettingsDialog = false }
        )
    }

    // In-Game Dedicated Info & Player Compatibility Dialog
    if (showInfoDialog) {
        GameInfoDialog(
            onDismiss = { showInfoDialog = false }
        )
    }
}
