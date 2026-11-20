package com.dynamicisland.android.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.dynamicisland.android.data.IslandStateHolder
import com.dynamicisland.android.data.SettingsRepository
import com.dynamicisland.android.model.HudKind
import com.dynamicisland.android.model.IslandSettings
import com.dynamicisland.android.model.MediaState
import com.dynamicisland.android.model.NotifItem
import com.dynamicisland.android.service.IslandNotificationListener
import com.dynamicisland.android.service.OverlayService
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var settingsRepo: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsRepo = SettingsRepository(this)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF0B0B0F)
                ) {
                    SettingsDashboardScreen(settingsRepo)
                }
            }
        }
    }
}

@Composable
private fun SettingsDashboardScreen(repo: SettingsRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by repo.settingsFlow.collectAsState(initial = IslandSettings())

    var hasOverlayPerm by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var hasNotifAccess by remember { mutableStateOf(isNotificationListenerEnabled(context)) }
    var hasPostNotif by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            } else true
        )
    }

    val postNotifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPostNotif = granted
    }

    val btLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    LaunchedEffect(Unit) {
        hasOverlayPerm = Settings.canDrawOverlays(context)
        hasNotifAccess = isNotificationListenerEnabled(context)
    }

    val bgPresets = listOf(
        0xFF000000,
        0xFF1E1E24,
        0xFF0A1836,
        0xFF26123C,
        0xFF082A1E,
        0xFF3C0E16
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Dynamic Island",
            color = Color.White,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Kapsul interaktif di area status bar & kamera depan",
            color = Color(0xFFA0A0A6),
            fontSize = 13.sp
        )

        // Kartu Izin Sistem Wajib
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF16161D)),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Izin Sistem Wajib", color = Color(0xFFFF5A96), fontWeight = FontWeight.Bold, fontSize = 13.sp)

                PermissionRow(
                    title = "Overlay di Atas Aplikasi",
                    granted = hasOverlayPerm,
                    onGrant = {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        )
                        context.startActivity(intent)
                    }
                )

                PermissionRow(
                    title = "Akses Notifikasi & Media",
                    granted = hasNotifAccess,
                    onGrant = {
                        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    }
                )

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    PermissionRow(
                        title = "Izin Notifikasi Service",
                        granted = hasPostNotif,
                        onGrant = {
                            postNotifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    )
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    OutlinedButton(
                        onClick = { btLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Izinkan Deteksi Baterai Bluetooth")
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            hasOverlayPerm = Settings.canDrawOverlays(context)
                            hasNotifAccess = isNotificationListenerEnabled(context)
                            if (hasOverlayPerm) {
                                val svc = Intent(context, OverlayService::class.java)
                                ContextCompat.startForegroundService(context, svc)
                            }
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5A96))
                    ) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Aktifkan Island", fontWeight = FontWeight.Bold)
                    }

                    OutlinedButton(
                        onClick = {
                            context.stopService(Intent(context, OverlayService::class.java))
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Hentikan")
                    }
                }
            }
        }

        // Pengaturan Kapsul (Porting GeneralWindow)
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF16161D)),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Pengaturan Kapsul", color = Color(0xFFFF5A96), fontWeight = FontWeight.Bold, fontSize = 13.sp)

                Column {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Ukuran Kapsul", color = Color.White, fontSize = 14.sp)
                        Text("${(settings.scale * 100).toInt()}%", color = Color(0xFFA0A0A6), fontSize = 13.sp)
                    }
                    Slider(
                        value = settings.scale,
                        onValueChange = { v -> scope.launch { repo.update { it.copy(scale = v) } } },
                        valueRange = 0.6f..1.4f
                    )
                }

                Column {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Posisi Vertikal (Notch Offset)", color = Color.White, fontSize = 14.sp)
                        Text("${settings.topMarginDp} dp", color = Color(0xFFA0A0A6), fontSize = 13.sp)
                    }
                    Slider(
                        value = settings.topMarginDp.toFloat(),
                        onValueChange = { v -> scope.launch { repo.update { it.copy(topMarginDp = v.toInt()) } } },
                        valueRange = 0f..50f
                    )
                }

                Text("Warna Latar Kapsul", color = Color.White, fontSize = 14.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    bgPresets.forEach { argb ->
                        val selected = settings.bgColorArgb == argb
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(Color(argb))
                                .border(
                                    width = if (selected) 2.5.dp else 1.dp,
                                    color = if (selected) Color(0xFFFF5A96) else Color.White.copy(alpha = 0.3f),
                                    shape = CircleShape
                                )
                                .clickable {
                                    scope.launch { repo.update { it.copy(bgColorArgb = argb) } }
                                }
                        )
                    }
                }

                ToggleRow(
                    label = "Sembunyikan otomatis saat idle",
                    checked = settings.autoHide,
                    onCheckedChange = { c -> scope.launch { repo.update { it.copy(autoHide = c) } } }
                )

                if (settings.autoHide) {
                    Column {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Jeda Auto-Hide", color = Color.White, fontSize = 13.sp)
                            Text("${settings.hideDelaySec} detik", color = Color(0xFFA0A0A6), fontSize = 13.sp)
                        }
                        Slider(
                            value = settings.hideDelaySec.toFloat(),
                            onValueChange = { v -> scope.launch { repo.update { it.copy(hideDelaySec = v.toInt()) } } },
                            valueRange = 2f..30f
                        )
                    }
                }

                ToggleRow(
                    label = "Cover lagu bulat & berputar (Vinyl)",
                    checked = settings.coverRound,
                    onCheckedChange = { c -> scope.launch { repo.update { it.copy(coverRound = c) } } }
                )

                ToggleRow(
                    label = "Otomatis DND saat Game / Layar Penuh",
                    checked = settings.autoDnd,
                    onCheckedChange = { c -> scope.launch { repo.update { it.copy(autoDnd = c) } } }
                )

                ToggleRow(
                    label = "Tampilkan cuaca di kapsul (Open-Meteo)",
                    checked = settings.showWeather,
                    onCheckedChange = { c -> scope.launch { repo.update { it.copy(showWeather = c) } } }
                )

                OutlinedButton(
                    onClick = { scope.launch { repo.reset() } },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Atur Ulang ke Bawaan")
                }
            }
        }

        // Panel Uji Coba Interaktif (Simulasi Fitur)
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF16161D)),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Simulasi & Pengujian Kapsul", color = Color(0xFFFF5A96), fontWeight = FontWeight.Bold, fontSize = 13.sp)

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            IslandStateHolder.updateMedia(
                                MediaState(
                                    title = "Blinding Lights",
                                    artist = "The Weeknd",
                                    isPlaying = true,
                                    positionMs = 45_000L,
                                    durationMs = 200_000L
                                )
                            )
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Tes Musik", fontSize = 12.sp)
                    }
                    Button(
                        onClick = { IslandStateHolder.showCharging(85) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Tes Charging", fontSize = 12.sp)
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            IslandStateHolder.enqueueNotification(
                                NotifItem(
                                    key = "demo_wa",
                                    packageName = "com.whatsapp",
                                    appName = "WhatsApp",
                                    title = "Budi",
                                    body = "Halo! Jadi ketemu jam 5 sore ya?",
                                    isDemo = true
                                )
                            )
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Tes Notif WA", fontSize = 12.sp)
                    }
                    Button(
                        onClick = {
                            IslandStateHolder.showHud(HudKind.VOLUME, 0.72f, "Volume", "72%")
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Tes HUD Volume", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionRow(title: String, granted: Boolean, onGrant: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Icon(
                imageVector = if (granted) Icons.Rounded.CheckCircle else Icons.Rounded.Warning,
                contentDescription = null,
                tint = if (granted) Color(0xFF30D158) else Color(0xFFFF9F0A),
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(title, color = Color.White, fontSize = 13.sp)
        }
        if (!granted) {
            TextButton(onClick = onGrant) {
                Text("Izinkan", color = Color(0xFFFF5A96))
            }
        } else {
            Text("Aktif", color = Color(0xFF30D158), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private fun isNotificationListenerEnabled(context: Context): Boolean {
    val cn = ComponentName(context, IslandNotificationListener::class.java)
    val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
    return flat != null && flat.contains(cn.flattenToString())
}
