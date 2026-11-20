package com.dynamicisland.android.service

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.usage.UsageStatsManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.graphics.PixelFormat
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.dynamicisland.android.R
import com.dynamicisland.android.data.IslandStateHolder
import com.dynamicisland.android.data.SettingsRepository
import com.dynamicisland.android.model.HudKind
import com.dynamicisland.android.network.RemoteFetchers
import com.dynamicisland.android.ui.DynamicIslandRoot
import com.dynamicisland.android.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground Service yang mengelola WindowManager (TYPE_APPLICATION_OVERLAY),
 * serta memantau Baterai, Volume, Kecerahan Layar, Bluetooth, Clipboard, Cuaca, dan Auto-DND.
 */
class OverlayService : LifecycleService(), SavedStateRegistryOwner {

    private val savedStateController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var windowManager: WindowManager
    private var composeView: ComposeView? = null
    private lateinit var layoutParams: WindowManager.LayoutParams

    private lateinit var settingsRepo: SettingsRepository
    private lateinit var audioManager: AudioManager
    private lateinit var clipboardManager: ClipboardManager

    private var lastChargingState: Boolean? = null
    private var lastVolume = -1
    private var weatherJob: Job? = null
    private var focusMonitorJob: Job? = null

    private val systemReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_BATTERY_CHANGED -> {
                    val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                    val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL
                    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                    val pct = if (level >= 0 && scale > 0) (level * 100 / scale) else 80

                    val prev = lastChargingState
                    lastChargingState = isCharging
                    if (prev != null && isCharging && !prev) {
                        IslandStateHolder.showCharging(pct)
                    } else if (prev != null && !isCharging && prev) {
                        IslandStateHolder.hideCharging()
                    }
                }
                Intent.ACTION_POWER_CONNECTED -> {
                    val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                    val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).coerceIn(0, 100)
                    IslandStateHolder.showCharging(pct)
                }
                Intent.ACTION_POWER_DISCONNECTED -> {
                    IslandStateHolder.hideCharging()
                }
                "android.media.VOLUME_CHANGED_ACTION" -> {
                    val cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                    if (lastVolume != -1 && cur != lastVolume) {
                        val frac = cur.toFloat() / max.toFloat()
                        val muted = cur == 0
                        IslandStateHolder.showHud(
                            kind = HudKind.VOLUME,
                            value = frac,
                            title = "Volume",
                            subtitle = if (muted) "Dibisukan" else "${(frac * 100).toInt()}%",
                            muted = muted
                        )
                    }
                    lastVolume = cur
                }
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    val name = try {
                        device?.name ?: "Perangkat Bluetooth"
                    } catch (_: SecurityException) {
                        "Perangkat Bluetooth"
                    }
                    val batteryPct = readBluetoothBattery(device)
                    IslandStateHolder.showHud(
                        kind = HudKind.BLUETOOTH,
                        value = (batteryPct ?: 100) / 100f,
                        title = name,
                        subtitle = if (batteryPct != null) "Baterai $batteryPct%" else "Terhubung"
                    )
                }
            }
        }
    }

    private val brightnessObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            try {
                val raw = Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS)
                val frac = (raw.coerceIn(0, 255) / 255f)
                IslandStateHolder.showHud(
                    kind = HudKind.BRIGHTNESS,
                    value = frac,
                    title = "Kecerahan Layar",
                    subtitle = "${(frac * 100).toInt()}%"
                )
            } catch (_: Exception) {
            }
        }
    }

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        try {
            val clip = clipboardManager.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0).coerceToText(this)?.toString().orEmpty()
                if (text.isNotBlank()) {
                    IslandStateHolder.addClipboardText(text)
                }
            }
        } catch (_: Exception) {
        }
    }

    override fun onCreate() {
        super.onCreate()
        savedStateController.performRestore(null)
        startForegroundNotification()

        settingsRepo = SettingsRepository(this)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        lastVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

        setupOverlayWindow()
        registerWatchers()

        serviceScope.launch {
            settingsRepo.settingsFlow.collect { cfg ->
                IslandStateHolder.applySettings(cfg)
                updateOverlayPosition(cfg.topMarginDp)
            }
        }
    }

    private fun startForegroundNotification() {
        val channelId = "dynamic_island_overlay"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Dynamic Island Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }

        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_island_notif)
            .setContentTitle("Dynamic Island Aktif")
            .setContentText("Ketuk untuk membuka pengaturan kapsul")
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .build()

        startForeground(1001, notification)
    }

    private fun setupOverlayWindow() {
        if (!Settings.canDrawOverlays(this)) return
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dpToPx(6)
        }

        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@OverlayService)
            setViewTreeSavedStateRegistryOwner(this@OverlayService)
            setContent {
                DynamicIslandRoot(
                    onRequestFocusable = { focusable -> setWindowFocusable(focusable) }
                )
            }
        }
        composeView = view
        windowManager.addView(view, layoutParams)
    }

    private fun setWindowFocusable(focusable: Boolean) {
        val view = composeView ?: return
        val baseFlags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL

        layoutParams.flags = if (focusable) {
            baseFlags
        } else {
            baseFlags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        try {
            windowManager.updateViewLayout(view, layoutParams)
        } catch (_: Exception) {
        }
    }

    private fun updateOverlayPosition(topMarginDp: Int) {
        val view = composeView ?: return
        layoutParams.y = dpToPx(topMarginDp)
        try {
            windowManager.updateViewLayout(view, layoutParams)
        } catch (_: Exception) {
        }
    }

    private fun registerWatchers() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction("android.media.VOLUME_CHANGED_ACTION")
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(systemReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(systemReceiver, filter)
        }

        contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS),
            false,
            brightnessObserver
        )
        clipboardManager.addPrimaryClipChangedListener(clipListener)

        // Fetch cuaca periodik setiap 45 menit (open-meteo.com)
        weatherJob = serviceScope.launch {
            while (isActive) {
                if (IslandStateHolder.settings.value.showWeather) {
                    val res = RemoteFetchers.fetchWeather()
                    if (res != null) {
                        IslandStateHolder.updateWeather(res.first, res.second)
                        delay(45 * 60 * 1000L)
                    } else {
                        delay(60 * 1000L)
                    }
                } else {
                    delay(15 * 1000L)
                }
            }
        }

        // Auto-DND detector saat aplikasi Game / Video Fullscreen aktif
        focusMonitorJob = serviceScope.launch {
            while (isActive) {
                delay(4000L)
                if (IslandStateHolder.settings.value.autoDnd) {
                    IslandStateHolder.setFullscreenBusy(isGameOrFullscreenAppForeground())
                } else {
                    IslandStateHolder.setFullscreenBusy(false)
                }
            }
        }
    }

    @SuppressLint("PrivateApi")
    private fun readBluetoothBattery(device: BluetoothDevice?): Int? {
        if (device == null) return null
        return try {
            val method = device.javaClass.getMethod("getBatteryLevel")
            val level = method.invoke(device) as? Int ?: -1
            if (level in 0..100) level else null
        } catch (_: Exception) {
            null
        }
    }

    private fun isGameOrFullscreenAppForeground(): Boolean {
        return try {
            val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 10_000, now)
            val topPkg = stats?.maxByOrNull { it.lastTimeUsed }?.packageName ?: return false
            if (topPkg == packageName) return false
            val appInfo = packageManager.getApplicationInfo(topPkg, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appInfo.category == android.content.pm.ApplicationInfo.CATEGORY_GAME
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        weatherJob?.cancel()
        focusMonitorJob?.cancel()
        try {
            unregisterReceiver(systemReceiver)
            contentResolver.unregisterContentObserver(brightnessObserver)
            clipboardManager.removePrimaryClipChangedListener(clipListener)
        } catch (_: Exception) {
        }
        composeView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
            }
        }
        super.onDestroy()
    }
}
