package com.example.railtune.ui.screens

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt

internal fun speedPresets(maxSpeed: Float): List<Pair<String, Float>> {
    val fixed = listOf(160f, 100f, 60f, 25f)
    val speeds = (listOf(maxSpeed) + fixed).distinct().sortedDescending()
    return speeds.map { "${it.toInt()} km/h" to it } + ("Stop" to 0f)
}

internal fun speedSelectorHeight(count: Int) = 4.dp + 38.dp * count

/** Speed commands are issued only on a tap or after releasing the selection. */
@Composable
internal fun VerticalSpeedSelector(
    presets: List<Pair<String, Float>>,
    targetSpeed: Float,
    maxSpeed: Float,
    enabled: Boolean = true,
    onSpeedChange: (Float) -> Unit
) {
    // Select Stop at zero as well as the matching speed preset.
    val selected = presets.indexOfLast { abs(it.second - targetSpeed) < 0.5f }
    val density = LocalDensity.current
    val step = 38.dp
    val stepPx = with(density) { step.toPx() }
    var dragging by remember { mutableStateOf(false) }
    var dragPx by remember { mutableFloatStateOf(0f) }
    val dragOffset = with(density) { dragPx.toDp() }
    val offset by animateDpAsState(
        if (dragging) dragOffset else step * if (selected >= 0) selected else presets.lastIndex,
        animationSpec = if (dragging) snap() else tween(220), label = "Geschwindigkeitsauswahl"
    )
    Box(Modifier.fillMaxWidth().height(speedSelectorHeight(presets.size))
        .clip(RoundedCornerShape(10.dp)).background(Color(0xFF171F30))
        .border(1.dp, Color(0xFF354158), RoundedCornerShape(10.dp)).padding(3.dp)) {
        if (selected >= 0 || dragging) {
            Box(Modifier.offset(y = if (dragging) dragOffset else offset)
                .fillMaxWidth().height(36.dp).clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF4780F5)))
        }
        Column(Modifier.fillMaxSize().selectableGroup().draggable(
            enabled = enabled,
            orientation = Orientation.Vertical,
            state = rememberDraggableState { delta ->
                dragPx = (dragPx + delta).coerceIn(0f, stepPx * (presets.size - 1))
            },
            onDragStarted = { position ->
                dragPx = if (selected >= 0) with(density) { offset.toPx() }
                    else (position.y - with(density) { 18.dp.toPx() })
                        .coerceIn(0f, stepPx * (presets.size - 1))
                dragging = true
            },
            onDragStopped = {
                val index = (dragPx / stepPx).roundToInt().coerceIn(presets.indices)
                if (presets[index].second <= maxSpeed) onSpeedChange(presets[index].second)
                dragging = false
            }
        ), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            presets.forEachIndexed { index, (label, speed) ->
                val available = enabled && speed <= maxSpeed
                Box(Modifier.fillMaxWidth().height(36.dp).clip(RoundedCornerShape(8.dp))
                    .selectable(selected = index == selected, enabled = available, role = Role.Tab,
                        onClick = { onSpeedChange(speed) }), contentAlignment = Alignment.Center) {
                    Text(label, style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold, maxLines = 1,
                        color = if (!available) Color(0xFF566174) else if (index == selected) Color.White
                            else Color(0xFF9AAAC1))
                }
            }
        }
    }
}
