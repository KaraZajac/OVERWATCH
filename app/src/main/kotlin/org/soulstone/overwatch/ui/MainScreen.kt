package org.soulstone.overwatch.ui

import android.content.Intent
import android.location.Location
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.cos
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.soulstone.overwatch.data.settings.Settings
import org.soulstone.overwatch.fusion.DetectionEvent
import org.soulstone.overwatch.fusion.DetectionSource
import org.soulstone.overwatch.fusion.SourceHealth
import org.soulstone.overwatch.fusion.ThreatLevel
import org.soulstone.overwatch.scan.DeflockClient
import org.soulstone.overwatch.ui.theme.ThreatColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    running: Boolean,
    threat: ThreatLevel,
    score: Int,
    events: List<DetectionEvent>,
    mapPoints: List<DeflockClient.SurveillancePoint>,
    userLocation: Location?,
    /** Detection radius in metres — what the location sources report on, and
     *  exactly what the map circle draws. */
    detectionRadiusM: Int,
    onRadiusChange: (Int) -> Unit,
    onStartStop: () -> Unit,
    onOpenSettings: () -> Unit,
    canStart: Boolean,
    permissionMessage: String?,
    showOpenAppSettings: Boolean = false,
    onOpenAppSettings: () -> Unit = {}
) {
    var showSheet by rememberSaveable { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 24.dp)
    ) {
        // Box (rather than Row + SpaceBetween) so the title is truly centered
        // regardless of the gear icon's width.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
        ) {
            Text(
                text = "OVERWATCH",
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 4.sp,
                modifier = Modifier.align(Alignment.Center)
            )
            IconButton(
                onClick = onOpenSettings,
                modifier = Modifier.align(Alignment.CenterEnd)
            ) {
                Icon(
                    Icons.Filled.Settings,
                    contentDescription = "Settings",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(40.dp))

        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            ThreatMapCircle(
                level = threat,
                animating = running,
                userLocation = userLocation,
                mapPoints = mapPoints,
                events = events,
                mapRadiusMeters = detectionRadiusM.toFloat(),
                onTap = { showSheet = true }
            )

            Spacer(Modifier.height(10.dp))
            SourceLegend()

            Spacer(Modifier.height(4.dp))
            Text(
                text = "tap circle for source details",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )

            Spacer(Modifier.height(10.dp))
            RadiusSlider(
                radiusM = detectionRadiusM,
                onCommit = onRadiusChange,
                modifier = Modifier.padding(horizontal = 24.dp)
            )

            Spacer(Modifier.height(10.dp))

            StatusText(running = running, threat = threat, score = score, events = events)
        }

        // Push the primary action button to the bottom of the screen.
        Spacer(Modifier.weight(1f))

        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Button(
                onClick = onStartStop,
                enabled = canStart,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (running) ThreatColors.Red else ThreatColors.Green,
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
            ) {
                Text(
                    text = if (running) "STOP" else "START",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }

            if (permissionMessage != null) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = permissionMessage,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp
                )
            }
            if (showOpenAppSettings) {
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onOpenAppSettings,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Open app settings",
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }

    if (showSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSheet = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.background
        ) {
            Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
                Text(
                    text = "Detection sources",
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.height(8.dp))
                SourcesPanel(events = events)
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun ThreatMapCircle(
    level: ThreatLevel,
    animating: Boolean,
    userLocation: Location?,
    mapPoints: List<DeflockClient.SurveillancePoint>,
    events: List<DetectionEvent>,
    mapRadiusMeters: Float,
    onTap: () -> Unit
) {
    val idleColor = MaterialTheme.colorScheme.surfaceVariant
    val activeColor = when (level) {
        ThreatLevel.GREEN -> ThreatColors.Green
        ThreatLevel.YELLOW -> ThreatColors.Yellow
        ThreatLevel.ORANGE -> ThreatColors.Orange
        ThreatLevel.RED -> ThreatColors.Red
    }
    // Steady threat-tier ring around the circle: tier color while scanning,
    // muted gray when idle. Gives the current level at a glance even over the map.
    val ringColor = if (animating) activeColor else idleColor

    // No pulse value is read in this scope on purpose — see PulseVisuals.kt.
    // Reading the infinite animation here recomposed the map host every frame,
    // which re-ran the AndroidView update block (clearing and reallocating
    // every marker, and queuing a zoom) ~60 times a second.
    val camera = remember { MapCamera() }

    Box(
        modifier = Modifier
            .size(300.dp)
            .border(width = 6.dp, color = ringColor, shape = CircleShape)
            .padding(6.dp)
            .clip(CircleShape),
        contentAlignment = Alignment.Center
    ) {
        // While idle OR before the first location fix arrives, fall back to the
        // solid pulsing circle — a blank/loading map mid-tile-fetch reads as
        // broken. The map only renders once we actually have something to show.
        if (!animating || userLocation == null) {
            PulsingDisc(
                color = if (animating) activeColor else idleColor,
                animating = animating,
                label = if (animating) "WAITING FIX" else "IDLE",
                labelColor = if (animating) Color.White
                    else MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            // OSM map snapshot, centered on the user, with red ALPR pins and
            // a blue user-position dot. Non-interactive — touches are captured
            // by the click overlay above, so a tap opens the source-details
            // bottom sheet. Pan/zoom controls stay off.
            // Capture into a local non-null val so the AndroidView update
            // lambda doesn't run afoul of smart-cast-into-closure rules.
            val fix: Location = userLocation
            val ctx = LocalContext.current
            // Build the marker drawables once per Composition rather than
            // every recomposition — bitmap allocation isn't free.
            val userMark = remember(ctx) { crosshairDrawable(ctx.resources, 46, MARK_USER_WHITE) }
            val flockDot = remember(ctx) { dotDrawable(ctx.resources, 26, DOT_FLOCK_RED) }
            val speedDot = remember(ctx) { dotDrawable(ctx.resources, 22, DOT_SPEED_AMBER) }
            val cameraDot = remember(ctx) { dotDrawable(ctx.resources, 18, DOT_CAMERA_GRAY) }
            val wazeDot = remember(ctx) { dotDrawable(ctx.resources, 26, DOT_WAZE_BLUE) }
            val aircraftDot = remember(ctx) { dotDrawable(ctx.resources, 26, DOT_AIRCRAFT_VIOLET) }
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

                    // Source dots first (Flock red, Waze blue),
                    // user position last so the crosshair always draws on top.
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
                                title = e.label
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

                    // Move the camera only when the position or the radius
                    // actually changed. zoomToBoundingBox has to be deferred to
                    // map.post because it needs measured dimensions, so issuing
                    // one per pass built a backlog of stale zooms that fought
                    // each other — that was the lurching when a slider moved.
                    if (camera.needsMove(fix.latitude, fix.longitude, mapRadiusMeters)) {
                        val r = mapRadiusMeters.toDouble().coerceAtLeast(50.0)
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
            // Threat-tier scrim — pulses while scanning, in its own leaf so
            // the animation never recomposes the map above it.
            TierScrim(color = activeColor, animating = animating)
        }
        // Click capture sits on top so taps reach onTap regardless of which
        // visual layer was painted underneath.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(onClick = onTap)
        )
    }
}

/**
 * How far out to look — a view control, not a sensitivity control.
 *
 * It sets what the circle draws and what the drill-down lists, and nothing
 * else: scores come from real distance alone (see ConfidenceEngine's falloff
 * tables), so widening this adds dots and rows but cannot change the threat
 * tier. It lives on the main screen because it is the one setting a user
 * reaches for while actually moving. Commits on release rather than
 * per-pixel, so dragging doesn't restart the location scanners every frame.
 */
@Composable
private fun RadiusSlider(
    radiusM: Int,
    onCommit: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var live by remember(radiusM) { mutableFloatStateOf(radiusM.toFloat()) }
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "show within",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = "${live.toInt()} m",
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
        }
        Slider(
            value = live,
            onValueChange = { live = it },
            onValueChangeFinished = { onCommit(live.toInt()) },
            valueRange = Settings.RADIUS_MIN.toFloat()..Settings.RADIUS_MAX.toFloat(),
            steps = 48
        )
    }
}

/** What the map's coloured dots mean. Five classes now share the circle, so
 *  without this the colours are just decoration. */
@Composable
private fun SourceLegend() {
    Row(
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
    ) {
        LegendDot(Color(DOT_FLOCK_RED), "ALPR")
        LegendDot(Color(DOT_SPEED_AMBER), "speed")
        LegendDot(Color(DOT_CAMERA_GRAY), "cam")
        LegendDot(Color(DOT_WAZE_BLUE), "police")
        LegendDot(Color(DOT_AIRCRAFT_VIOLET), "air")
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 5.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun StatusText(
    running: Boolean,
    threat: ThreatLevel,
    score: Int,
    events: List<DetectionEvent>
) {
    val text = when {
        !running -> "Idle — press START to begin scanning"
        events.isEmpty() -> "All clear"
        threat == ThreatLevel.GREEN -> "Scanning… (${events.size} weak signals)"
        else -> {
            val top = events.first()
            "${top.label}  •  ${top.score}"
        }
    }
    Text(
        text = text,
        color = MaterialTheme.colorScheme.onBackground,
        fontSize = 16.sp,
        fontFamily = FontFamily.Monospace
    )
    if (running) {
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Max score: $score",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp
        )
    }
}

@Composable
private fun SourcesPanel(events: List<DetectionEvent>) {
    Column(modifier = Modifier.fillMaxWidth()) {
        DetectionSource.values().forEach { source ->
            val sourceEvents = events.filter { it.source == source }
            SourceRow(source = source, events = sourceEvents)
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** User-facing label for a detection source. The internal enum stays MIC
 *  (mic-bearing devices is the technical concept) while the UI shows the
 *  friendlier "COMMERCIAL" — Nest/Ring/Echo are commercial smart-home gear. */
private fun DetectionSource.displayLabel(): String = when (this) {
    DetectionSource.MIC -> "COMMERCIAL"
    else -> name
}

@Composable
private fun SourceRow(source: DetectionSource, events: List<DetectionEvent>) {
    val health by SourceHealth.flowFor(source).collectAsState()
    val unreachable = health.status == SourceHealth.Status.FAILED

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = source.displayLabel(),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                val maxScore = events.maxOfOrNull { it.score } ?: 0
                val statusColor = when {
                    unreachable -> MaterialTheme.colorScheme.onSurfaceVariant
                    maxScore >= ThreatLevel.RED.minScore -> ThreatColors.Red
                    maxScore >= ThreatLevel.ORANGE.minScore -> ThreatColors.Orange
                    maxScore >= ThreatLevel.YELLOW.minScore -> ThreatColors.Yellow
                    else -> ThreatColors.Green
                }
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(statusColor)
                )
            }
            if (unreachable) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = health.message ?: "Source unavailable",
                    color = ThreatColors.Orange,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            } else if (events.isEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "no detections",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp
                )
            } else {
                Spacer(Modifier.height(4.dp))
                events.take(3).forEach { e -> EventRow(e) }
                if (events.size > 3) {
                    Text(
                        text = "+${events.size - 3} more",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun EventRow(e: DetectionEvent) {
    val ctx = LocalContext.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = "${e.score}  •  ${e.label}  •  ${e.matchedMethods}",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f, fill = true)
        )
        if (e.hasGeo) {
            IconButton(
                onClick = {
                    // Force the pin to open in Google Maps rather than whichever
                    // app holds the user's default geo: handler — Waze, etc. can
                    // intercept geo: intents and we don't want that here. Falls
                    // back to a generic browser intent if Maps isn't installed.
                    val mapsUri = Uri.parse(
                        "https://www.google.com/maps/search/?api=1&query=${e.lat},${e.lon}"
                    )
                    val mapsIntent = Intent(Intent.ACTION_VIEW, mapsUri)
                        .setPackage("com.google.android.apps.maps")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try {
                        ctx.startActivity(mapsIntent)
                    } catch (_: android.content.ActivityNotFoundException) {
                        val fallback = Intent(Intent.ACTION_VIEW, mapsUri)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        try { ctx.startActivity(fallback) } catch (_: android.content.ActivityNotFoundException) {}
                    }
                },
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    Icons.Filled.Place,
                    contentDescription = "Open in Maps",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
