package com.dynamicisland.android.model

import android.app.PendingIntent
import android.app.RemoteInput
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.graphics.Color

enum class IslandMode {
    IDLE,
    MUSIC,
    PLAYER,
    NOTIF,
    CHARGE,
    HUD,
    REPLY,
    DROP,
    SCREENSHOT,
    HIDDEN
}

enum class HudKind {
    VOLUME,
    BRIGHTNESS,
    BLUETOOTH
}

data class IslandSettings(
    val scale: Float = 1.0f,
    val autoHide: Boolean = false,
    val hideDelaySec: Int = 4,
    val bgColorArgb: Long = 0xFF000000,
    val coverRound: Boolean = false,
    val autoDnd: Boolean = true,
    val showWeather: Boolean = true,
    val topMarginDp: Int = 6
)

data class MediaState(
    val title: String = "",
    val artist: String = "",
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val albumArt: Bitmap? = null,
    val packageName: String = "",
    val lyrics: String = "",
    val isLoadingLyrics: Boolean = false
)

data class NotifItem(
    val key: String,
    val packageName: String,
    val appName: String,
    val title: String,
    val body: String,
    val appIcon: Bitmap? = null,
    val contentIntent: PendingIntent? = null,
    val replyAction: PendingIntent? = null,
    val remoteInput: RemoteInput? = null,
    val isDemo: Boolean = false
) {
    val canReply: Boolean get() = replyAction != null && remoteInput != null
}

data class HudState(
    val kind: HudKind = HudKind.VOLUME,
    val value: Float = 0f,
    val title: String = "",
    val subtitle: String = "",
    val isMuted: Boolean = false
)

data class ChargeState(
    val isCharging: Boolean = false,
    val level: Int = 80
)

data class WeatherState(
    val tempC: Int? = null,
    val kind: String = "cloud" // sun, cloud, rain
)

data class DropItem(
    val id: String,
    val name: String,
    val uri: Uri,
    val mimeType: String
)

data class ScreenshotPreview(
    val bitmap: Bitmap,
    val label: String
)

object AppColorHelper {
    fun colorForPackage(pkg: String, appName: String): Color {
        val key = "$pkg $appName".lowercase()
        return when {
            "whatsapp" in key -> Color(0xFF25D366)
            "telegram" in key -> Color(0xFF2AABEE)
            "gmail" in key || "mail" in key -> Color(0xFFEA4335)
            "instagram" in key -> Color(0xFFE1306C)
            "discord" in key -> Color(0xFF5865F2)
            "spotify" in key -> Color(0xFF1ED760)
            "youtube" in key -> Color(0xFFFF0033)
            "chrome" in key -> Color(0xFF4285F4)
            else -> Color(0xFF6E768C)
        }
    }
}
