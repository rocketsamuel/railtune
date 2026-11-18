package com.example.railtune.ui.screens

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DrivingControlPanel(
    online: Boolean,
    actualSpeed: Float,
    targetSpeed: Float,
    maxSpeed: Float,
    forward: Boolean,
    onSpeedChange: (Float) -> Unit,
    onDirectionChange: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Column(Modifier.padding(top = 6.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.Top) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                // Preserve readable controls on narrow phones; let the dial take remaining space.
                // Match the 80 dp image column and 12 dp gap in the vehicle dropdown.
                val presetWidth = 84.dp
                val throttleWidth = 56.dp
                val presets = speedPresets(maxSpeed)
                val regulatorHeight = speedSelectorHeight(presets.size)
                val readingHeight = with(androidx.compose.ui.platform.LocalDensity.current) {
                    (32.sp.toDp() + MaterialTheme.typography.labelSmall.lineHeight.toDp())
                }
                val trackHeight = (regulatorHeight - readingHeight).coerceAtLeast(48.dp)
                val dialWidth = (maxWidth - presetWidth - throttleWidth - 25.dp).coerceAtMost(300.dp)
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.width(presetWidth + throttleWidth + 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.Top) {
                            Column(Modifier.width(presetWidth), horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                VerticalSpeedSelector(
                                    presets = presets,
                                    targetSpeed = targetSpeed,
                                    maxSpeed = maxSpeed,
                                    onSpeedChange = onSpeedChange
                                )
                            }
                            Column(Modifier.width(throttleWidth), horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(Modifier.width(48.dp).height(trackHeight), contentAlignment = Alignment.Center) {
                                    val throttleColors = SliderDefaults.colors(
                                        thumbColor = Color(0xFF4780F5),
                                        activeTrackColor = PanelAccent,
                                        inactiveTrackColor = Color(0xFF243247)
                                    )
                                    Slider(
                                        value = targetSpeed.coerceIn(0f, maxSpeed),
                                        onValueChange = onSpeedChange,
                                        valueRange = 0f..maxSpeed,
                                        modifier = Modifier.requiredWidth(trackHeight).graphicsLayer { rotationZ = -90f }
                                            .semantics { contentDescription = "Sollgeschwindigkeit in km/h" },
                                        colors = throttleColors,
                                        thumb = {
                                            // Rotated together with the slider: a wide grip with two horizontal ridges.
                                            Canvas(Modifier.size(width = 24.dp, height = 38.dp)
                                                .clip(RoundedCornerShape(7.dp)).background(Color(0xFF4780F5))) {
                                                listOf(0.38f, 0.62f).forEach { fraction ->
                                                    drawLine(Color(0xFFBBDFFF),
                                                        Offset(size.width * fraction, size.height * 0.25f),
                                                        Offset(size.width * fraction, size.height * 0.75f),
                                                        strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
                                                }
                                            }
                                        },
                                        track = { state ->
                                            SliderDefaults.Track(sliderState = state, colors = throttleColors,
                                                modifier = Modifier.height(18.dp))
                                        }
                                    )
                                }
                                Text(targetSpeed.toInt().toString(), color = PanelText,
                                    fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold)
                                Text("km/h", color = PanelMuted, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    DrivingSpeedometer(actualSpeed, online,
                        Modifier.weight(1f).height(dialWidth).align(Alignment.CenterVertically))
                }
            }
            Spacer(Modifier.height(12.dp))
            Spacer(Modifier.height(12.dp))
            DirectionSelector(forward = forward, onDirectionChange = onDirectionChange)
        }
    }
}

@Composable
private fun DrivingSpeedometer(speed: Float, online: Boolean, modifier: Modifier) {
    val onlineIcon = rememberVectorPainter(Icons.Default.Speed)
    val animatedSpeed by animateFloatAsState(speed.coerceIn(0f, 250f), label = "drivingSpeed")
    Canvas(modifier.semantics { contentDescription = "Istgeschwindigkeit ${speed.toInt()} km/h, RailCom ${if (online) "online" else "offline"}" }) {
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
        if (online) {
            val lamp = center + Offset(30f * scale, -34f * scale)
            val iconSize = 20f * scale
            translate(lamp.x - iconSize / 2f, lamp.y - iconSize / 2f) {
                with(onlineIcon) {
                    draw(Size(iconSize, iconSize), colorFilter = ColorFilter.tint(Color(0xFF39D477)))
                }
            }
        }
        val readingOffset = 10.dp.toPx() / scale
        text("IST", 36f + readingOffset, 10f, PanelMuted)
        text(animatedSpeed.toInt().toString(), 65f + readingOffset, 34f, PanelText)
        text("km/h", 81f + readingOffset, 13f, PanelAccent)
    }
}
