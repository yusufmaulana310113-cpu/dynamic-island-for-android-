package com.dynamicisland

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.PixelFormat
import android.location.LocationManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private fun registerSys(ctx: Context, r: BroadcastReceiver, f: IntentFilter) {
    ContextCompat.registerReceiver(ctx, r, f, ContextCompat.RECEIVER_EXPORTED)
}

// =============================================================================
// Baterai & pengisian daya  (setara _read_power / _poll_power)
// =============================================================================
class BatteryWatcher(
    private val ctx: Context,
    private val onPlugged: (Int) -> Unit,
    private val onLevel: (Int) -> Unit,
) {
    private var plugged: Boolean? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_POWER_CONNECTED -> setPlugged(true, level(i))
                Intent.ACTION_POWER_DISCONNECTED -> plugged = false
                Intent.ACTION_BATTERY_CHANGED -> {
                    val pct = level(i)
                    setPlugged(i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0, pct)
                    if (pct >= 0) onLevel(pct)
                }
            }
        }
    }

    private fun level(i: Intent?): Int {
        val l = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val s = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        return if (l < 0 || s <= 0) -1 else l * 100 / s
    }

    private fun setPlugged(now: Boolean, pct: Int) {
        val prev = plugged
        plugged = now
        if (prev == false && now) onPlugged(if (pct >= 0) pct else 0)
    }

    fun start() {
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        val sticky = ctx.registerReceiver(null, f)
        plugged = (sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        registerSys(ctx, receiver, f)
    }

    fun stop() { try { ctx.unregisterReceiver(receiver) } catch (_: Exception) {} }
}

// =============================================================================
// System HUD: volume, kecerahan, baterai Bluetooth  (setara SystemHudWatcher)
// =============================================================================
class SystemHudWatcher(
    private val ctx: Context,
    private val onHud: (HudKind, Float, String, String, Boolean) -> Unit,   // kind, value, title, subtitle, muted
) {
    private val handler = Handler(Looper.getMainLooper())
    private val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val lastVol = HashMap<Int, Int>()
    private val lastMute = HashMap<Int, Boolean>()
    private var lastBright = -1
    private val btLevels = HashMap<String, Int>()
    private val streams = intArrayOf(AudioManager.STREAM_MUSIC, AudioManager.STREAM_RING, AudioManager.STREAM_ALARM, AudioManager.STREAM_VOICE_CALL)

    private fun label(s: Int) = when (s) {
        AudioManager.STREAM_RING -> "Dering"
        AudioManager.STREAM_ALARM -> "Alarm"
        AudioManager.STREAM_VOICE_CALL -> "Panggilan"
        else -> "Volume"
    }

    private fun checkStream(s: Int, prime: Boolean = false) {
        val v = am.getStreamVolume(s)
        val max = am.getStreamMaxVolume(s).coerceAtLeast(1)
        val muted = am.isStreamMute(s) || v == 0
        val changed = lastVol[s] != v || lastMute[s] != muted
        lastVol[s] = v; lastMute[s] = muted
        if (prime || !changed) return
        onHud(HudKind.VOLUME, v.toFloat() / max, label(s), if (muted) "Dibisukan" else "${v * 100 / max}%", muted)
    }

    private val volReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val s = i.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1)
            if (s in streams) checkStream(s) else if (i.action == "android.media.STREAM_MUTE_CHANGED_ACTION") streams.forEach { checkStream(it) }
        }
    }

    private val brightObs = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) = checkBrightness(false)
    }

    private fun checkBrightness(prime: Boolean) {
        val b = Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, -1)
        if (b < 0 || b == lastBright) return
        val first = lastBright < 0
        lastBright = b
        if (prime || first) return
        val v = (b / 255f).coerceIn(0f, 1f)
        onHud(HudKind.BRIGHTNESS, v, "Kecerahan layar", "${(v * 100).toInt()}%", false)
    }

    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val dev = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            else @Suppress("DEPRECATION") i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
            dev ?: return
            val id = try { dev.address } catch (_: SecurityException) { "bt" }
            val name = try { dev.name } catch (_: SecurityException) { null } ?: "Perangkat Bluetooth"
            when (i.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    announce(dev, id, name, -1)
                    handler.postDelayed({ // baterai sering baru tersedia beberapa detik setelah terhubung
                        val lv = batteryOf(dev)
                        if (lv >= 0 && btLevels[id] != lv) announce(dev, id, name, lv)
                    }, 3000)
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    btLevels.remove(id)
                    onHud(HudKind.BLUETOOTH, 0f, name, "Terputus", false)
                }
                ACTION_BT_BATTERY -> {
                    val lv = i.getIntExtra(EXTRA_BT_BATTERY, -1)
                    if (lv in 0..100 && btLevels[id] != lv) announce(dev, id, name, lv)
                }
            }
        }
    }

    private fun batteryOf(dev: BluetoothDevice): Int = try {
        // getBatteryLevel() adalah API tersembunyi; gagal dengan aman bila tidak tersedia
        BluetoothDevice::class.java.getMethod("getBatteryLevel").invoke(dev) as Int
    } catch (_: Throwable) { -1 }

    private fun announce(dev: BluetoothDevice, id: String, name: String, known: Int) {
        val lv = if (known >= 0) known else batteryOf(dev)
        if (lv in 0..100) {
            btLevels[id] = lv
            onHud(HudKind.BLUETOOTH, lv / 100f, name, "$lv%", false)
        } else {
            onHud(HudKind.BLUETOOTH, 1f, name, "Terhubung", false)
        }
    }

    fun start() {
        streams.forEach { checkStream(it, prime = true) }
        checkBrightness(prime = true)
        registerSys(ctx, volReceiver, IntentFilter().apply {
            addAction("android.media.VOLUME_CHANGED_ACTION")
            addAction("android.media.STREAM_MUTE_CHANGED_ACTION")
        })
        ctx.contentResolver.registerContentObserver(Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS), false, brightObs)
        ctx.contentResolver.registerContentObserver(Settings.System.getUriFor("screen_brightness_float"), false, brightObs)
        registerSys(ctx, btReceiver, IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(ACTION_BT_BATTERY)
        })
    }

    fun stop() {
        try { ctx.unregisterReceiver(volReceiver) } catch (_: Exception) {}
        try { ctx.unregisterReceiver(btReceiver) } catch (_: Exception) {}
        ctx.contentResolver.unregisterContentObserver(brightObs)
    }

    companion object {
        const val ACTION_BT_BATTERY = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"
        const val EXTRA_BT_BATTERY = "android.bluetooth.device.extra.BATTERY_LEVEL"
    }
}

// =============================================================================
// Screenshot: mendeteksi screenshot baru (MediaStore) -> state "screenshot"
// =============================================================================
class ScreenshotWatcher(
    private val ctx: Context,
    private val scope: CoroutineScope,
    private val onShot: (Uri, ImageBitmap?) -> Unit,
) {
    private var started = false
    private var lastId = -1L
    private val imagesUri: Uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI

    private val obs = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            scope.launch(Dispatchers.IO) { delay(700); check() }
        }
    }

    private fun hasPermission(): Boolean {
        val p = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
        return ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    }

    /** Aman dipanggil berulang; baru aktif setelah izin media diberikan. */
    fun tryStart() {
        if (started || !hasPermission()) return
        started = true
        lastId = latest()?.first ?: -1L
        ctx.contentResolver.registerContentObserver(imagesUri, true, obs)
    }

    fun stop() { if (started) ctx.contentResolver.unregisterContentObserver(obs); started = false }

    private fun latest(): Pair<Long, Long>? {   // (id, dateAdded) dari screenshot terbaru
        val args = Bundle().apply {
            putStringArray(android.content.ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.Images.Media.DATE_ADDED))
            putInt(android.content.ContentResolver.QUERY_ARG_SORT_DIRECTION, android.content.ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
            putInt(android.content.ContentResolver.QUERY_ARG_LIMIT, 6)
        }
        val proj = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.RELATIVE_PATH, MediaStore.Images.Media.DATE_ADDED)
        try {
            ctx.contentResolver.query(imagesUri, proj, args, null)?.use { c ->
                while (c.moveToNext()) {
                    val hay = ((c.getString(1) ?: "") + " " + (c.getString(2) ?: "")).lowercase()
                    if (hay.contains("screenshot") || hay.contains("screen_shot") || hay.contains("tangkapan")) {
                        return c.getLong(0) to c.getLong(3)
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }

    private suspend fun check() {
        val (id, added) = latest() ?: return
        if (id == lastId) return
        lastId = id
        if (System.currentTimeMillis() / 1000 - added > 20) return
        val uri = ContentUris.withAppendedId(imagesUri, id)
        val thumb = try { ctx.contentResolver.loadThumbnail(uri, Size(320, 320), null).asImageBitmap() } catch (_: Exception) { null }
        withContext(Dispatchers.Main) { onShot(uri, thumb) }
    }
}

// =============================================================================
// Deteksi fullscreen (Mode Fokus / Auto-DND)
// Overlay 1px setinggi layar: bila status bar disembunyikan aplikasi lain (game, video,
// mode imersif), inset status bar menjadi 0. Ini heuristik, bukan API resmi.
// =============================================================================
class FullscreenDetector(private val ctx: Context, private val onChange: (Boolean) -> Unit) {
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var view: View? = null
    private var last = false
    private var pending: Runnable? = null

    fun attach() {
        if (view != null) return
        val v = object : View(ctx) {
            override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
                evaluate(insets)
                return super.onApplyWindowInsets(insets)
            }
        }
        val lp = WindowManager.LayoutParams(
            1, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        try { wm.addView(v, lp); view = v } catch (_: Exception) {}
    }

    fun detach() {
        pending?.let { handler.removeCallbacks(it) }
        view?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        view = null
    }

    private fun evaluate(insets: WindowInsets) {
        val top = if (Build.VERSION.SDK_INT >= 30) insets.getInsets(WindowInsets.Type.statusBars()).top
        else @Suppress("DEPRECATION") insets.systemWindowInsetTop
        val fs = top == 0
        pending?.let { handler.removeCallbacks(it) }
        val r = Runnable { if (fs != last) { last = fs; onChange(fs) } }
        pending = r
        handler.postDelayed(r, 600)   // debounce: hindari salah deteksi saat rotasi/transisi
    }
}

// =============================================================================
// Cuaca  (setara WeatherFetcher; coroutine periodik, tidur total di antara fetch)
// =============================================================================
class WeatherFetcher(
    private val ctx: Context,
    private val scope: CoroutineScope,
    private val onData: (Int, String) -> Unit,
) {
    private val wake = Channel<Unit>(Channel.CONFLATED)

    fun poke() { wake.trySend(Unit) }

    fun start() {
        scope.launch {
            while (isActive) {
                var wait = C.WEATHER_MS
                if (Prefs.showWeather) {
                    wait = try {
                        val (t, k) = withContext(Dispatchers.IO) { val (la, lo) = locate(); Net.weather(la, lo) }
                        onData(t, k)
                        C.WEATHER_MS
                    } catch (_: Exception) { C.WEATHER_RETRY_MS }
                }
                withTimeoutOrNull(wait) { wake.receive() }
            }
        }
    }

    private fun locate(): Pair<Double, Double> {
        if (ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            try {
                val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                for (p in listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)) {
                    lm.getLastKnownLocation(p)?.let { return it.latitude to it.longitude }
                }
            } catch (_: Exception) {}
        }
        return Net.ipLocation()
    }
}
