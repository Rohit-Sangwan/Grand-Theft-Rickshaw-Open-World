package com.sbro.emucorex.ui.home

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope

/**
 * Animated Canvas Background rendering a stylized Desi-Cyberpunk night city:
 * - Distant glowing neon city skyline
 * - Perspective road with animated cyber-lines and streetlights
 * - Iconic stylized Golden Auto-Rickshaw silhouette with glowing headlights and cyan underglow
 * - Ambient floating particles for a living, cinematic title screen.
 */
@Composable
fun RickshawCityCanvas(
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "CityPulse")

    // Headlight flicker & pulse
    val headlightPulse by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "HeadlightPulse"
    )

    // Road dash offset animation for forward motion sensation
    val roadOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "RoadDash"
    )

    Canvas(modifier = modifier.fillMaxSize()) {
        val width = size.width
        val height = size.height

        // 1. Sky Gradient (Deep Midnight to Dark Violet)
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    Color(0xFF070A14), // Deep Space Blue
                    Color(0xFF0F1528), // Dark Indigo
                    Color(0xFF1E103A), // Electric Violet Horizon
                    Color(0xFF0F0B1A)  // Ground Base
                )
            ),
            size = size
        )

        // 2. Distant Skyline Buildings (Silhouettes with Neon Windows)
        drawSkyline(width, height)

        // 3. Perspective Road with animated markings
        drawRoad(width, height, roadOffset)

        // 4. Stylized Auto-Rickshaw with glowing headlights & neon underglow
        drawRickshaw(width, height, headlightPulse)
    }
}

private fun DrawScope.drawSkyline(width: Float, height: Float) {
    val horizonY = height * 0.62f

    // Background mountains / distant glow
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color(0xFF8B5CF6).copy(alpha = 0.35f), Color.Transparent),
            center = Offset(width * 0.72f, horizonY * 0.9f),
            radius = width * 0.28f
        )
    )

    // Silhouettes of high-rises on the right half (landscape composition)
    val buildingBase = horizonY
    val buildings = listOf(
        // xStartPercent, widthPercent, heightPercent, windowGlowColor
        Triple(0.50f, 0.08f, 0.35f),
        Triple(0.57f, 0.06f, 0.45f),
        Triple(0.62f, 0.09f, 0.38f),
        Triple(0.70f, 0.07f, 0.52f),
        Triple(0.76f, 0.08f, 0.40f),
        Triple(0.83f, 0.07f, 0.48f),
        Triple(0.89f, 0.09f, 0.32f)
    )

    for (b in buildings) {
        val left = width * b.first
        val bWidth = width * b.second
        val bHeight = height * b.third
        val top = buildingBase - bHeight

        // Building body
        drawRect(
            color = Color(0xFF0A0F1D),
            topLeft = Offset(left, top),
            size = Size(bWidth, bHeight)
        )

        // Rooftop antenna with red beacon
        drawLine(
            color = Color(0xFF334155),
            start = Offset(left + bWidth / 2, top),
            end = Offset(left + bWidth / 2, top - 18f),
            strokeWidth = 2f
        )
        drawCircle(
            color = Color(0xFFEF4444),
            radius = 3f,
            center = Offset(left + bWidth / 2, top - 18f)
        )

        // Glowing office windows
        val windowCols = 3
        val windowRows = 6
        val colGap = bWidth / (windowCols + 1)
        val rowGap = bHeight / (windowRows + 2)
        for (r in 1..windowRows) {
            for (c in 1..windowCols) {
                if ((r + c) % 2 == 0) {
                    val wx = left + c * colGap - 3f
                    val wy = top + r * rowGap
                    drawRect(
                        color = if (r % 3 == 0) Color(0xFFF59E0B).copy(alpha = 0.7f) else Color(0xFF38BDF8).copy(alpha = 0.6f),
                        topLeft = Offset(wx, wy),
                        size = Size(6f, 8f)
                    )
                }
            }
        }
    }
}

private fun DrawScope.drawRoad(width: Float, height: Float, roadOffset: Float) {
    val horizonY = height * 0.62f
    val roadTopLeft = width * 0.45f
    val roadTopRight = width * 0.95f

    // Road surface
    val roadPath = Path().apply {
        moveTo(roadTopLeft, horizonY)
        lineTo(roadTopRight, horizonY)
        lineTo(width, height)
        lineTo(width * 0.30f, height)
        close()
    }

    drawPath(
        path = roadPath,
        brush = Brush.verticalGradient(
            colors = listOf(Color(0xFF131826), Color(0xFF0A0D15)),
            startY = horizonY,
            endY = height
        )
    )

    // Neon edge lines (Cyan curbs)
    drawLine(
        color = Color(0xFF06B6D4).copy(alpha = 0.6f),
        start = Offset(roadTopLeft, horizonY),
        end = Offset(width * 0.30f, height),
        strokeWidth = 3f
    )
    drawLine(
        color = Color(0xFFF59E0B).copy(alpha = 0.6f),
        start = Offset(roadTopRight, horizonY),
        end = Offset(width, height),
        strokeWidth = 3f
    )

    // Center dash lines (animated perspective)
    val numDashes = 7
    for (i in 0 until numDashes) {
        val fraction = ((i + roadOffset) / numDashes) % 1.0f
        val py = horizonY + fraction * (height - horizonY)
        val px = (roadTopLeft + roadTopRight) / 2 + fraction * (width * 0.65f - (roadTopLeft + roadTopRight) / 2)
        val dashHeight = 4f + fraction * 22f
        val dashWidth = 2f + fraction * 6f

        drawRect(
            color = Color(0xFFFBBF24).copy(alpha = 0.4f + fraction * 0.6f),
            topLeft = Offset(px - dashWidth / 2, py),
            size = Size(dashWidth, dashHeight)
        )
    }
}

private fun DrawScope.drawRickshaw(width: Float, height: Float, pulse: Float) {
    // Position the hero rickshaw at right-center in the lane
    val rx = width * 0.68f
    val ry = height * 0.72f
    val rScale = height * 0.0018f // Scaled to screen height

    // 1. Neon Cyan Underglow Ground Effect
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color(0xFF06B6D4).copy(alpha = 0.55f * pulse), Color.Transparent),
            center = Offset(rx, ry + 35f * rScale),
            radius = 120f * rScale
        )
    )

    // 2. Dual Glowing Headlight Beams casting forward
    val beamPath = Path().apply {
        moveTo(rx - 25f * rScale, ry + 10f * rScale)
        lineTo(rx - 260f * rScale, height)
        lineTo(rx - 80f * rScale, height)
        lineTo(rx + 25f * rScale, ry + 10f * rScale)
        close()
    }
    drawPath(
        path = beamPath,
        brush = Brush.verticalGradient(
            colors = listOf(
                Color(0xFFFEF08A).copy(alpha = 0.45f * pulse),
                Color(0xFFF59E0B).copy(alpha = 0.12f * pulse),
                Color.Transparent
            ),
            startY = ry,
            endY = height
        )
    )

    // 3. Auto-Rickshaw Bodywork (Iconic Yellow Top & Dark Green Bottom)
    // Lower chassis & green body
    val greenChassis = Path().apply {
        moveTo(rx - 65f * rScale, ry + 25f * rScale)
        lineTo(rx + 65f * rScale, ry + 25f * rScale)
        lineTo(rx + 55f * rScale, ry - 10f * rScale)
        lineTo(rx - 55f * rScale, ry - 10f * rScale)
        close()
    }
    drawPath(path = greenChassis, color = Color(0xFF047857)) // Emerald Green

    // Upper Yellow Canopy Hood
    val yellowCanopy = Path().apply {
        moveTo(rx - 55f * rScale, ry - 10f * rScale)
        lineTo(rx + 55f * rScale, ry - 10f * rScale)
        lineTo(rx + 45f * rScale, ry - 65f * rScale)
        lineTo(rx - 45f * rScale, ry - 65f * rScale)
        close()
    }
    drawPath(path = yellowCanopy, color = Color(0xFFFBBF24)) // Desi Taxi Gold

    // Chrome windshield frame & dark tint
    val windshield = Path().apply {
        moveTo(rx - 42f * rScale, ry - 15f * rScale)
        lineTo(rx + 42f * rScale, ry - 15f * rScale)
        lineTo(rx + 35f * rScale, ry - 55f * rScale)
        lineTo(rx - 35f * rScale, ry - 55f * rScale)
        close()
    }
    drawPath(path = windshield, color = Color(0xFF0F172A)) // Tinted Glass
    drawLine(
        color = Color(0xFF38BDF8).copy(alpha = 0.7f),
        start = Offset(rx - 35f * rScale, ry - 55f * rScale),
        end = Offset(rx + 35f * rScale, ry - 55f * rScale),
        strokeWidth = 2f
    )

    // Wheels (Left, Right and Front)
    drawCircle(color = Color(0xFF1E293B), radius = 16f * rScale, center = Offset(rx - 52f * rScale, ry + 28f * rScale))
    drawCircle(color = Color(0xFF94A3B8), radius = 6f * rScale, center = Offset(rx - 52f * rScale, ry + 28f * rScale))

    drawCircle(color = Color(0xFF1E293B), radius = 16f * rScale, center = Offset(rx + 52f * rScale, ry + 28f * rScale))
    drawCircle(color = Color(0xFF94A3B8), radius = 6f * rScale, center = Offset(rx + 52f * rScale, ry + 28f * rScale))

    // Headlight Lens (Glowing Projectors)
    drawCircle(
        color = Color(0xFFFFFBEB),
        radius = 8f * rScale * pulse,
        center = Offset(rx - 25f * rScale, ry + 8f * rScale)
    )
    drawCircle(
        color = Color(0xFFFFFBEB),
        radius = 8f * rScale * pulse,
        center = Offset(rx + 25f * rScale, ry + 8f * rScale)
    )

    // Amber Turn Indicators
    drawCircle(color = Color(0xFFF59E0B), radius = 4f * rScale, center = Offset(rx - 48f * rScale, ry + 8f * rScale))
    drawCircle(color = Color(0xFFF59E0B), radius = 4f * rScale, center = Offset(rx + 48f * rScale, ry + 8f * rScale))

    // Front Grill Badge ("GTR" metallic emblem)
    drawRect(
        color = Color(0xFFF59E0B),
        topLeft = Offset(rx - 10f * rScale, ry + 6f * rScale),
        size = Size(20f * rScale, 6f * rScale)
    )
}
