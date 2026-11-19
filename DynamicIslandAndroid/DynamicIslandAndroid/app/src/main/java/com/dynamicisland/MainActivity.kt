package com.dynamicisland

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat

class MainActivity : ComponentActivity() {
    private val resumeTick = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) { SettingsScreen(resumeTick.intValue) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeTick.intValue++
    }
}

private class PermItem(val title: String, val desc: String, val granted: Boolean, val required: Boolean, val action: () -> Unit)

private fun has(ctx: Context, p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

private val colorPresets = listOf(
    0xFF000000.toInt(), 0xFF1C1C1E.toInt(), 0xFF0B1D3A.toInt(),
    0xFF2B1055.toInt(), 0xFF0F2E1C.toInt(), 0xFF3A0D12.toInt(),
)

@Composable
private fun SettingsScreen(tick: Int) {
    val ctx = LocalContext.current
    var local by remember { mutableIntStateOf(0) }
    @Suppress("UNUSED_VARIABLE") val refresh = tick + local
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { local++ }
    val running = IslandService.running

    val overlayOk = Settings.canDrawOverlays(ctx)
    val listenerOk = IslandNotificationListener.isEnabled(ctx)
    val nm = ctx.getSystemService(NotificationManager::class.java)
    val pm = ctx.getSystemService(PowerManager::class.java)
    val mediaPerm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE

    fun open(a: String, withPkg: Boolean = false) {
        val i = Intent(a)
        if (withPkg) i.data = Uri.parse("package:${ctx.packageName}")
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { ctx.startActivity(i) } catch (_: Exception) { Toast.makeText(ctx, "Tidak bisa membuka pengaturan", Toast.LENGTH_SHORT).show() }
    }

    val perms = buildList {
        add(PermItem("Tampil di atas aplikasi lain", "Wajib. SYSTEM_ALERT_WINDOW untuk menggambar kapsul di atas layar.", overlayOk, true) {
            open(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, true)
        })
        add(PermItem("Akses notifikasi", "Wajib untuk notifikasi, Balas Cepat, dan sesi media (Spotify dll).", listenerOk, true) {
            open(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        })
        add(PermItem("Tampilkan notifikasi", "Dibutuhkan agar service latar belakang tetap berjalan (Android 13+).",
            Build.VERSION.SDK_INT < 33 || has(ctx, Manifest.permission.POST_NOTIFICATIONS), true) {
            if (Build.VERSION.SDK_INT >= 33) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        })
        add(PermItem("Bluetooth (perangkat terhubung)", "Untuk HUD nama & baterai earbud/headset.",
            Build.VERSION.SDK_INT < 31 || has(ctx, Manifest.permission.BLUETOOTH_CONNECT), false) {
            if (Build.VERSION.SDK_INT >= 31) launcher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        })
        add(PermItem("Lokasi perkiraan", "Untuk cuaca akurat. Tanpa izin ini lokasi dari IP dipakai.",
            has(ctx, Manifest.permission.ACCESS_COARSE_LOCATION), false) {
            launcher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        })
        add(PermItem("Akses foto/media", "Untuk mendeteksi screenshot baru dan menampilkan pratinjaunya.", has(ctx, mediaPerm), false) {
            launcher.launch(mediaPerm)
        })
        add(PermItem("Akses Jangan Ganggu", "Opsional: Mode Fokus juga menyalakan DND sistem (mode prioritas).",
            nm.isNotificationPolicyAccessGranted, false) { open(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS) })
        add(PermItem("Abaikan optimasi baterai", "Opsional: mencegah sistem mematikan service di latar belakang.",
            pm.isIgnoringBatteryOptimizations(ctx.packageName), false) { open(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS) })
    }

    // Auto-start service bila diizinkan
    LaunchedEffect(overlayOk, local, tick) {
        if (Prefs.enabled && overlayOk && !IslandService.running) IslandService.start(ctx)
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Dynamic Island", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text("Port Android dari Dynamic Island untuk Windows (v8.3).", color = Color.Gray, fontSize = 13.sp)

        SectionCard("Status") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (running) "Berjalan" else "Berhenti", fontWeight = FontWeight.SemiBold)
                    Text("Aktifkan kapsul overlay", fontSize = 12.sp, color = Color.Gray)
                }
                Switch(checked = Prefs.enabled, onCheckedChange = {
                    Prefs.enabled = it; Prefs.save()
                    if (it) { if (overlayOk) IslandService.start(ctx) else Toast.makeText(ctx, "Berikan izin overlay dulu", Toast.LENGTH_SHORT).show() }
                    else IslandService.stop(ctx)
                })
            }
        }

        SectionCard("Izin") {
            perms.forEach { p ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(if (p.granted) Color(0xFF30D158) else if (p.required) Color(0xFFFF453A) else Color(0xFFFF9F0A)))
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(p.title + if (p.required) " *" else "", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text(p.desc, fontSize = 11.sp, color = Color.Gray)
                    }
                    if (!p.granted) OutlinedButton(onClick = p.action) { Text("Berikan") } else Text("OK", color = Color(0xFF30D158), fontSize = 12.sp)
                }
            }
            Text("* wajib", fontSize = 10.sp, color = Color.Gray)
        }

        SectionCard("Tampilan kapsul") {
            SliderRow("Ukuran kapsul", "${(Prefs.scale * 100).toInt()}%", Prefs.scale * 100, 60f..140f) { Prefs.scale = it / 100f }
            SliderRow("Posisi vertikal", "${Prefs.yOffset.toInt()} dp", Prefs.yOffset, 0f..80f) { Prefs.yOffset = it }
            SliderRow("Celah kamera (idle & musik)", "${Prefs.cutoutGap.toInt()} dp", Prefs.cutoutGap, 0f..90f) { Prefs.cutoutGap = it }
            Text("Atur posisi vertikal & celah kamera sampai pas dengan lubang kamera depan.", fontSize = 11.sp, color = Color.Gray)
            Text("Warna latar", fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                colorPresets.forEach { c ->
                    Box(
                        Modifier.size(34.dp).clip(CircleShape).background(Color(c))
                            .border(if (Prefs.bg == c) 2.dp else 1.dp, if (Prefs.bg == c) Color(0xFF0A84FF) else Color.Gray, CircleShape)
                            .clickableNoRipple { Prefs.bg = c; Prefs.save() },
                    )
                }
            }
            HexField()
        }

        SectionCard("Perilaku") {
            SwitchRow("Sembunyikan otomatis saat tidak dipakai", Prefs.autoHide) { Prefs.autoHide = it; Prefs.save() }
            if (Prefs.autoHide) SliderRow("Jeda sembunyi", "${Prefs.hideDelay} dtk", Prefs.hideDelay.toFloat(), 2f..15f) { Prefs.hideDelay = it.toInt() }
            SwitchRow("Kontrol musik untuk semua aplikasi media", Prefs.detectAudio) { Prefs.detectAudio = it; Prefs.save() }
            SwitchRow("Cover lagu bulat & berputar", Prefs.coverRound) { Prefs.coverRound = it; Prefs.save() }
            SwitchRow("Otomatis DND saat fullscreen / game", Prefs.autoDnd) { Prefs.autoDnd = it; Prefs.save() }
            SwitchRow("Tampilkan cuaca di kapsul", Prefs.showWeather) { Prefs.showWeather = it; Prefs.save() }
        }

        SectionCard("Uji coba") {
            val c = IslandBus.controller
            fun need(block: IslandController.() -> Unit) {
                if (c == null) Toast.makeText(ctx, "Aktifkan Dynamic Island dulu", Toast.LENGTH_SHORT).show() else c.block()
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { need { demoMusic() } }) { Text("Musik", fontSize = 12.sp) }
                OutlinedButton(onClick = { need { startCharge(78) } }) { Text("Pengisian", fontSize = 12.sp) }
                OutlinedButton(onClick = { need { toggleDnd() } }) { Text("Fokus", fontSize = 12.sp) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { need { demoNotif(true) } }) { Text("Notif WhatsApp (bisa dibalas)", fontSize = 12.sp) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { need { demoNotif(false) } }) { Text("Notif Gmail (tidak bisa dibalas)", fontSize = 12.sp) }
            }
        }

        Button(onClick = { Prefs.reset() }, modifier = Modifier.fillMaxWidth()) { Text("Reset pengaturan") }
        Text("Versi 8.3.0-android", color = Color.Gray, fontSize = 11.sp)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier = this.tap(onClick)

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C1E)), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title.uppercase(), fontSize = 11.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontSize = 14.sp)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SliderRow(label: String, valueText: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column {
        Row { Text(label, Modifier.weight(1f), fontSize = 13.sp); Text(valueText, fontSize = 13.sp, color = Color.Gray) }
        Slider(value = value.coerceIn(range), onValueChange = onChange, onValueChangeFinished = { Prefs.save() }, valueRange = range)
    }
}

@Composable
private fun HexField() {
    var text by remember { mutableStateOf("#%06X".format(Prefs.bg and 0xFFFFFF)) }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            val h = it.trim().removePrefix("#")
            if (h.length == 6) h.toIntOrNull(16)?.let { rgb -> Prefs.bg = (0xFF shl 24) or rgb; Prefs.save() }
        },
        label = { Text("Hex (#RRGGBB)") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}
