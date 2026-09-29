package com.windowslockpin.companion.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

private data class PortalParticle(val angle: Float, val radius: Float, val depth: Float, val size: Float)

/** One-shot scan feedback. Perspective projection gives depth without a GL surface or a looping effect. */
@Composable
internal fun EggPortalEntrance(progress: () -> Float) {
    val particles = remember {
        val random = Random(1701)
        List(24) {
            PortalParticle(random.nextFloat() * (2 * PI).toFloat(),
                0.08f + random.nextFloat() * 0.55f,
                0.7f + random.nextFloat() * 2.5f,
                0.7f + random.nextFloat() * 1.6f)
        }
    }
    Canvas(Modifier.fillMaxSize().pointerInput(Unit) {
        // Do not activate controls hidden behind the entrance; Back remains available.
        awaitPointerEventScope {
            while (true) awaitPointerEvent().changes.forEach { it.consume() }
        }
    }) {
        val t = progress().coerceIn(0f, 1f)
        val fade = (1f - ((t - 0.5f) / 0.5f).coerceIn(0f, 1f))
        val strength = sin(PI.toFloat() * t).coerceAtLeast(0f)
        val center = Offset(size.width * 0.5f, size.height * 0.44f)
        val unit = size.minDimension
        drawRect(Color(0xFF051224), alpha = fade * 0.32f)

        val glowRadius = unit * (0.12f + 0.25f * t)
        drawCircle(Brush.radialGradient(
            listOf(Color(0xFFB6F4FF).copy(alpha = strength * 0.12f),
                Color(0xFF167FE8).copy(alpha = strength * 0.06f), Color.Transparent),
            center, glowRadius), radius = glowRadius, center = center)

        // Tilted orbit rings open outward as the perspective particles approach the camera.
        repeat(1) { ring ->
            val radius = unit * (0.12f + t * 0.12f)
            var previous: Offset? = null
            for (segment in 0..64) {
                val angle = segment / 64f * (2 * PI).toFloat() + ring * 0.7f + t * 0.35f
                val x = cos(angle) * radius
                val y = sin(angle) * radius * (0.4f + ring * 0.15f)
                val tilt = ring * 0.85f - 0.7f
                val point = center + Offset(x * cos(tilt) - y * sin(tilt), x * sin(tilt) + y * cos(tilt))
                previous?.let { drawLine(Color(0xFF74DFFF), it, point, 1.dp.toPx(), alpha = strength * fade * 0.15f) }
                previous = point
            }
        }

        particles.forEach { particle ->
            fun project(at: Float): Offset {
                val depth = (particle.depth - at * 0.35f).coerceAtLeast(0.5f)
                val angle = particle.angle + at * 0.25f
                return center + Offset(cos(angle), sin(angle)) * (particle.radius * unit / depth)
            }
            val point = project(t)
            val tail = project((t - 0.025f).coerceAtLeast(0f))
            val depth = (particle.depth - t * 0.35f).coerceAtLeast(0.5f)
            val radius = (particle.size / depth).coerceIn(0.6f, 2f).dp.toPx()
            val alpha = strength * fade
            drawLine(Color(0xFF48BBFF), tail, point, radius * 0.65f, alpha = alpha * 0.16f)
            drawCircle(Color(0xFF38AEFF), radius * 2f, point, alpha = alpha * 0.04f)
            drawCircle(Color(0xFFD5FAFF), radius, point, alpha = alpha * 0.35f)
        }
    }
}
