package com.dynamicisland

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.ceil

/** Pemilik lifecycle untuk ComposeView yang hidup di luar Activity. */
class OverlayOwner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
    private val registry = LifecycleRegistry(this)
    private val ssc = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = ssc.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = store

    fun create() {
        ssc.performAttach()
        ssc.performRestore(null)
        registry.currentState = Lifecycle.State.CREATED
    }
    fun resume() { registry.currentState = Lifecycle.State.RESUMED }
    fun destroy() { registry.currentState = Lifecycle.State.DESTROYED; store.clear() }
}

class IslandService : Service(), IslandController.Host {

    companion object {
        const val ACTION_STOP = "com.dynamicisland.action.STOP"
        private const val CHANNEL = "island_service"
        private const val NOTIF_ID = 4711
        var running by mutableStateOf(false)

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, IslandService::class.java))
        }
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, IslandService::class.java)) }
    }

    private val S = IslandStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val owner = OverlayOwner()
    private lateinit var wm: WindowManager
    private lateinit var params: WindowManager.LayoutParams
    private var root: FrameLayout? = null
    private lateinit var controller: IslandController
    private lateinit var media: MediaWatcher
    private lateinit var battery: BatteryWatcher
    private lateinit var hud: SystemHudWatcher
    private lateinit var weather: WeatherFetcher
    private lateinit var shots: ScreenshotWatcher
    private lateinit var fullscreen: FullscreenDetector
    private lateinit var clipboard: ClipboardManager
    private var winWDp = 200f
    private var winHDp = 37f

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        try {
            startActivity(Intent(this, ClipboardReaderActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS))
        } catch (_: Exception) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Prefs.enabled = false; Prefs.save()
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        createChannel()
        ServiceCompat.startForeground(
            this, NOTIF_ID, buildNotification(),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        running = true
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        S.screenWidthDp = resources.configuration.screenWidthDp.toFloat()
        DropStore.loadExisting(this)

        controller = IslandController(this, scope, this)
        IslandBus.controller = controller
        controller.start()

        media = MediaWatcher(this, scope) { controller.onTrackChanged(it) }
        controller.media = media
        media.start()

        battery = BatteryWatcher(this, { controller.startCharge(it) }, { controller.onBatteryLevel(it) })
        battery.start()

        hud = SystemHudWatcher(this) { k, v, t, s, m -> controller.showHud(k, v, t, s, m) }
        hud.start()

        weather = WeatherFetcher(this, scope) { t, k -> S.weatherTemp = t; S.weatherKind = k }
        weather.start()

        shots = ScreenshotWatcher(this, scope) { uri, thumb -> controller.onScreenshot(uri, thumb) }
        scope.launch { while (isActive) { shots.tryStart(); delay(5000) } }

        fullscreen = FullscreenDetector(this) { controller.onFullscreen(it) }
        clipboard.addPrimaryClipChangedListener(clipListener)

        scope.launch { snapshotFlow { Prefs.scale to Prefs.yOffset }.collect { applyWindow() } }
        scope.launch { snapshotFlow { Prefs.detectAudio }.collect { media.rescan() } }
        scope.launch { snapshotFlow { Prefs.showWeather }.collect { weather.poke() } }

        // Overlay hanya dipasang bila izin "Tampil di atas aplikasi lain" sudah ada
        scope.launch {
            while (isActive && !Settings.canDrawOverlays(this@IslandService)) delay(1500)
            if (isActive) attachOverlay()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        S.screenWidthDp = newConfig.screenWidthDp.toFloat()
    }

    override fun onDestroy() {
        running = false
        IslandBus.controller = null
        controller.stop()
        media.stop(); battery.stop(); hud.stop(); shots.stop()
        fullscreen.detach()
        try { clipboard.removePrimaryClipChangedListener(clipListener) } catch (_: Exception) {}
        root?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        root = null
        owner.destroy()
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // Overlay
    // ------------------------------------------------------------------
    private fun attachOverlay() {
        if (root != null) return
        val frame = object : FrameLayout(this@IslandService) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                    controller.onBack(); return true
                }
                return super.dispatchKeyEvent(event)
            }
        }
        frame.setViewTreeLifecycleOwner(owner)
        frame.setViewTreeSavedStateRegistryOwner(owner)
        frame.setViewTreeViewModelStoreOwner(owner)
        owner.create(); owner.resume()

        val cv = ComposeView(this).apply {
            setContent { IslandRoot(controller) { w, h -> setWindowDp(w, h) } }
        }
        frame.addView(cv, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        params = WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            // agar kapsul boleh menempati area notch / punch-hole kamera
            layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30)
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            title = "DynamicIsland"
        }
        applyWindow()
        try {
            wm.addView(frame, params)
            root = frame
            fullscreen.attach()
        } catch (_: Exception) {}
    }

    private fun pxPerDp() = resources.displayMetrics.density * Prefs.scale

    private fun setWindowDp(w: Float, h: Float) {
        winWDp = w; winHDp = h
        applyWindow()
    }

    private fun applyWindow() {
        val r = root ?: return
        val d = pxPerDp()
        params.width = ceil(winWDp * d).toInt().coerceAtLeast(1)
        params.height = ceil(winHDp * d).toInt().coerceAtLeast(1)
        params.y = (Prefs.yOffset * resources.displayMetrics.density).toInt()
        try { wm.updateViewLayout(r, params) } catch (_: Exception) {}
    }

    override fun setFocusable(focusable: Boolean) {
        val r = root ?: return
        params.flags = if (focusable) params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        else params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        try { wm.updateViewLayout(r, params) } catch (_: Exception) {}
        if (!focusable) {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(r.windowToken, 0)
        }
    }

    // ------------------------------------------------------------------
    // Notifikasi foreground service
    // ------------------------------------------------------------------
    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Dynamic Island", NotificationManager.IMPORTANCE_MIN).apply {
            description = "Menjaga Dynamic Island tetap aktif"
            setShowBadge(false)
        })
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, IslandService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_island)
            .setContentTitle("Dynamic Island aktif")
            .setContentText("Ketuk untuk membuka pengaturan")
            .setContentIntent(open)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .addAction(0, "Berhenti", stop)
            .build()
    }
}
