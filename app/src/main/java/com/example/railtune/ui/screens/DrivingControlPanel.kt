package com.example.railtune.ui.screens

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

private val PanelBackground = Color(0xFF101D2C)
private val PanelAccent = Color(0xFF65CFFF)
private val PanelText = Color(0xFFEAF1FC)
private val PanelMuted = Color(0xFF9AAAC1)
private val PanelOutline = Color(0xFF344D65)

@Composable
internal fun DrivingControlPanel(
    name: String,
    note: String,
    online: Boolean,
    actualSpeed: Float,
    targetSpeed: Float,
    maxSpeed: Float,
    forward: Boolean,
    onEdit: () -> Unit,
    onSpeedChange: (Float) -> Unit,
    onDirectionChange: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, PanelOutline),
        colors = CardDefaults.cardColors(containerColor = PanelBackground)
    ) {
        Column(Modifier.padding(start = 12.dp, top = 6.dp, end = 12.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.Top) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy((-2).dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(name, modifier = Modifier.weight(1f, fill = false), color = PanelText,
                            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (online) {
                            Row(modifier = Modifier
                                .border(1.dp, Color(0xFF39D477), RoundedCornerShape(12.dp))
                                .padding(horizontal = 5.dp, vertical = 2.dp),
                                horizontalArrangement = Arrangement.spacedBy(2.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Speed, contentDescription = null,
                                    modifier = Modifier.size(10.dp), tint = Color(0xFF39D477))
                                Text("Online", color = PanelText,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = 10.sp, lineHeight = 12.sp, maxLines = 1)
                            }
                        }
                    }
                    if (note.isNotBlank()) Text(note, color = PanelText,
                        style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = "Lok bearbeiten", tint = PanelMuted)
                }
            }
            Spacer(Modifier.height(6.dp))
            HorizontalDivider(color = PanelOutline)
            Spacer(Modifier.height(12.dp))
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                // Preserve readable controls on narrow phones; let the dial take remaining space.
                val presetWidth = if (maxWidth < 360.dp) 76.dp else 88.dp
                val throttleWidth = 56.dp
                val dialWidth = (maxWidth - presetWidth - throttleWidth - 16.dp).coerceAtMost(300.dp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    DrivingSpeedometer(actualSpeed, Modifier.weight(1f).height(dialWidth))
                    Column(Modifier.width(throttleWidth), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(targetSpeed.toInt().toString(), color = PanelText,
                            fontSize = 26.sp, fontWeight = FontWeight.Bold)
                        Text("km/h", color = PanelMuted, style = MaterialTheme.typography.labelSmall)
                        Box(Modifier.width(48.dp).height(164.dp), contentAlignment = Alignment.Center) {
                            Slider(
                                value = targetSpeed.coerceIn(0f, maxSpeed),
                                onValueChange = onSpeedChange,
                                valueRange = 0f..maxSpeed,
                                modifier = Modifier.requiredWidth(164.dp).graphicsLayer { rotationZ = -90f }
                                    .semantics { contentDescription = "Sollgeschwindigkeit in km/h" },
                                colors = SliderDefaults.colors(thumbColor = PanelText,
                                    activeTrackColor = PanelAccent, inactiveTrackColor = PanelOutline)
                            )
                        }
                    }
                    Column(Modifier.width(presetWidth), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf<Int?>(null, 160, 100, 60, 30).forEach { preset ->
                            val speed = preset?.toFloat() ?: maxSpeed
                            // A fixed preset equal to Vmax is represented by Vmax as the active choice.
                            val selected = targetSpeed > 0f && abs(targetSpeed - speed) < 0.5f &&
                                (preset == null || speed != maxSpeed)
                            Button(onClick = { onSpeedChange(speed) }, enabled = speed <= maxSpeed,
                                modifier = Modifier.fillMaxWidth().height(36.dp),
                                shape = RoundedCornerShape(8.dp),
                                border = if (selected) null else BorderStroke(1.dp, PanelOutline),
                                contentPadding = PaddingValues(horizontal = 2.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (selected) PanelAccent else Color(0xFF1A2B3D),
                                    contentColor = if (selected) PanelBackground else PanelText)) {
                                Text(preset?.let { "$it km/h" } ?: "Vmax",
                                    fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = PanelOutline)
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                DirectionButton(false, !forward, { onDirectionChange(false) }, Modifier.weight(1f))
                FilledIconButton(onClick = { onSpeedChange(0f) },
                    modifier = Modifier.size(48.dp), shape = RoundedCornerShape(8.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = Color(0xFFFF5B60), contentColor = Color.White)) {
                    Icon(Icons.Default.Stop, contentDescription = "Stop", modifier = Modifier.size(18.dp))
                }
                DirectionButton(true, forward, { onDirectionChange(true) }, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun DirectionButton(forward: Boolean, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    Button(onClick = onClick, modifier = modifier.height(48.dp), shape = RoundedCornerShape(8.dp),
        border = if (selected) null else BorderStroke(1.dp, PanelOutline),
        contentPadding = PaddingValues(horizontal = 4.dp),
        colors = ButtonDefaults.buttonColors(containerColor = if (selected) PanelAccent else PanelBackground,
            contentColor = if (selected) PanelBackground else PanelText)) {
        if (!forward) {
            Icon(Icons.Default.ArrowBack, null, Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(if (forward) "Vorwärts" else "Rückwärts", fontSize = 12.sp, maxLines = 1)
        if (forward) {
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Default.ArrowForward, null, Modifier.size(16.dp))
        }
    }
}

@Composable
private fun DrivingSpeedometer(speed: Float, modifier: Modifier) {
    val animatedSpeed by animateFloatAsState(speed.coerceIn(0f, 250f), label = "drivingSpeed")
    Canvas(modifier.semantics { contentDescription = "Istgeschwindigkeit ${speed.toInt()} km/h" }) {
        // Scale the whole instrument together so ticks, labels and needle remain aligned.
        val diameter = minOf(size.width, size.height)
        val scale = diameter / 220f
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = diameter * 0.46f
        fun point(r: Float, angle: Float): Offset {
            val radians = Math.toRadians(angle.toDouble())
            return center + Offset(r * cos(radians).toFloat(), r * sin(radians).toFloat())
        }
        drawArc(PanelOutline, 140f, 260f, false,
            topLeft = center - Offset(radius, radius), size = Size(radius * 2, radius * 2),
            style = Stroke(6f * scale, cap = StrokeCap.Round))
        if (animatedSpeed > 0f) {
            drawArc(PanelAccent, 140f, animatedSpeed / 250f * 260f, false,
                topLeft = center - Offset(radius, radius), size = Size(radius * 2, radius * 2),
                style = Stroke(6f * scale, cap = StrokeCap.Round))
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = PanelText.toArgb()
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            textSize = 13f * scale
        }
        for (i in 0..50) {
            val angle = 140f + i / 50f * 260f
            val major = i % 10 == 0
            drawLine(if (major) PanelText else PanelAccent,
                point(radius - 10f * scale, angle),
                point(radius - (if (major) 19f else 15f) * scale, angle),
                strokeWidth = (if (major) 2.5f else 1.5f) * scale)
            if (major) {
                val label = point(radius - 32f * scale, angle)
                drawContext.canvas.nativeCanvas.drawText((i * 5).toString(), label.x,
                    label.y - (paint.ascent() + paint.descent()) / 2, paint)
            }
        }
        val angle = 140f + animatedSpeed / 250f * 260f
        val needle = Path().apply {
            val tip = point(radius * 0.78f * 0.75f, angle)
            val tipSide = point(1.8f * scale, angle + 90f) - center
            val left = point(4.5f * scale, angle + 90f)
            val tail = point(radius * 0.14f, angle + 180f)
            val right = point(4.5f * scale, angle - 90f)
            moveTo(tip.x + tipSide.x, tip.y + tipSide.y)
            lineTo(left.x, left.y)
            lineTo(tail.x, tail.y)
            lineTo(right.x, right.y)
            lineTo(tip.x - tipSide.x, tip.y - tipSide.y)
            quadraticBezierTo(tip.x + (tip.x - center.x) * 0.025f,
                tip.y + (tip.y - center.y) * 0.025f,
                tip.x + tipSide.x, tip.y + tipSide.y)
            close()
        }
        drawPath(needle, PanelAccent)
        drawCircle(PanelAccent, 4f * scale, point(radius * 0.14f, angle + 180f))
        drawCircle(Color(0xFFB8CDDC), 8f * scale, center)
        fun text(value: String, y: Float, fontSize: Float, color: Color) {
            paint.textSize = fontSize * scale
            paint.color = color.toArgb()
            drawContext.canvas.nativeCanvas.drawText(value, center.x, center.y + y * scale, paint)
        }
        text(animatedSpeed.toInt().toString(), 65f, 34f, PanelText)
        text("km/h", 81f, 13f, PanelAccent)
    }
}
