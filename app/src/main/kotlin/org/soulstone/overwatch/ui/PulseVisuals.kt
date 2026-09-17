package org.soulstone.overwatch.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.Text
import androidx.compose.ui.unit.sp

/**
 * The pulsing threat visuals, deliberately split into their own leaf
 * composables.
 *
 * **Why this file exists.** The pulse is an infinite animation, so anything
 * that reads it recomposes on every frame. Both the in-app circle and the
 * floating bubble used to read it in the same scope that hosted the map's
 * `AndroidView`, which meant the map's `update` block re-ran ~60 times a
 * second: it cleared every overlay and reallocated a `Marker` for all of them
 * (152 ALPRs in a dense area) each frame, and queued a `zoomToBoundingBox`
 * through `map.post` on every one of those passes while `setCenter` fought it.
 * That is what made the map flicker and lurch — worst when a proximity slider
 * moved, because the new bounding box landed on top of a backlog of stale
 * zoom runnables.
 *
 * Keeping the animated read inside these leaves means the map host recomposes
 * only when the fix, the points, the events or the radius actually change.
 * Anything that hosts a map must therefore render the pulse through these and
 * never read a pulse value in its own scope.
 */
@Composable
private fun rememberPulse(animating: Boolean): State<Float> {
    val transition = rememberInfiniteTransition(label = "threat-pulse")
    return transition.animateFloat(
        initialValue = if (animating) 0.5f else 1.0f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200),
            repeatMode = RepeatMode.Reverse
        ),
        label = "threat-pulse"
    )
}

/** Solid pulsing disc — the idle / waiting-for-fix face of the circle. */
@Composable
internal fun PulsingDisc(
    color: Color,
    animating: Boolean,
    label: String?,
    labelColor: Color,
    modifier: Modifier = Modifier
) {
    val pulse by rememberPulse(animating)
    val alpha = if (animating) pulse else 1.0f
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(color.copy(alpha = alpha), color.copy(alpha = alpha * 0.6f))
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        if (label != null) {
            Text(
                text = label,
                color = labelColor,
                fontSize = 22.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

/** Threat-tier scrim laid over the map so the tier reads at a glance. */
@Composable
internal fun TierScrim(color: Color, animating: Boolean, modifier: Modifier = Modifier) {
    val pulse by rememberPulse(animating)
    val scrimAlpha = (0.55f * pulse).coerceIn(0.40f, 0.65f)
    Box(modifier.fillMaxSize().background(color.copy(alpha = scrimAlpha)))
}

/**
 * Remembers the last camera position applied to a [org.osmdroid.views.MapView]
 * so the bounding-box zoom is only issued when the center or radius actually
 * moved, rather than on every pass through an `AndroidView` update block.
 */
internal class MapCamera {
    private var lat = Double.NaN
    private var lon = Double.NaN
    private var radius = Float.NaN

    /** True (and records the new values) when this camera differs from the last applied one. */
    fun needsMove(newLat: Double, newLon: Double, newRadius: Float): Boolean {
        // Sub-metre jitter on a stationary fix shouldn't re-zoom the map.
        val moved = lat.isNaN() ||
            kotlin.math.abs(newLat - lat) > 1e-5 ||
            kotlin.math.abs(newLon - lon) > 1e-5 ||
            radius != newRadius
        if (moved) { lat = newLat; lon = newLon; radius = newRadius }
        return moved
    }
}
