package org.soulstone.overwatch.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.cos
import kotlin.math.max
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.soulstone.overwatch.data.settings.Settings
import org.soulstone.overwatch.fusion.DetectionSource
import org.soulstone.overwatch.fusion.ThreatLevel
import org.soulstone.overwatch.service.DetectionService
import org.soulstone.overwatch.ui.theme.ThreatColors

/**
 * Smaller "chat-bubble" version of the threat-map circle, hosted in a
 * WindowManager overlay by [org.soulstone.overwatch.service.OverlayManager].
 *
 * Self-contained: pulls all of its data from the same companion-level
 * StateFlows the in-app [MainScreen] uses (DetectionService.running / store /
 * mapPoints / location) plus the proximity sliders from [Settings]. The
 * caller doesn't pass any state — keeps the OverlayManager dumb.
 *
 * Tap and drag are handled at the View layer (OverlayManager's OnTouchListener);
 * this composable is render-only.
 */
@Composable
fun OverlayBubble() {
    val ctx = LocalContext.current
    val settings = remember(ctx) { Settings.get(ctx) }

    val running by DetectionService.running.collectAsState()
    val threat by DetectionService.store.threatLevel.collectAsState()
    val userLocation by DetectionService.location.collectAsState()
    val mapPoints by DetectionService.mapPoints.collectAsState()
    val events by DetectionService.store.events.collectAsState()
    val deflockProx by settings.deflockProximityM.collectAsState()
    val citizenProx by settings.citizenProximityM.collectAsState()
    val wazeProx by settings.wazeProximityM.collectAsState()
    val radius = max(max(deflockProx, citizenProx), wazeProx).toFloat()

    val activeColor = when (threat) {
        ThreatLevel.GREEN -> ThreatColors.Green
        ThreatLevel.YELLOW -> ThreatColors.Yellow
        ThreatLevel.ORANGE -> ThreatColors.Orange
        ThreatLevel.RED -> ThreatColors.Red
    }

    val transition = rememberInfiniteTransition(label = "overlay-pulse")
    val pulse by transition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200),
            repeatMode = RepeatMode.Reverse
        ),
        label = "overlay-pulse"
    )

    val userMark = remember(ctx) { crosshairDrawable(ctx.resources, 34, MARK_USER_WHITE) }
    val flockDot = remember(ctx) { dotDrawable(ctx.resources, 22, DOT_FLOCK_RED) }
    val wazeDot = remember(ctx) { dotDrawable(ctx.resources, 22, DOT_WAZE_BLUE) }
    val citizenDot = remember(ctx) { dotDrawable(ctx.resources, 22, DOT_CITIZEN_PURPLE) }

    Box(
        modifier = Modifier
            .size(140.dp)
            .clip(CircleShape),
        contentAlignment = Alignment.Center
    ) {
        // The OverlayManager only attaches the bubble while running == true,
        // but check anyway — paranoia keeps the bubble from rendering a stale
        // map if a future code path lets the composition outlive the service.
        val fix = userLocation
        if (!running || fix == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                activeColor.copy(alpha = pulse),
                                activeColor.copy(alpha = pulse * 0.6f)
                            )
                        )
                    )
            )
        } else {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { c ->
                    MapView(c).apply {
                        setTileSource(TileSourceFactory.MAPNIK)
                        setMultiTouchControls(false)
                        setBuiltInZoomControls(false)
                        isClickable = false
                        isFocusable = false
                    }
                },
                update = { map ->
                    map.controller.setCenter(GeoPoint(fix.latitude, fix.longitude))
                    map.overlays.clear()
                    for (p in mapPoints) {
                        map.overlays.add(
                            Marker(map).apply {
                                position = GeoPoint(p.lat, p.lon)
                                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                                icon = flockDot
                                title = p.operator ?: p.manufacturer ?: "ALPR"
                                setInfoWindow(null)
                            }
                        )
                    }
                    for (e in events) {
                        val lat = e.lat ?: continue
                        val lon = e.lon ?: continue
                        val dot = when (e.source) {
                            DetectionSource.WAZE -> wazeDot
                            DetectionSource.CITIZEN -> citizenDot
                            else -> null
                        } ?: continue
                        map.overlays.add(
                            Marker(map).apply {
                                position = GeoPoint(lat, lon)
                                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                                icon = dot
                                setInfoWindow(null)
                            }
                        )
                    }
                    map.overlays.add(
                        Marker(map).apply {
                            position = GeoPoint(fix.latitude, fix.longitude)
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                            icon = userMark
                            setInfoWindow(null)
                        }
                    )
                    val r = radius.toDouble().coerceAtLeast(50.0)
                    val latDegPerMeter = 1.0 / 111_000.0
                    val lonDegPerMeter = 1.0 /
                        (111_000.0 * cos(Math.toRadians(fix.latitude)).coerceAtLeast(0.01))
                    val bbox = BoundingBox(
                        fix.latitude + r * latDegPerMeter,
                        fix.longitude + r * lonDegPerMeter,
                        fix.latitude - r * latDegPerMeter,
                        fix.longitude - r * lonDegPerMeter
                    )
                    map.post { map.zoomToBoundingBox(bbox, false, 0) }
                    map.invalidate()
                },
                onRelease = { map -> map.onDetach() }
            )
            // Tier scrim — same pulse alpha range as the in-app circle.
            val scrimAlpha = (0.55f * pulse).coerceIn(0.40f, 0.65f)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(activeColor.copy(alpha = scrimAlpha))
            )
        }
    }
}
