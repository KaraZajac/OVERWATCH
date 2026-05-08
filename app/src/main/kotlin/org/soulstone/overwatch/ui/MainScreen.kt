package org.soulstone.overwatch.ui

import android.content.Intent
import android.location.Location
import android.net.Uri
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
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
    mapPoints: List<DeflockClient.AlprPoint>,
    userLocation: Location?,
    /** Visible radius of the map circle, in meters. Driven by the larger of
     *  the DeFlock and Citizen proximity sliders so the user sees the full
     *  area where a detection could fire. */
    mapRadiusMeters: Float,
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
                mapRadiusMeters = mapRadiusMeters,
                onTap = { showSheet = true }
            )

            Spacer(Modifier.height(12.dp))
            Text(
                text = "tap circle for source details",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )

            Spacer(Modifier.height(24.dp))

            StatusText(running = running, threat = threat, score = score, events = events)

            Spacer(Modifier.height(24.dp))

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
    mapPoints: List<DeflockClient.AlprPoint>,
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

    val transition = rememberInfiniteTransition(label = "pulse")
    val pulse by transition.animateFloat(
        initialValue = if (animating) 0.5f else 1.0f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    Box(
        modifier = Modifier
            .size(220.dp)
            .clip(CircleShape),
        contentAlignment = Alignment.Center
    ) {
        // While idle OR before the first location fix arrives, fall back to the
        // solid pulsing circle — a blank/loading map mid-tile-fetch reads as
        // broken. The map only renders once we actually have something to show.
        if (!animating || userLocation == null) {
            val color = if (animating) activeColor else idleColor
            val alpha = if (animating) pulse else 1.0f
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                color.copy(alpha = alpha),
                                color.copy(alpha = alpha * 0.6f)
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                val labelText = when {
                    !animating -> "IDLE"
                    else -> "WAITING FIX"
                }
                Text(
                    text = labelText,
                    color = if (animating) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace
                )
            }
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
            val userDot = remember(ctx) { dotDrawable(ctx.resources, 36, DOT_USER_BLUE) }
            val flockDot = remember(ctx) { dotDrawable(ctx.resources, 26, DOT_FLOCK_RED) }
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

                    // ALPR dots first, user dot last so the user draws on top.
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
                    map.overlays.add(
                        Marker(map).apply {
                            position = GeoPoint(fix.latitude, fix.longitude)
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                            icon = userDot
                            setInfoWindow(null)
                        }
                    )

                    // Fit the visible radius to the larger of the two proximity
                    // settings. Defer to map.post so the call lands after layout
                    // — zoomToBoundingBox needs measured dimensions to compute
                    // the right zoom level. Latitude-aware longitude scaling so
                    // the bbox stays roughly square in real meters at any lat.
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
                    map.post { map.zoomToBoundingBox(bbox, false, 0) }
                    map.invalidate()
                },
                onRelease = { map -> map.onDetach() }
            )
            // Threat-tier scrim — pulses while scanning. Heavier alpha than
            // the first cut so the tier color reads at a glance over OSM
            // tiles, which are themselves cream/light by default.
            val scrimAlpha = (0.55f * pulse).coerceIn(0.40f, 0.65f)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(activeColor.copy(alpha = scrimAlpha))
            )
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
