package com.dynamicisland

import android.app.Notification
import android.app.PendingIntent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import java.io.File
import kotlin.math.max

/** Konstanta (setara blok "Pengaturan Konstanta" di versi Python). */
object C {
    const val AUTO_SHRINK_MS = 5000L
    const val SONG_POPUP_MS = 4500L
    const val NOTIF_MS = 5500L
    const val CHARGE_MS = 4500L
    const val HUD_MS = 1600L
    const val DROP_MS = 7000L
    const val DROP_AFTER_MS = 2500L
    const val SHOT_MS = 3500L
    const val REPLY_IDLE_MS = 30000L
    const val REPLY_RESULT_MS = 1500L
    const val WEATHER_MS = 45 * 60 * 1000L
    const val WEATHER_RETRY_MS = 60_000L
    const val CLIP_MAX = 5
    const val CLIP_MAX_CHARS = 20000
    const val MAX_DROP_FILES = 10
    const val DROP_TEXT_MAX = 200000
    const val DROP_FILE_MAX = 50L * 1024 * 1024
    const val SPRING_DAMPING = 0.70f
    const val SPRING_STIFFNESS = 190f   // response 0.46s  ->  (2*pi/0.46)^2
    const val MAX_CORNER = 40f
}

/** Ukuran dalam dp (sama dengan SIZES di versi Python). */
enum class Mode(val w: Float, val h: Float) {
    IDLE(200f, 37f), MUSIC(272f, 37f), PLAYER(380f, 152f), NOTIF(380f, 78f),
    CHARGE(300f, 56f), HUD(360f, 84f), HIDDEN(72f, 10f), REPLY(380f, 124f),
    DROP(340f, 96f), SCREENSHOT(320f, 76f), LYRICS(380f, 260f), CLIP(380f, 230f)
}

enum class HudKind { VOLUME, BRIGHTNESS, BLUETOOTH, FOCUS }

class NotifItem(
    val key: String,
    val pkg: String,
    val app: String,
    val title: String,
    val body: String,
    val icon: ImageBitmap?,
    val contentIntent: PendingIntent?,
    val replyAction: Notification.Action?,
    val demo: Boolean = false,
    val canReply: Boolean = replyAction != null,
)

class DropFile(val file: File, val name: String, val mime: String)

/** State global yang dibaca oleh Compose. Semua penulisan dilakukan di main thread. */
object IslandStore {
    // --- layar & mode
    var screenWidthDp by mutableFloatStateOf(360f)
    var hidden by mutableStateOf(false)
    var fsBusy by mutableStateOf(false)
    var dndManual by mutableStateOf(false)
    val dndActive: Boolean get() = dndManual || (Prefs.autoDnd && fsBusy)

    // --- media
    var demo by mutableStateOf(false)
    var hasTrack by mutableStateOf(false)
    var playing by mutableStateOf(false)
    var title by mutableStateOf("")
    var artist by mutableStateOf("")
    var art by mutableStateOf<ImageBitmap?>(null)
    var accent by mutableStateOf(Color(0xFFFF5F7D))
    var posMs by mutableLongStateOf(0L)
    var durMs by mutableLongStateOf(0L)
    var stampMs by mutableLongStateOf(0L)
    var speed by mutableFloatStateOf(1f)
    var canSeek by mutableStateOf(false)
    var playerOpen by mutableStateOf(false)
    var songPopup by mutableStateOf(false)
    var lyricsOpen by mutableStateOf(false)
    var lyrics by mutableStateOf("")

    // --- notifikasi & balas
    var notif by mutableStateOf<NotifItem?>(null)
    var lastNotif: NotifItem? = null
    var reply by mutableStateOf<NotifItem?>(null)
    var lastReply: NotifItem? = null
    var replyStatus by mutableStateOf("")   // "", sending, ok, fail
    var replyMsg by mutableStateOf("")

    // --- pengisian daya
    var charging by mutableStateOf(false)
    var chargePct by mutableIntStateOf(0)

    // --- HUD
    var hudKind by mutableStateOf<HudKind?>(null)
    var lastHudKind: HudKind = HudKind.VOLUME
    var hudValue by mutableFloatStateOf(0f)
    var hudTitle by mutableStateOf("")
    var hudSub by mutableStateOf("")
    var hudMuted by mutableStateOf(false)

    // --- cuaca
    var weatherTemp by mutableStateOf<Int?>(null)
    var weatherKind by mutableStateOf("cloud")

    // --- drop shelf, clipboard, screenshot
    val dropFiles = mutableStateListOf<DropFile>()
    var dropOpen by mutableStateOf(false)
    val clip = mutableStateListOf<String>()
    var clipOpen by mutableStateOf(false)
    var shotUri by mutableStateOf<android.net.Uri?>(null)
    var shotThumb by mutableStateOf<ImageBitmap?>(null)

    val dropVisible: Boolean get() = dropOpen && dropFiles.isNotEmpty()

    /** Prioritas: reply > screenshot > notif > drop > hud > charge > lyrics > clip > player > music > idle */
    val baseMode: Mode
        get() = when {
            reply != null -> Mode.REPLY
            shotUri != null -> Mode.SCREENSHOT
            notif != null -> Mode.NOTIF
            dropVisible -> Mode.DROP
            hudKind != null -> Mode.HUD
            charging -> Mode.CHARGE
            lyricsOpen && hasTrack -> Mode.LYRICS
            clipOpen -> Mode.CLIP
            hasTrack && (playerOpen || songPopup) -> Mode.PLAYER
            hasTrack -> Mode.MUSIC
            else -> Mode.IDLE
        }

    val mode: Mode
        get() {
            val b = baseMode
            val quiet = b == Mode.IDLE || b == Mode.MUSIC
            return if (quiet && ((fsBusy && Prefs.autoDnd) || (Prefs.autoHide && hidden))) Mode.HIDDEN else b
        }
}

/** Lebar kapsul idle: sisi kiri (jam) & kanan (cuaca/badge) dibuat simetris agar celah kamera di tengah. */
fun idleWidth(): Float {
    val badges = (if (IslandStore.dropFiles.isNotEmpty()) 1 else 0) +
        (if (IslandStore.clip.isNotEmpty()) 1 else 0) +
        (if (IslandStore.dndActive) 1 else 0)
    val right = (if (Prefs.showWeather) 44f else 0f) + badges * 26f
    val side = max(56f, right)
    return max(120f, 28f + 2f * side + Prefs.cutoutGap)
}

fun notifLines(n: NotifItem?): Int = if (n != null && n.body.length > 44) 2 else 1
fun notifHeight(): Float = if (notifLines(IslandStore.notif ?: IslandStore.lastNotif) > 1) 94f else Mode.NOTIF.h

/** Jembatan antar-komponen (listener, activity) ke controller; semua di satu proses. */
object IslandBus {
    @Volatile var controller: IslandController? = null
    @Volatile var onNotification: ((NotifItem) -> Unit)? = null
    @Volatile var onNotificationRemoved: ((String) -> Unit)? = null
    @Volatile var onClipText: ((String) -> Unit)? = null
    @Volatile var onDropAdded: (() -> Unit)? = null
}
