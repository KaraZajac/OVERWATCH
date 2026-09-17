package org.soulstone.overwatch.ui

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
import org.soulstone.overwatch.scan.DeflockClient
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
    val detectionRadius by settings.detectionRadiusM.collectAsState()
    val radius = detectionRadius.toFloat()

    val activeColor = when (threat) {
        ThreatLevel.GREEN -> ThreatColors.Green
        ThreatLevel.YELLOW -> ThreatColors.Yellow
        ThreatLevel.ORANGE -> ThreatColors.Orange
        ThreatLevel.RED -> ThreatColors.Red
    }

    // The pulse lives in PulseVisuals' leaves now; reading it here would
    // recompose the map host every frame.

    val userMark = remember(ctx) { crosshairDrawable(ctx.resources, 34, MARK_USER_WHITE) }
    val flockDot = remember(ctx) { dotDrawable(ctx.resources, 22, DOT_FLOCK_RED) }
    val speedDot = remember(ctx) { dotDrawable(ctx.resources, 18, DOT_SPEED_AMBER) }
    val cameraDot = remember(ctx) { dotDrawable(ctx.resources, 15, DOT_CAMERA_GRAY) }
    val wazeDot = remember(ctx) { dotDrawable(ctx.resources, 22, DOT_WAZE_BLUE) }
    val aircraftDot = remember(ctx) { dotDrawable(ctx.resources, 22, DOT_AIRCRAFT_VIOLET) }

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
            PulsingDisc(
                color = activeColor,
                animating = running,
                label = null,
                labelColor = activeColor
            )
        } else {
            // Scoped to this branch so it dies with the MapView — see the same
            // note in MainScreen; an outer remember left a rebuilt map stuck at
            // world zoom.
            val camera = remember { MapCamera() }
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
                    map.overlays.clear()
                    for (p in mapPoints) {
                        map.overlays.add(
                            Marker(map).apply {
                                position = GeoPoint(p.lat, p.lon)
                                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                                icon = when (p.kind) {
                                    DeflockClient.Kind.ALPR -> flockDot
                                    DeflockClient.Kind.SPEED_CAMERA -> speedDot
                                    DeflockClient.Kind.CAMERA -> cameraDot
                                }
                                title = p.operator ?: p.manufacturer ?: p.kind.name
                                setInfoWindow(null)
                            }
                        )
                    }
                    for (e in events) {
                        if (!e.visibleAt(radius)) continue
                        val lat = e.lat ?: continue
                        val lon = e.lon ?: continue
                        val dot = when (e.source) {
                            DetectionSource.WAZE -> wazeDot
                            DetectionSource.AIRCRAFT -> aircraftDot
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
                    // Same camera guard as the in-app circle — see MapCamera.
                    if (camera.needsMove(fix.latitude, fix.longitude, radius)) {
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
                        map.controller.setCenter(GeoPoint(fix.latitude, fix.longitude))
                        map.post { map.zoomToBoundingBox(bbox, false, 0) }
                    }
                    map.invalidate()
                },
                onRelease = { map -> map.onDetach() }
            )
            // Tier scrim — own leaf, so the pulse never recomposes the map.
            TierScrim(color = activeColor, animating = running)
        }
    }
}
