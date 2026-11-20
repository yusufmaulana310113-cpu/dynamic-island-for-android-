package com.dynamicisland.android.ui

import android.content.Intent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Reply
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.dynamicisland.android.data.IslandStateHolder
import com.dynamicisland.android.model.*
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.sin

@Composable
fun DynamicIslandRoot(
    onRequestFocusable: (Boolean) -> Unit
) {
    val mode by IslandStateHolder.mode.collectAsState()
    val settings by IslandStateHolder.settings.collectAsState()
    val showLyrics by IslandStateHolder.showLyricsPanel.collectAsState()
    val showClip by IslandStateHolder.showClipboardPanel.collectAsState()

    // Aktifkan fokus keyboard window hanya saat mode REPLY aktif agar pengguna bisa mengetik
    LaunchedEffect(mode) {
        onRequestFocusable(mode == IslandMode.REPLY)
    }

    val (targetW, targetH) = when (mode) {
        IslandMode.IDLE -> 215.dp to 36.dp
        IslandMode.MUSIC -> 276.dp to 36.dp
        IslandMode.PLAYER -> 356.dp to 156.dp
        IslandMode.NOTIF -> 356.dp to 82.dp
        IslandMode.CHARGE -> 296.dp to 56.dp
        IslandMode.HUD -> 340.dp to 80.dp
        IslandMode.REPLY -> 356.dp to 124.dp
        IslandMode.DROP -> 340.dp to 98.dp
        IslandMode.SCREENSHOT -> 324.dp to 76.dp
        IslandMode.HIDDEN -> 76.dp to 10.dp
    }

    // Fisika Spring Animation (meniru SPRING_RESPONSE = 0.46, SPRING_DAMPING = 0.70)
    val springSpec = spring<Dp>(
        dampingRatio = 0.72f,
        stiffness = Spring.StiffnessLow
    )
    val animW by animateDpAsState(targetValue = targetW * settings.scale, animationSpec = springSpec, label = "w")
    val animH by animateDpAsState(targetValue = targetH * settings.scale, animationSpec = springSpec, label = "h")
    val cornerRadius = (animH / 2).coerceAtMost(36.dp)
    val bgColor = Color(settings.bgColorArgb)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(horizontal = 8.dp)
    ) {
        Box(
            modifier = Modifier
                .width(animW)
                .height(animH)
                .clip(RoundedCornerShape(cornerRadius))
                .background(bgColor)
                .border(
                    width = 0.75.dp,
                    color = Color.White.copy(alpha = 0.14f),
                    shape = RoundedCornerShape(cornerRadius)
                )
                .pointerInput(mode) {
                    detectVerticalDragGestures { _, dragAmount ->
                        if (dragAmount < -18f) {
                            IslandStateHolder.onCapsuleSwipedUp()
                        }
                    }
                }
                .clickable { IslandStateHolder.onCapsuleTapped() },
            contentAlignment = Alignment.Center
        ) {
            AnimatedContent(
                targetState = mode,
                transitionSpec = {
                    fadeIn(tween(180)) togetherWith fadeOut(tween(140))
                },
                label = "island_content"
            ) { currentMode ->
                when (currentMode) {
                    IslandMode.HIDDEN -> Box(Modifier.fillMaxSize())
                    IslandMode.IDLE -> IdleCapsuleContent(settings)
                    IslandMode.MUSIC -> MusicCompactContent(settings)
                    IslandMode.PLAYER -> PlayerExpandedContent(settings)
                    IslandMode.NOTIF -> NotifCapsuleContent()
                    IslandMode.REPLY -> ReplyCapsuleContent()
                    IslandMode.CHARGE -> ChargeCapsuleContent()
                    IslandMode.HUD -> HudCapsuleContent()
                    IslandMode.DROP -> DropCapsuleContent()
                    IslandMode.SCREENSHOT -> ScreenshotCapsuleContent()
                }
            }
        }

        // Sub-panel melayang di bawah kapsul (Lirik / Riwayat Clipboard)
        if (showLyrics) {
            Spacer(modifier = Modifier.height(8.dp))
            LyricsBlurCard()
        } else if (showClip) {
            Spacer(modifier = Modifier.height(8.dp))
            ClipboardBlurCard()
        }
    }
}

@Composable
private fun IdleCapsuleContent(settings: IslandSettings) {
    val weather by IslandStateHolder.weather.collectAsState()
    val dropItems by IslandStateHolder.dropItems.collectAsState()
    val clipHist by IslandStateHolder.clipboardHistory.collectAsState()
    val dndManual by IslandStateHolder.dndManual.collectAsState()
    val fsBusy by IslandStateHolder.fullscreenBusy.collectAsState()

    var timeStr by remember { mutableStateOf("") }
    var dateStr by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val tf = SimpleDateFormat("HH:mm", Locale.getDefault())
        val df = SimpleDateFormat("EEE, d MMM", Locale("id", "ID"))
        while (true) {
            val now = Date()
            timeStr = tf.format(now)
            dateStr = df.format(now)
            delay(1000)
        }
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = timeStr,
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = dateStr,
                color = Color(0xFFA0A0A6),
                fontSize = 10.sp
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            if (dndManual || (settings.autoDnd && fsBusy)) {
                Icon(
                    imageVector = Icons.Rounded.NightsStay,
                    contentDescription = "DND",
                    tint = Color(0xFFAF82FF),
                    modifier = Modifier.size(13.dp)
                )
            }
            if (dropItems.isNotEmpty()) {
                BadgePill(text = "${dropItems.size}", color = Color(0xFF0A84FF)) {
                    IslandStateHolder.openDropShelf()
                }
            }
            if (clipHist.isNotEmpty()) {
                BadgePill(text = "${clipHist.size}", color = Color(0xFFFF9F0A)) {
                    IslandStateHolder.toggleClipboardPanel()
                }
            }
            if (settings.showWeather) {
                val tempText = weather.tempC?.let { "${it}°" } ?: "--°"
                Text(
                    text = tempText,
                    color = Color(0xFFEBEBF0),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun BadgePill(text: String, color: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.28f))
            .clickable { onClick() }
            .padding(horizontal = 6.dp, vertical = 1.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text = text, color = color, fontSize = 9.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun MusicCompactContent(settings: IslandSettings) {
    val media by IslandStateHolder.media.collectAsState()
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AlbumArtThumb(media = media, size = 24.dp, isRound = settings.coverRound)
        Spacer(Modifier.width(8.dp))
        Text(
            text = media.title,
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        AudioEqualizerWave(isPlaying = media.isPlaying, width = 30.dp, height = 16.dp)
    }
}

@Composable
private fun PlayerExpandedContent(settings: IslandSettings) {
    val media by IslandStateHolder.media.collectAsState()
    val showLyrics by IslandStateHolder.showLyricsPanel.collectAsState()
    val showClip by IslandStateHolder.showClipboardPanel.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AlbumArtThumb(media = media, size = 52.dp, isRound = settings.coverRound)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = media.title.ifBlank { "Tidak ada media" },
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = media.artist.ifBlank { "Artis tidak diketahui" },
                    color = Color(0xFFA0A0A6),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))
            AudioEqualizerWave(isPlaying = media.isPlaying, width = 38.dp, height = 22.dp)
        }

        // Timeline / Seekbar
        if (media.durationMs > 0) {
            val progress = (media.positionMs.toFloat() / media.durationMs.toFloat()).coerceIn(0f, 1f)
            Column {
                Slider(
                    value = progress,
                    onValueChange = { frac ->
                        val target = (frac * media.durationMs).toLong()
                        IslandStateHolder.mediaActionCallback?.invoke("seek", target)
                    },
                    modifier = Modifier.height(18.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Color.White,
                        inactiveTrackColor = Color.White.copy(alpha = 0.25f)
                    )
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(formatDuration(media.positionMs), color = Color(0xFFA0A0A6), fontSize = 9.sp)
                    Text("-${formatDuration(media.durationMs - media.positionMs)}", color = Color(0xFFA0A0A6), fontSize = 9.sp)
                }
            }
        }

        // Kontrol Pemutar + Tombol Clipboard & Lirik
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(
                onClick = { IslandStateHolder.toggleClipboardPanel() },
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    Icons.Rounded.ContentPaste,
                    contentDescription = "Clipboard",
                    tint = if (showClip) Color(0xFFFF5A96) else Color.White.copy(alpha = 0.8f),
                    modifier = Modifier.size(18.dp)
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                IconButton(
                    onClick = { IslandStateHolder.mediaActionCallback?.invoke("prev", 0L) },
                    modifier = Modifier.size(30.dp)
                ) {
                    Icon(Icons.Rounded.SkipPrevious, contentDescription = "Prev", tint = Color.White)
                }
                IconButton(
                    onClick = { IslandStateHolder.mediaActionCallback?.invoke("play_pause", 0L) },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = if (media.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = "Play/Pause",
                        tint = Color.White,
                        modifier = Modifier.size(30.dp)
                    )
                }
                IconButton(
                    onClick = { IslandStateHolder.mediaActionCallback?.invoke("next", 0L) },
                    modifier = Modifier.size(30.dp)
                ) {
                    Icon(Icons.Rounded.SkipNext, contentDescription = "Next", tint = Color.White)
                }
            }

            IconButton(
                onClick = { IslandStateHolder.toggleLyricsPanel() },
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    Icons.Rounded.Lyrics,
                    contentDescription = "Lirik",
                    tint = if (showLyrics) Color(0xFFFF5A96) else Color.White.copy(alpha = 0.8f),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun NotifCapsuleContent() {
    val notif by IslandStateHolder.activeNotif.collectAsState()
    val item = notif ?: return
    val accent = AppColorHelper.colorForPackage(item.packageName, item.appName)

    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(accent),
            contentAlignment = Alignment.Center
        ) {
            if (item.appIcon != null) {
                Image(
                    bitmap = item.appIcon.asImageBitmap(),
                    contentDescription = item.appName,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Text(
                    text = item.appName.take(1).uppercase(),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = item.appName.uppercase(),
                    color = Color(0xFF96969C),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
                Text(text = "sekarang", color = Color(0xFF96969C), fontSize = 9.sp)
            }
            Text(
                text = item.title.ifBlank { "(Tanpa judul)" },
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = item.body,
                color = Color(0xFFCDCDD2),
                fontSize = 11.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 14.sp
            )
        }

        if (item.canReply) {
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.16f))
                    .clickable { IslandStateHolder.openReplyForActiveNotif() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.Reply,
                    contentDescription = "Balas Cepat",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun ReplyCapsuleContent() {
    val context = LocalContext.current
    val replyItem by IslandStateHolder.replyItem.collectAsState()
    val feedback by IslandStateHolder.replyFeedback.collectAsState()
    val item = replyItem ?: return
    val accent = AppColorHelper.colorForPackage(item.packageName, item.appName)
    var draft by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "BALAS · ${item.appName.uppercase()}",
                    color = Color(0xFF96969C),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = item.title,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.15f))
                    .clickable { IslandStateHolder.closeReply() },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.Close, contentDescription = "Tutup", tint = Color.White, modifier = Modifier.size(14.dp))
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(36.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color.White.copy(alpha = 0.12f))
                    .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(18.dp))
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                if (draft.isEmpty()) {
                    Text("Ketik balasan…", color = Color.White.copy(alpha = 0.45f), fontSize = 12.sp)
                }
                BasicTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = true,
                    textStyle = TextStyle(color = Color.White, fontSize = 12.sp),
                    cursorBrush = SolidColor(Color.White),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(Modifier.width(8.dp))

            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(if (draft.isNotBlank()) accent else Color.White.copy(alpha = 0.15f))
                    .clickable(enabled = draft.isNotBlank()) {
                        IslandStateHolder.sendQuickReply(context, draft)
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.Send,
                    contentDescription = "Kirim",
                    tint = Color.White,
                    modifier = Modifier.size(17.dp)
                )
            }
        }

        Text(
            text = feedback.ifBlank { "Balas langsung ke ${item.appName}" },
            color = if (feedback.startsWith("Terkirim")) Color(0xFF30D158) else Color(0xFF8E8E93),
            fontSize = 9.sp
        )
    }
}

@Composable
private fun ChargeCapsuleContent() {
    val charge by IslandStateHolder.charge.collectAsState()
    val pct = charge?.level ?: 80
    val green = Color(0xFF30D158)
    val animPct by animateFloatAsState(targetValue = pct / 100f, animationSpec = tween(700), label = "batt")

    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .width(50.dp)
                    .height(24.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .border(1.5.dp, Color.White.copy(alpha = 0.85f), RoundedCornerShape(7.dp))
                    .padding(3.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(animPct)
                        .clip(RoundedCornerShape(4.dp))
                        .background(green)
                )
                Icon(
                    imageVector = Icons.Rounded.Bolt,
                    contentDescription = "Charging",
                    tint = Color.White,
                    modifier = Modifier
                        .size(16.dp)
                        .align(Alignment.Center)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(text = "$pct%", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(
                    text = if (pct >= 100) "Terisi penuh" else "Mengisi daya",
                    color = green,
                    fontSize = 10.sp
                )
            }
        }

        Icon(
            imageVector = Icons.Rounded.Bolt,
            contentDescription = null,
            tint = green,
            modifier = Modifier.size(22.dp)
        )
    }
}

@Composable
private fun HudCapsuleContent() {
    val hud by IslandStateHolder.hud.collectAsState()
    val state = hud ?: return
    val accent = when (state.kind) {
        HudKind.VOLUME -> Color(0xFF0A84FF)
        HudKind.BRIGHTNESS -> Color(0xFFFFD60A)
        HudKind.BLUETOOTH -> Color(0xFF30D158)
    }
    val icon = when (state.kind) {
        HudKind.VOLUME -> if (state.isMuted) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp
        HudKind.BRIGHTNESS -> Icons.Rounded.BrightnessHigh
        HudKind.BLUETOOTH -> Icons.Rounded.BluetoothConnected
    }
    val animVal by animateFloatAsState(targetValue = state.value, animationSpec = tween(180), label = "hud_val")

    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.1f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = state.title, tint = accent, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = state.title,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(text = state.subtitle, color = accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.18f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(animVal)
                        .clip(CircleShape)
                        .background(accent)
                )
            }
        }
    }
}

@Composable
private fun DropCapsuleContent() {
    val context = LocalContext.current
    val items by IslandStateHolder.dropItems.collectAsState()
    val first = items.firstOrNull()

    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF0A84FF).copy(alpha = 0.25f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Rounded.FolderOpen, contentDescription = "Quick Drop", tint = Color(0xFF0A84FF))
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${items.size} item di Quick Drop",
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = first?.name ?: "Rak sementara",
                color = Color(0xFFA0A0A6),
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "Bagikan ulang ke aplikasi lain",
                color = Color(0xFF78787E),
                fontSize = 9.sp
            )
        }
        IconButton(
            onClick = {
                val uris = ArrayList(items.map { it.uri })
                if (uris.isNotEmpty()) {
                    val shareIntent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                        type = "*/*"
                        putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    val chooser = Intent.createChooser(shareIntent, "Bagikan dari Quick Drop").apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(chooser)
                }
            },
            modifier = Modifier.size(32.dp)
        ) {
            Icon(Icons.Rounded.Share, contentDescription = "Share", tint = Color.White, modifier = Modifier.size(18.dp))
        }
        IconButton(
            onClick = { IslandStateHolder.clearDropShelf() },
            modifier = Modifier.size(32.dp)
        ) {
            Icon(Icons.Rounded.DeleteOutline, contentDescription = "Hapus", tint = Color.White, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun ScreenshotCapsuleContent() {
    val shot by IslandStateHolder.screenshot.collectAsState()
    val item = shot ?: return

    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            bitmap = item.bitmap.asImageBitmap(),
            contentDescription = "Screenshot",
            modifier = Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(10.dp)),
            contentScale = ContentScale.Crop
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text("Pratinjau Tangkapan", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(item.label, color = Color(0xFFA0A0A6), fontSize = 10.sp)
        }
        IconButton(onClick = { IslandStateHolder.dismissScreenshot() }) {
            Icon(Icons.Rounded.CheckCircle, contentDescription = "OK", tint = Color(0xFF30D158))
        }
    }
}

@Composable
private fun LyricsBlurCard() {
    val media by IslandStateHolder.media.collectAsState()
    val scroll = rememberScrollState()

    Column(
        modifier = Modifier
            .width(356.dp)
            .height(280.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Color(0xE6140E1E))
            .border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(24.dp))
            .padding(18.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(media.title.ifBlank { "Lirik" }, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1)
                Text(media.artist, color = Color(0xFFA0A0A6), fontSize = 11.sp, maxLines = 1)
            }
            IconButton(onClick = { IslandStateHolder.toggleLyricsPanel() }, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Rounded.Close, contentDescription = "Tutup", tint = Color.White)
            }
        }
        Spacer(Modifier.height(10.dp))
        Box(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scroll),
            contentAlignment = Alignment.TopCenter
        ) {
            val bodyText = when {
                media.isLoadingLyrics -> "Memuat lirik dari lrclib.net…"
                media.lyrics.isBlank() -> "Lirik tidak ditemukan."
                else -> media.lyrics
            }
            Text(
                text = bodyText,
                color = Color.White.copy(alpha = 0.92f),
                fontSize = 14.sp,
                lineHeight = 22.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun ClipboardBlurCard() {
    val context = LocalContext.current
    val items by IslandStateHolder.clipboardHistory.collectAsState()

    Column(
        modifier = Modifier
            .width(356.dp)
            .height(290.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Color(0xE6140E1E))
            .border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(24.dp))
            .padding(18.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Riwayat Clipboard", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            IconButton(onClick = { IslandStateHolder.toggleClipboardPanel() }, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Rounded.Close, contentDescription = "Tutup", tint = Color.White)
            }
        }
        Spacer(Modifier.height(8.dp))
        if (items.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("Belum ada teks yang disalin", color = Color(0xFF96969C), fontSize = 12.sp)
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(items) { text ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White.copy(alpha = 0.09f))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = text.replace("""\s+""".toRegex(), " "),
                            color = Color.White,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(Color(0xFFFF5A96))
                                .clickable { IslandStateHolder.copyToClipboard(context, text) }
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text("Copy", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { IslandStateHolder.clearClipboard() },
            enabled = items.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().height(34.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.14f))
        ) {
            Text("Hapus Semua", color = Color.White, fontSize = 11.sp)
        }
    }
}

@Composable
private fun AlbumArtThumb(media: MediaState, size: Dp, isRound: Boolean) {
    val spin = isRound && media.isPlaying
    val angle = remember { Animatable(0f) }
    LaunchedEffect(spin) {
        if (spin) {
            while (true) {
                angle.snapTo(0f)
                angle.animateTo(360f, tween(durationMillis = 8000, easing = LinearEasing))
            }
        }
    }
    val shape = if (isRound) CircleShape else RoundedCornerShape(size * 0.24f)

    Box(
        modifier = Modifier
            .size(size)
            .graphicsLayer { rotationZ = if (spin) angle.value else 0f }
            .clip(shape)
            .background(
                Brush.linearGradient(listOf(Color(0xFFFF5F7D), Color(0xFFBE1E4B)))
            ),
        contentAlignment = Alignment.Center
    ) {
        if (media.albumArt != null) {
            Image(
                bitmap = media.albumArt.asImageBitmap(),
                contentDescription = media.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Icon(
                imageVector = Icons.Rounded.MusicNote,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(size * 0.6f)
            )
        }
    }
}

@Composable
private fun AudioEqualizerWave(isPlaying: Boolean, width: Dp, height: Dp) {
    val phase = remember { Animatable(0f) }
    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            while (true) {
                phase.snapTo(0f)
                phase.animateTo((2f * Math.PI).toFloat(), tween(900, easing = LinearEasing))
            }
        }
    }

    Canvas(modifier = Modifier.width(width).height(height)) {
        val bars = 6
        val gap = size.width * 0.08f
        val barW = (size.width - gap * (bars - 1)) / bars
        for (i in 0 until bars) {
            val factor = if (isPlaying) {
                (0.28f + 0.72f * ((sin(phase.value + i * 1.1f) + 1f) / 2f))
            } else {
                0.2f
            }
            val barH = (size.height * factor).coerceAtLeast(barW)
            val top = (size.height - barH) / 2f
            drawRoundRect(
                color = Color(0xFFFF5A96),
                topLeft = Offset(i * (barW + gap), top),
                size = Size(barW, barH),
                cornerRadius = CornerRadius(barW / 2f, barW / 2f)
            )
        }
    }
}

private fun formatDuration(ms: Long): String {
    val totalSec = (ms.coerceAtLeast(0L) / 1000L).toInt()
    val min = totalSec / 60
    val sec = totalSec % 60
    return "%d:%02d".format(min, sec)
}
