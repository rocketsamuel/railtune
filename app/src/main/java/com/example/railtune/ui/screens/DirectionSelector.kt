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

@Composable
internal fun DirectionSelector(forward: Boolean, onDirectionChange: (Boolean) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth().height(48.dp)
        .clip(RoundedCornerShape(10.dp)).background(Color(0xFF171F30))
        .border(1.dp, Color(0xFF354158), RoundedCornerShape(10.dp)).padding(3.dp)) {
        val width = (maxWidth - 2.dp) / 2
        val step = width + 2.dp
        val density = LocalDensity.current
        val stepPx = with(density) { step.toPx() }
        var dragging by remember { mutableStateOf(false) }
        var dragPx by remember { mutableFloatStateOf(0f) }
        val dragOffset = with(density) { dragPx.toDp() }
        val offset by animateDpAsState(
            if (dragging) dragOffset else if (forward) step else 0.dp,
            animationSpec = if (dragging) snap() else tween(220), label = "Fahrtrichtung"
        )
        Box(Modifier.offset(x = if (dragging) dragOffset else offset).width(width).fillMaxHeight()
            .clip(RoundedCornerShape(8.dp)).background(Color(0xFF4780F5)))
        Row(Modifier.fillMaxSize().selectableGroup().draggable(
            orientation = Orientation.Horizontal,
            state = rememberDraggableState { dragPx = (dragPx + it).coerceIn(0f, stepPx) },
            onDragStarted = {
                dragPx = with(density) { offset.toPx() }
                dragging = true
            },
            onDragStopped = {
                val nextForward = dragPx >= stepPx / 2
                if (nextForward != forward) onDirectionChange(nextForward)
                dragging = false
            }
        ), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            listOf(false to "Rückwärts", true to "Vorwärts").forEach { (direction, label) ->
                Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(8.dp))
                    .selectable(selected = forward == direction, role = Role.Tab,
                        onClick = { if (forward != direction) onDirectionChange(direction) }),
                    contentAlignment = Alignment.Center) {
                    Text(label, style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (direction == forward) Color.White else Color(0xFF9AAAC1))
                }
            }
        }
    }
}
