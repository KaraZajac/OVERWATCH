package org.soulstone.overwatch.ui

import android.content.Intent
import android.location.Location
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import java.util.Locale
import kotlin.math.cos
import kotlin.math.roundToInt
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.soulstone.overwatch.data.settings.Settings
import org.soulstone.overwatch.fusion.DetectionEvent
import org.soulstone.overwatch.fusion.DetectionSource
import org.soulstone.overwatch.fusion.IgnoreRule
import org.soulstone.overwatch.fusion.IgnoreRules
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
    /** Whether the running scan uses location at all. With only the radio
     *  sources on it does not, and there is no fix to wait for. */
    locating: Boolean = true,
    /** "Show within", in metres: what the map circle draws and the drill-down
     *  lists. The scanners evaluate at their own fixed radii, so it never changes
     *  a score or the tier. */
    detectionRadiusM: Int,
    onRadiusChange: (Int) -> Unit,
    /** How many rules are on the ignore list, so the sheet can say that something
     *  is being left out of it. */
    ignoredCount: Int = 0,
    onIgnore: (IgnoreRule) -> Unit = {},
    onStartStop: () -> Unit,
    onOpenSettings: () -> Unit,
    canStart: Boolean,
    permissionMessage: String?,
    showOpenAppSettings: Boolean = false,
    onOpenAppSettings: () -> Unit = {}
) {
    var showSheet by rememberSaveable { mutableStateOf(false) }
    // The row the user asked to ignore, held as it was when they tapped it: the
    // live event may be rescored or expire while the dialog is open.
    var ignoreTarget by remember { mutableStateOf<DetectionEvent?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // Background first, then insets: the colour still runs under the
            // status bar and the cutout, only the content is held clear.
            .windowInsetsPadding(WindowInsets.safeDrawing)
            // The circle, the slider and the button need about 620 dp. A phone on
            // its side, a split screen or a large font has less, and without this
            // the START/STOP button was simply off the bottom with no way to
            // reach it. When everything fits nothing changes: the column is still
            // at least a screen tall, so the spacer below keeps the button down.
            .verticalScroll(rememberScrollState())
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
                locating = locating,
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

            // Count only what the sheet will list, so "N weak signals" matches what
            // a tap shows. Anything YELLOW or above is always visible, so the
            // headline event is never filtered out.
            StatusText(
                running = running,
                threat = threat,
                score = score,
                events = events.filter { it.visibleAt(detectionRadiusM.toFloat()) }
            )
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
            // Eight sources no longer fit a small screen, so the sheet scrolls.
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 8.dp)
            ) {
                Text(
                    text = "Detection sources",
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.height(8.dp))
                SourcesPanel(
                    events = events.filter { it.visibleAt(detectionRadiusM.toFloat()) },
                    onIgnoreRequest = { ignoreTarget = it }
                )
                if (ignoredCount > 0) {
                    // Said here because an ignore list is easy to forget, and a
                    // sheet that quietly shows less than is out there would be a lie.
                    Text(
                        text = "$ignoredCount on your ignore list: hidden here and not " +
                            "counted. Review it in Settings.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    ignoreTarget?.let { target ->
        IgnoreDialog(
            event = target,
            onConfirm = { rule ->
                onIgnore(rule)
                ignoreTarget = null
            },
            onDismiss = { ignoreTarget = null }
        )
    }
}

/**
 * Confirms an ignore and, for the sources that have kinds, asks how wide it goes.
 *
 * "Just this one" is a rule on the device's address. That is exact, but item
 * trackers and some glasses and speakers swap their Bluetooth address every few
 * minutes, and the rule cannot follow them. "Every one" is the only thing that
 * sticks for those, at the price of hiding other people's as well, so the dialog
 * says both things rather than picking for the user.
 */
@Composable
private fun IgnoreDialog(
    event: DetectionEvent,
    onConfirm: (IgnoreRule) -> Unit,
    onDismiss: () -> Unit
) {
    val kindRule = remember(event) { IgnoreRules.kindRule(event, System.currentTimeMillis()) }
    var everyOne by remember(event) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Text(
                text = "Stop alerting on this?",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        },
        text = {
            Column {
                // The row as it read when tapped, distance and all: two unnamed
                // cameras differ only by that.
                Text(
                    text = event.label,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.height(10.dp))
                if (kindRule == null) {
                    DialogNote(
                        when (event.source) {
                            DetectionSource.DEFLOCK ->
                                "This camera stops raising the alert level and leaves the " +
                                    "list. It stays on the map."
                            DetectionSource.AIRCRAFT ->
                                "This aircraft stops raising the alert level and leaves the " +
                                    "list and the map."
                            else ->
                                "Matched by its address. It stops raising the alert level, " +
                                    "vibrating and showing in the list."
                        }
                    )
                } else {
                    IgnoreChoice(
                        selected = !everyOne,
                        title = "Just this one",
                        note = "Matched by its address. Trackers and some glasses and " +
                            "speakers change theirs every few minutes; if this one does, " +
                            "it will alert again.",
                        onClick = { everyOne = false }
                    )
                    IgnoreChoice(
                        selected = everyOne,
                        title = "Every \"${kindRule.label}\"",
                        note = "Hides all of them, other people's included. For your own " +
                            "gear when \"just this one\" does not stick.",
                        onClick = { everyOne = true }
                    )
                }
                Spacer(Modifier.height(6.dp))
                DialogNote("Undo it any time in Settings, under Ignored.")
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val now = System.currentTimeMillis()
                    onConfirm(
                        if (everyOne && kindRule != null) kindRule.copy(addedMs = now)
                        else IgnoreRules.deviceRule(event, now)
                    )
                }
            ) { Text("Ignore", fontFamily = FontFamily.Monospace) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", fontFamily = FontFamily.Monospace) }
        }
    )
}

@Composable
private fun DialogNote(text: String) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace
    )
}

@Composable
private fun IgnoreChoice(selected: Boolean, title: String, note: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        // Null: the row owns the click, as in Settings' radio rows.
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        Column {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace
            )
            DialogNote(note)
        }
    }
}

@Composable
private fun ThreatMapCircle(
    level: ThreatLevel,
    animating: Boolean,
    userLocation: Location?,
    locating: Boolean,
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
                // A scan with only Bluetooth and Wi-Fi sources asks for no
                // location; it said WAITING FIX from START to STOP.
                label = when {
                    !animating -> "IDLE"
                    locating -> "WAITING FIX"
                    else -> "SCANNING"
                },
                labelColor = when {
                    !animating -> MaterialTheme.colorScheme.onSurfaceVariant
                    // White on the yellow tier is 1.7:1, unreadable in daylight.
                    level == ThreatLevel.YELLOW -> Color(0xFF1A1A1A)
                    else -> Color.White
                }
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
            // Remembered *inside this branch* so its lifetime matches the
            // MapView's. Hoisting it outside was a real bug: stopping a scan
            // clears the location, which swaps this branch out and destroys the
            // MapView, but an outer `remember` kept the last camera position.
            // On the next start a brand-new MapView came up at osmdroid's
            // default zoom — the whole world — and needsMove() compared the
            // unchanged lat/lon/radius, returned false, and never zoomed it in.
            val camera = remember { MapCamera() }
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
                        if (!e.visibleAt(mapRadiusMeters)) continue
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
 * per-pixel, so the map isn't re-zoomed and the setting isn't re-saved on every
 * frame of a drag.
 */
@Composable
private fun RadiusSlider(
    radiusM: Int,
    onCommit: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var live by remember(radiusM) { mutableFloatStateOf(radiusM.toFloat()) }
    // The slider's stops are computed in floating point and land a hair under
    // the round number (1399.9998), which toInt() showed and saved as 1399 m.
    fun snapped(): Int = ((live / RADIUS_STEP_M).roundToInt() * RADIUS_STEP_M)
        .coerceIn(Settings.RADIUS_MIN, Settings.RADIUS_MAX)
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
                text = "${snapped()} m",
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
        }
        Slider(
            value = live,
            onValueChange = { live = it },
            onValueChangeFinished = { onCommit(snapped()) },
            valueRange = Settings.RADIUS_MIN.toFloat()..Settings.RADIUS_MAX.toFloat(),
            steps = (Settings.RADIUS_MAX - Settings.RADIUS_MIN) / RADIUS_STEP_M - 1
        )
    }
}

private const val RADIUS_STEP_M = 100

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
        LegendDot(Color(DOT_AIRCRAFT_VIOLET), "air/drone")
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
private fun SourcesPanel(events: List<DetectionEvent>, onIgnoreRequest: (DetectionEvent) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        DetectionSource.values().forEach { source ->
            val sourceEvents = events.filter { it.source == source }
            SourceRow(source = source, events = sourceEvents, onIgnoreRequest = onIgnoreRequest)
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** User-facing label for a detection source. The internal enum stays MIC
 *  (mic-bearing devices is the technical concept) while the UI shows the
 *  friendlier "COMMERCIAL" — Nest/Ring/Echo are commercial smart-home gear. */
internal fun DetectionSource.displayLabel(): String = when (this) {
    DetectionSource.MIC -> "COMMERCIAL"
    else -> name
}

@Composable
private fun SourceRow(
    source: DetectionSource,
    events: List<DetectionEvent>,
    onIgnoreRequest: (DetectionEvent) -> Unit
) {
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
                    // Grey only when there is nothing to show. A source can be
                    // half up (see below), and a red drone under a grey dot
                    // read as "nothing here".
                    unreachable && events.isEmpty() -> MaterialTheme.colorScheme.onSurfaceVariant
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
                // A source can be half up: AIRCRAFT's ADS-B feed can fail while
                // drones' Remote ID still arrives by radio. Show what it has.
                if (events.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    EventList(source, events, onIgnoreRequest)
                }
            } else {
                // A source that is up can still have something to say: the BLE
                // scan is on its reduced filter list for a few seconds more.
                health.message?.let { note ->
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = note,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Spacer(Modifier.height(4.dp))
                if (events.isEmpty()) {
                    Text(
                        text = "no detections",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp
                    )
                } else {
                    EventList(source, events, onIgnoreRequest)
                }
            }
        }
    }
}

/**
 * A source's detections: the top three, and the rest behind a tap.
 *
 * The rest used to be a bare "+N more" with no way to see them. That was only a
 * summary until rows could be ignored; a user's own doorbell is rarely one of the
 * three highest scores in a street full of them, and it has to be reachable.
 */
@Composable
private fun EventList(
    source: DetectionSource,
    events: List<DetectionEvent>,
    onIgnoreRequest: (DetectionEvent) -> Unit
) {
    var expanded by rememberSaveable(source) { mutableStateOf(false) }
    val shown = if (expanded) events else events.take(COLLAPSED_ROWS)
    shown.forEach { e -> EventRow(e, onIgnoreRequest) }
    if (events.size > COLLAPSED_ROWS) {
        Text(
            text = if (expanded) "show fewer" else "+${events.size - COLLAPSED_ROWS} more",
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .clickable { expanded = !expanded }
                .padding(vertical = 6.dp)
        )
    }
}

private const val COLLAPSED_ROWS = 3

@Composable
private fun EventRow(e: DetectionEvent, onIgnoreRequest: (DetectionEvent) -> Unit) {
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
                    // Fixed decimals: Double.toString() writes a coordinate
                    // within 0.001 degrees of the equator or of Greenwich as
                    // "-5.0E-4", which is not a place Maps can find.
                    val mapsUri = Uri.parse(
                        "https://www.google.com/maps/search/?api=1&query=" +
                            String.format(Locale.US, "%.6f,%.6f", e.lat, e.lon)
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
        if (IgnoreRules.canIgnore(e)) {
            IconButton(
                onClick = { onIgnoreRequest(e) },
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    Icons.Filled.NotificationsOff,
                    contentDescription = "Stop alerting on this",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
