package org.soulstone.overwatch.ui

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
import android.content.Intent
import android.net.Uri
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
import kotlinx.coroutines.launch
import org.soulstone.overwatch.fusion.DetectionEvent
import org.soulstone.overwatch.fusion.DetectionSource
import org.soulstone.overwatch.fusion.SourceHealth
import org.soulstone.overwatch.fusion.ThreatLevel
import org.soulstone.overwatch.ui.theme.ThreatColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    running: Boolean,
    threat: ThreatLevel,
    score: Int,
    events: List<DetectionEvent>,
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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = "[DЯΣΛMMΛKΣЯ]",
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "   . //0VΣЯW4TCH",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = FontFamily.Monospace
                )
            }
            IconButton(onClick = onOpenSettings) {
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
            ThreatCircle(level = threat, animating = running, onTap = { showSheet = true })

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
private fun ThreatCircle(level: ThreatLevel, animating: Boolean, onTap: () -> Unit) {
    // When the scanner isn't running, deliberately use a muted color and IDLE
    // text so the user can tell at a glance whether they're scanning. Without
    // this, idle and "scanning, all clear" both render as solid green.
    val idleColor = MaterialTheme.colorScheme.surfaceVariant
    val activeColor = when (level) {
        ThreatLevel.GREEN -> ThreatColors.Green
        ThreatLevel.YELLOW -> ThreatColors.Yellow
        ThreatLevel.ORANGE -> ThreatColors.Orange
        ThreatLevel.RED -> ThreatColors.Red
    }
    val color = if (animating) activeColor else idleColor
    val labelText = if (animating) level.name else "IDLE"
    val labelColor = if (animating) Color.White else MaterialTheme.colorScheme.onSurfaceVariant

    val transition = rememberInfiniteTransition(label = "pulse")
    val pulse by transition.animateFloat(
        initialValue = if (animating) 0.6f else 1.0f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )
    val alpha = if (animating) pulse else 1.0f

    Box(
        modifier = Modifier
            .size(220.dp)
            .clip(CircleShape)
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        color.copy(alpha = alpha),
                        color.copy(alpha = alpha * 0.6f)
                    )
                )
            )
            .clickable(onClick = onTap),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = labelText,
            color = labelColor,
            fontSize = 28.sp,
            fontWeight = FontWeight.Black,
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
                    text = source.name,
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
                    val uri = Uri.parse("geo:${e.lat},${e.lon}?q=${e.lat},${e.lon}(${Uri.encode(e.label)})")
                    val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (intent.resolveActivity(ctx.packageManager) != null) {
                        ctx.startActivity(intent)
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
