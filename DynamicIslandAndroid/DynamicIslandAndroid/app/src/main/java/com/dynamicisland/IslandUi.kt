@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.dynamicisland

import android.os.SystemClock
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
private val dateFmt = SimpleDateFormat("EEE, d MMM", Locale("id", "ID"))

fun targetSize(mode: Mode, maxW: Float): Pair<Float, Float> {
    val w = if (mode == Mode.IDLE) idleWidth() else mode.w
    val h = if (mode == Mode.NOTIF) notifHeight() else mode.h
    return minOf(w, maxW) to h
}

/** Root Compose. Seluruh UI dikalikan Prefs.scale lewat Density. */
@Composable
fun IslandRoot(c: IslandController, onWindow: (Float, Float) -> Unit) {
    val base = LocalDensity.current
    val dens = Density(base.density * Prefs.scale, base.fontScale)
    CompositionLocalProvider(LocalDensity provides dens) { Capsule(c, onWindow) }
}

@Composable
private fun Capsule(c: IslandController, onWindow: (Float, Float) -> Unit) {
    val S = IslandStore
    val mode = S.mode
    val maxW = S.screenWidthDp / Prefs.scale - 12f
    val (tw, th) = targetSize(mode, maxW)

    // Animasi pegas (setara _step_spring: response 0.46 s, damping 0.70)
    val spec = spring<Dp>(dampingRatio = C.SPRING_DAMPING, stiffness = C.SPRING_STIFFNESS)
    val w by animateDpAsState(tw.dp, spec, label = "w")
    val h by animateDpAsState(th.dp, spec, label = "h")

    // Jendela overlay diperbesar dulu sebelum animasi, dikecilkan setelah pegas selesai (touch area tetap kecil)
    LaunchedEffect(tw, th) {
        onWindow(maxOf(w.value, tw), maxOf(h.value, th))
        snapshotFlow { abs(w.value - tw) < 0.6f && abs(h.value - th) < 0.6f }.first { it }
        onWindow(tw, th)
    }

    val radius = minOf(h / 2, C.MAX_CORNER.dp)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Box(
            Modifier
                .size(w, h)
                .clip(RoundedCornerShape(radius))
                .background(Color(Prefs.bg))
                .pointerInput(mode) { detectTapGestures(onTap = { c.onTap() }, onLongPress = { c.onLongPress() }) }
                .pointerInput(mode) {
                    var dy = 0f
                    detectVerticalDragGestures(
                        onDragStart = { dy = 0f },
                        onDragEnd = { if (dy < -20f) c.swipeUp() },
                        onVerticalDrag = { _, d -> dy += d },
                    )
                },
            contentAlignment = Alignment.TopCenter,
        ) {
            Crossfade(targetState = mode, animationSpec = tween(150), modifier = Modifier.fillMaxSize(), label = "content") { m ->
                val (cw, ch) = targetSize(m, maxW)
                Box(Modifier.fillMaxSize().wrapContentSize(Alignment.TopCenter, unbounded = true)) {
                    Box(Modifier.size(cw.dp, ch.dp)) { ModeContent(m, c) }
                }
            }
        }
    }
}

@Composable
private fun ModeContent(m: Mode, c: IslandController) {
    when (m) {
        Mode.IDLE -> IdleContent(c)
        Mode.MUSIC -> MusicContent()
        Mode.PLAYER -> PlayerContent(c)
        Mode.NOTIF -> NotifContent(c)
        Mode.REPLY -> ReplyContent(c)
        Mode.CHARGE -> ChargeContent()
        Mode.HUD -> HudContent()
        Mode.DROP -> DropContent(c)
        Mode.SCREENSHOT -> ShotContent(c)
        Mode.LYRICS -> LyricsContent(c)
        Mode.CLIP -> ClipContent(c)
        Mode.HIDDEN -> Box(Modifier.fillMaxSize())
    }
}

// ============================================================================
// IDLE: jam di kiri, celah kamera di tengah, cuaca + badge di kanan
// ============================================================================
@Composable
private fun IdleContent(c: IslandController) {
    val S = IslandStore
    val clock by produceState(initialValue = Date().let { timeFmt.format(it) to dateFmt.format(it) }) {
        while (true) { val d = Date(); value = timeFmt.format(d) to dateFmt.format(d); delay(1000) }
    }
    Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            Column {
                Text(clock.first, color = White, fontSize = 11.sp, fontWeight = FontWeight.Bold, style = TextStyle(lineHeight = 12.sp))
                Text(clock.second, color = Gray, fontSize = 8.sp, style = TextStyle(lineHeight = 9.sp), maxLines = 1)
            }
        }
        Spacer(Modifier.width(Prefs.cutoutGap.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (S.dropFiles.isNotEmpty()) Badge(Icons.Filled.Inbox, Blue) { c.openDrop() }
                if (S.clip.isNotEmpty()) Badge(Icons.Filled.ContentPaste, Gray) { c.toggleClip() }
                if (S.dndActive) Badge(Icons.Filled.NightsStay, Indigo) { c.toggleDnd() }
                if (Prefs.showWeather) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(weatherIcon(S.weatherKind), null, tint = weatherTint(S.weatherKind), modifier = Modifier.size(13.dp))
                        Spacer(Modifier.width(3.dp))
                        Text(S.weatherTemp?.let { "$it°C" } ?: "--°C", color = White, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun Badge(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, onClick: () -> Unit) {
    Box(Modifier.size(18.dp).clip(CircleShape).tap(onClick), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(14.dp))
    }
}

// ============================================================================
// MUSIC (kompak): sampul di kiri, gelombang di kanan, celah kamera di tengah
// ============================================================================
@Composable
private fun MusicContent() {
    val S = IslandStore
    Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Art(24.dp, S.art, S.accent, S.playing)
        Spacer(Modifier.weight(1f))
        Wave(S.playing, S.accent)
    }
}

// ============================================================================
// PLAYER: sampul, judul (marquee), lirik, seek bar, kontrol
// ============================================================================
@Composable
private fun PlayerContent(c: IslandController) {
    val S = IslandStore
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(S.playing) { while (S.playing) { now = SystemClock.elapsedRealtime(); delay(250) } }
    val live = if (S.playing) S.posMs + ((now - S.stampMs) * S.speed).toLong() else S.posMs
    val pos = if (S.durMs > 0) live.coerceIn(0L, S.durMs) else live.coerceAtLeast(0L)
    var scrub by remember { mutableStateOf<Float?>(null) }
    val frac = scrub ?: if (S.durMs > 0) pos.toFloat() / S.durMs else 0f
    val shownMs = scrub?.let { (it * S.durMs).toLong() } ?: pos

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.SpaceBetween) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.tap { c.openMediaApp() }) { Art(52.dp, S.art, S.accent, S.playing) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(S.title, color = White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                    modifier = Modifier.fillMaxWidth().basicMarquee(iterations = Int.MAX_VALUE))
                Text(S.artist, color = Gray, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box(Modifier.size(32.dp).clip(CircleShape).tap { c.toggleLyrics() }, contentAlignment = Alignment.Center) {
                LyricsGlyph(S.lyricsOpen)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(fmtTime(shownMs), color = Gray, fontSize = 9.sp, modifier = Modifier.width(32.dp))
            Box(Modifier.weight(1f)) {
                SeekBar(frac, S.accent, S.canSeek || S.demo, { scrub = it; c.touchPlayer() }, { c.seek(it) })
            }
            Text(if (S.durMs > 0) fmtTime(S.durMs) else "--:--", color = Gray, fontSize = 9.sp, modifier = Modifier.width(32.dp))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            IconBtn(Icons.Filled.SkipPrevious, 38.dp) { c.mediaCommand("prev") }
            Spacer(Modifier.width(18.dp))
            IconBtn(if (S.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, 38.dp, Color.Black, White) { c.mediaCommand("play") }
            Spacer(Modifier.width(18.dp))
            IconBtn(Icons.Filled.SkipNext, 38.dp) { c.mediaCommand("next") }
        }
    }
}

@Composable
private fun SeekBar(frac: Float, accent: Color, enabled: Boolean, onScrub: (Float?) -> Unit, onSeek: (Float) -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(22.dp)
            .pointerInput(enabled) {
                if (enabled) detectTapGestures { o -> onSeek((o.x / size.width).coerceIn(0f, 1f)) }
            }
            .pointerInput(enabled) {
                if (enabled) {
                    var last = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { o -> last = (o.x / size.width).coerceIn(0f, 1f); onScrub(last) },
                        onDragEnd = { onScrub(null); onSeek(last) },
                        onDragCancel = { onScrub(null) },
                        onHorizontalDrag = { ch, _ -> last = (ch.position.x / size.width).coerceIn(0f, 1f); onScrub(last) },
                    )
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxWidth().height(22.dp)) {
            val th = 4.dp.toPx()
            val y = (size.height - th) / 2f
            val r = CornerRadius(th / 2f)
            drawRoundRect(Color(0x33FFFFFF), Offset(0f, y), Size(size.width, th), r)
            drawRoundRect(accent, Offset(0f, y), Size((size.width * frac.coerceIn(0f, 1f)).coerceAtLeast(th), th), r)
            if (enabled) drawCircle(White, 5.dp.toPx(), Offset(size.width * frac.coerceIn(0f, 1f), size.height / 2f))
        }
    }
}
