package com.dynamicisland

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

// ============================================================================
// NOTIF: ikon aplikasi, judul, isi, tombol Balas bila ada RemoteInput
// ============================================================================
@Composable
fun AppIcon(n: NotifItem, size: androidx.compose.ui.unit.Dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.24f)), contentAlignment = Alignment.Center) {
        if (n.icon != null) {
            Image(n.icon, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Box(Modifier.fillMaxSize().background(appColor(n.app)), contentAlignment = Alignment.Center) {
                Text(n.app.take(1).uppercase(), color = White, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.5f).sp)
            }
        }
    }
}

@Composable
fun NotifContent(c: IslandController) {
    val n = IslandStore.notif ?: IslandStore.lastNotif ?: return
    val lines = notifLines(n)
    Row(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        AppIcon(n, 38.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(n.app.uppercase(), color = Gray, fontSize = 9.sp, maxLines = 1, fontWeight = FontWeight.SemiBold)
            Text(n.title, color = White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(n.body, color = Color(0xFFD1D1D6), fontSize = 11.sp, maxLines = if (lines > 1) 2 else 1, overflow = TextOverflow.Ellipsis)
        }
        if (n.canReply) {
            Spacer(Modifier.width(8.dp))
            Pill("Balas", Blue) { c.startReply() }
        }
    }
}

// ============================================================================
// REPLY: kolom teks + kirim. Jendela dibuat focusable oleh controller.
// ============================================================================
@Composable
fun ReplyContent(c: IslandController) {
    val S = IslandStore
    val n = S.reply ?: S.lastReply ?: return
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val kb = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        delay(300)
        try { focus.requestFocus(); kb?.show() } catch (_: Exception) {}
    }
    val sending = S.replyStatus == "sending"
    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.SpaceBetween) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(n, 26.dp)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text("Balas ${n.title}", color = White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(n.body, color = Gray, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconBtn(Icons.Filled.Close, 28.dp, Gray) { c.cancelReply() }
        }
        Row(
            Modifier.fillMaxWidth().height(38.dp).clip(RoundedCornerShape(50)).background(Color(0x26FFFFFF)).padding(start = 14.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                BasicTextField(
                    value = text,
                    onValueChange = { text = it.take(2000); c.touchReply() },
                    enabled = !sending,
                    singleLine = true,
                    textStyle = TextStyle(color = White, fontSize = 14.sp),
                    cursorBrush = SolidColor(White),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { c.submitReply(text) }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (text.isEmpty()) Text("Balas…", color = Color(0x70FFFFFF), fontSize = 14.sp)
                            inner()
                        }
                    },
                )
            }
            IconBtn(Icons.Filled.ArrowUpward, 30.dp, White, if (text.isBlank() || sending) Color(0x33FFFFFF) else Blue) { c.submitReply(text) }
        }
        val statusColor = when (S.replyStatus) { "ok" -> Green; "fail" -> Red; else -> Gray }
        Text(S.replyMsg.ifEmpty { " " }, color = statusColor, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ============================================================================
// CHARGE: ikon baterai terisi animasi + persentase
// ============================================================================
@Composable
fun ChargeContent() {
    val S = IslandStore
    var target by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(S.chargePct) { target = S.chargePct / 100f }
    val fill by animateFloatAsState(target, tween(900), label = "charge")
    val pulse = rememberInfiniteTransition(label = "bolt").animateFloat(0.45f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "p").value
    val col = when { S.chargePct <= 15 -> Red; S.chargePct <= 30 -> Yellow; else -> Green }
    Row(Modifier.fillMaxSize().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(40.dp, 20.dp)) {
            val bw = size.width - 4.dp.toPx()
            val outline = Color(0x66FFFFFF)
            drawRoundRect(outline, Offset.Zero, Size(bw, size.height), CornerRadius(6.dp.toPx()), Stroke(2.dp.toPx()))
            val pad = 3.5.dp.toPx()
            drawRoundRect(col, Offset(pad, pad), Size(((bw - 2 * pad) * fill).coerceAtLeast(2.dp.toPx()), size.height - 2 * pad), CornerRadius(3.dp.toPx()))
            drawRoundRect(outline, Offset(bw + 1.dp.toPx(), size.height * 0.3f), Size(3.dp.toPx(), size.height * 0.4f), CornerRadius(1.5.dp.toPx()))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Mengisi daya", color = White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text(if (S.chargePct >= 100) "Penuh" else "Sedang mengisi", color = Gray, fontSize = 10.sp)
        }
        Icon(Icons.Filled.Bolt, null, tint = col.copy(alpha = pulse), modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(4.dp))
        Text("${S.chargePct}%", color = col, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

// ============================================================================
// HUD: volume / kecerahan / baterai Bluetooth / mode fokus
// ============================================================================
@Composable
fun HudContent() {
    val S = IslandStore
    val kind = S.hudKind ?: S.lastHudKind
    val accent = when (kind) {
        HudKind.BLUETOOTH -> Green
        HudKind.BRIGHTNESS -> Yellow
        HudKind.FOCUS -> Indigo
        HudKind.VOLUME -> if (S.hudMuted) Gray else Blue
    }
    val v by animateFloatAsState(S.hudValue.coerceIn(0f, 1f), tween(120), label = "hud")
    Row(Modifier.fillMaxSize().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
            if (kind == HudKind.BLUETOOTH) {
                Canvas(Modifier.fillMaxSize()) {
                    val sw = 3.dp.toPx()
                    val inset = sw / 2
                    drawArc(Color(0x2AFFFFFF), 0f, 360f, false, Offset(inset, inset), Size(size.width - sw, size.height - sw), style = Stroke(sw))
                    drawArc(accent, -90f, 360f * v, false, Offset(inset, inset), Size(size.width - sw, size.height - sw), style = Stroke(sw, cap = StrokeCap.Round))
                }
                Icon(Icons.Filled.Bluetooth, null, tint = White, modifier = Modifier.size(18.dp))
            } else {
                Box(Modifier.fillMaxSize().clip(CircleShape).background(Color(0x12FFFFFF)), contentAlignment = Alignment.Center) {
                    val icon = when (kind) {
                        HudKind.VOLUME -> if (S.hudMuted) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp
                        HudKind.BRIGHTNESS -> Icons.Filled.BrightnessMedium
                        else -> Icons.Filled.NightsStay
                    }
                    Icon(icon, null, tint = accent, modifier = Modifier.size(22.dp))
                }
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Text(S.hudTitle, color = White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(S.hudSub, color = accent, fontSize = 10.sp, maxLines = 1)
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color(0x2AFFFFFF))) {
                Box(Modifier.fillMaxWidth(v.coerceAtLeast(0.03f)).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(accent))
            }
        }
    }
}

// ============================================================================
// DROP: rak file sementara + Bagikan (Share Sheet)
// ============================================================================
@Composable
fun DropContent(c: IslandController) {
    val files = IslandStore.dropFiles
    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.SpaceBetween) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Quick Drop · ${files.size} item", color = White, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Pill("Bagikan", Blue) { c.shareDrop() }
            Spacer(Modifier.width(6.dp))
            IconBtn(Icons.Filled.Delete, 28.dp, Gray) { c.clearDrop() }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(files.toList()) { f ->
                Row(
                    Modifier.clip(RoundedCornerShape(10.dp)).background(Color(0x1FFFFFFF)).tap { c.touchDrop() }.padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Description, null, tint = Blue, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(f.name, color = White, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(96.dp))
                }
            }
        }
    }
}

// ============================================================================
// SCREENSHOT: pratinjau screenshot terbaru
// ============================================================================
@Composable
fun ShotContent(c: IslandController) {
    val S = IslandStore
    Row(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(54.dp, 54.dp).clip(RoundedCornerShape(8.dp)).background(Color(0x22FFFFFF)), contentAlignment = Alignment.Center) {
            S.shotThumb?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Screenshot tersimpan", color = White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text("Ketuk untuk membuka", color = Gray, fontSize = 10.sp, maxLines = 1)
        }
        Pill("Bagikan", Blue) { c.shareShot() }
        Spacer(Modifier.width(4.dp))
        IconBtn(Icons.Filled.Close, 28.dp, Gray) { c.endShot() }
    }
}

// ============================================================================
// LYRICS: lirik dari lrclib.net
// ============================================================================
@Composable
fun LyricsContent(c: IslandController) {
    val S = IslandStore
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(S.title, color = White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(S.artist, color = Gray, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconBtn(Icons.Filled.Close, 30.dp, Gray) { S.lyricsOpen = false }
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            Text(S.lyrics.ifEmpty { "Memuat lirik…" }, color = Color(0xFFE5E5EA), fontSize = 13.sp, style = TextStyle(lineHeight = 20.sp))
        }
    }
}

// ============================================================================
// CLIP: riwayat clipboard (maks 5)
// ============================================================================
@Composable
fun ClipContent(c: IslandController) {
    val items = IslandStore.clip
    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Riwayat Clipboard", color = White, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Pill("Hapus semua", Color(0x26FFFFFF), Gray) { c.clearClip() }
            Spacer(Modifier.width(4.dp))
            IconBtn(Icons.Filled.Close, 28.dp, Gray) { IslandStore.clipOpen = false }
        }
        Spacer(Modifier.height(6.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (items.isEmpty()) Text("Belum ada teks yang disalin.", color = Gray, fontSize = 11.sp)
            items.toList().forEach { t ->
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0x1FFFFFFF)).tap { c.copyClip(t) }.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text(t.replace('\n', ' '), color = White, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
