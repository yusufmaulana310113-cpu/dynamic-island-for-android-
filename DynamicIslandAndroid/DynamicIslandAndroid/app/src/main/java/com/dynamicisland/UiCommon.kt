package com.dynamicisland

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.geometry.Offset

val White = Color.White
val Gray = Color(0xFFA0A0A6)
val Blue = Color(0xFF0A84FF)
val Green = Color(0xFF30D158)
val Yellow = Color(0xFFFFD60A)
val Indigo = Color(0xFF7D7AFF)
val Red = Color(0xFFFF453A)

/** Klik tanpa ripple (kapsul memakai efek sendiri). */
@Composable
fun Modifier.tap(onClick: () -> Unit): Modifier =
    this.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)

@Composable
fun Pill(text: String, bg: Color, fg: Color = White, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(50)).background(bg).tap(onClick).padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = fg, fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
fun IconBtn(icon: ImageVector, size: Dp = 32.dp, tint: Color = White, bg: Color = Color.Transparent, onClick: () -> Unit) {
    Box(Modifier.size(size).clip(CircleShape).background(bg).tap(onClick), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(size * 0.62f))
    }
}

/** Sampul lagu: kotak membulat, atau bulat & berputar bila Prefs.coverRound aktif. */
@Composable
fun Art(size: Dp, art: ImageBitmap?, accent: Color, playing: Boolean) {
    val round = Prefs.coverRound
    val angle = if (round && playing) {
        val t = rememberInfiniteTransition(label = "spin")
        t.animateFloat(0f, 360f, infiniteRepeatable(tween(12000, easing = LinearEasing)), label = "a").value
    } else 0f
    val shape = if (round) CircleShape else RoundedCornerShape(size * 0.2f)
    Box(Modifier.size(size).graphicsLayer { rotationZ = angle }.clip(shape), contentAlignment = Alignment.Center) {
        if (art != null) {
            Image(art, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(accent, accent.copy(alpha = 0.45f)))), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.MusicNote, null, tint = White, modifier = Modifier.size(size * 0.55f))
            }
        }
    }
}

@Composable
fun Wave(playing: Boolean, color: Color) {
    val t = rememberInfiniteTransition(label = "wave")
    androidx.compose.foundation.layout.Row(
        Modifier.height(16.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(4) { i ->
            val a = t.animateFloat(0.25f, 1f, infiniteRepeatable(tween(420 + i * 130, easing = LinearEasing), RepeatMode.Reverse), label = "b$i").value
            Box(Modifier.width(3.dp).fillMaxHeight(if (playing) a else 0.25f).clip(RoundedCornerShape(2.dp)).background(color))
        }
    }
}

@Composable
fun LyricsGlyph(active: Boolean) {
    val col = if (active) Blue else Gray
    Canvas(Modifier.size(16.dp)) {
        val sw = 2.dp.toPx()
        drawLine(col, Offset(0f, size.height * 0.2f), Offset(size.width, size.height * 0.2f), sw, StrokeCap.Round)
        drawLine(col, Offset(0f, size.height * 0.5f), Offset(size.width * 0.75f, size.height * 0.5f), sw, StrokeCap.Round)
        drawLine(col, Offset(0f, size.height * 0.8f), Offset(size.width * 0.9f, size.height * 0.8f), sw, StrokeCap.Round)
    }
}

fun weatherIcon(kind: String): ImageVector = when (kind) {
    "sun" -> Icons.Filled.WbSunny
    "rain" -> Icons.Filled.WaterDrop
    else -> Icons.Filled.Cloud
}

fun weatherTint(kind: String): Color = when (kind) {
    "sun" -> Yellow
    "rain" -> Color(0xFF64B5FF)
    else -> Gray
}

fun appColor(name: String): Color {
    val palette = listOf(0xFF25D366, 0xFF0A84FF, 0xFFFF9F0A, 0xFFBF5AF2, 0xFFFF453A, 0xFF64D2FF, 0xFF30D158)
    return Color(palette[(name.lowercase().hashCode() and 0x7fffffff) % palette.size])
}

fun fmtTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}
