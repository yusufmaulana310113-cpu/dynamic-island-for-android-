package com.dynamicisland

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.palette.graphics.Palette
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Mendeteksi sesi media aktif (Spotify, YouTube Music, dst.) lewat MediaSessionManager.
 * Membutuhkan NotificationListener yang sudah diaktifkan pengguna; bila belum, otomatis dicoba ulang.
 */
class MediaWatcher(
    private val ctx: Context,
    private val scope: CoroutineScope,
    private val onTrack: (newTrack: Boolean) -> Unit,
) {
    private val S = IslandStore
    private val msm = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    private val cn = ComponentName(ctx, IslandNotificationListener::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var registered = false
    private var controller: MediaController? = null
    private var lastKey = ""
    private var lastBitmap: Bitmap? = null
    private var coverJob: Job? = null

    private val musicPackages = setOf(
        "com.spotify.music", "com.google.android.apps.youtube.music", "com.apple.android.music",
        "deezer.android.app", "com.soundcloud.android", "com.amazon.mp3", "com.aspiro.tidal",
        "com.google.android.music", "com.jio.media.jiobeats", "com.shazam.android",
    )

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        pick(list ?: emptyList())
    }

    private val cb = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = readAll()
        override fun onPlaybackStateChanged(state: PlaybackState?) = readAll()
        override fun onSessionDestroyed() = rescan()
    }

    fun start() {
        scope.launch {
            while (isActive) {
                if (!registered) tryRegister()
                delay(4000)
            }
        }
    }

    fun stop() {
        try { msm.removeOnActiveSessionsChangedListener(sessionsListener) } catch (_: Exception) {}
        controller?.unregisterCallback(cb)
        controller = null
        registered = false
    }

    private fun tryRegister() {
        try {
            msm.addOnActiveSessionsChangedListener(sessionsListener, cn, handler)
            registered = true
            pick(msm.getActiveSessions(cn))
        } catch (_: SecurityException) {
            // izin Notification Listener belum diberikan -> coba lagi nanti
        }
    }

    fun rescan() {
        if (!registered) return
        try { pick(msm.getActiveSessions(cn)) } catch (_: SecurityException) {}
    }

    private fun allowed(c: MediaController) =
        c.packageName != ctx.packageName && (Prefs.detectAudio || c.packageName in musicPackages)

    private fun pick(list: List<MediaController>) {
        val ok = list.filter { allowed(it) }
        val chosen = ok.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: ok.firstOrNull()
        if (chosen?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(cb)
            controller = chosen
            chosen?.registerCallback(cb, handler)
        }
        readAll()
    }

    private fun readAll() {
        val c = controller
        if (c == null) {
            if (!S.demo) {
                S.hasTrack = false; S.playing = false
                onTrack(false)
            }
            return
        }
        S.demo = false
        val m = c.metadata
        val st = c.playbackState
        val title = (m?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: m?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)).orEmpty()
        val artist = (m?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: m?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: m?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)).orEmpty()
        val state = st?.state ?: PlaybackState.STATE_NONE
        if (title.isBlank() || state == PlaybackState.STATE_NONE || state == PlaybackState.STATE_STOPPED) {
            S.hasTrack = false; S.playing = false
            onTrack(false)
            return
        }
        val playing = state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING
        val speed = st?.playbackSpeed?.takeIf { it > 0f } ?: 1f
        var pos = st?.position ?: 0L
        if (st != null && state == PlaybackState.STATE_PLAYING && st.lastPositionUpdateTime > 0) {
            pos += ((SystemClock.elapsedRealtime() - st.lastPositionUpdateTime) * speed).toLong()
        }
        val dur = m?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val key = "$title|$artist"
        val isNew = key != lastKey || !S.hasTrack

        S.title = title; S.artist = artist
        S.playing = playing
        S.durMs = if (dur > 0) dur else 0L
        S.posMs = if (dur > 0) pos.coerceIn(0, dur) else pos.coerceAtLeast(0)
        S.stampMs = SystemClock.elapsedRealtime()
        S.speed = speed
        S.canSeek = st != null && (st.actions and PlaybackState.ACTION_SEEK_TO) != 0L && dur > 0
        S.hasTrack = true

        val bmp = m?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: m?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: m?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        if (isNew) {
            lastKey = key
            S.art = null
            lastBitmap = null
        }
        if (bmp != null) {
            if (bmp !== lastBitmap) setArt(bmp)
        } else if (isNew) {
            val uri = listOf(
                MediaMetadata.METADATA_KEY_ALBUM_ART_URI, MediaMetadata.METADATA_KEY_ART_URI,
                MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI,
            ).firstNotNullOfOrNull { m?.getString(it) }
            loadFallbackCover(title, artist, uri)
        }
        onTrack(isNew)
    }

    private fun setArt(bmp: Bitmap) {
        lastBitmap = bmp
        S.art = bmp.asImageBitmap()
        scope.launch(Dispatchers.Default) {
            val soft = if (bmp.config == Bitmap.Config.HARDWARE) bmp.copy(Bitmap.Config.ARGB_8888, false) else bmp
            val p = Palette.from(soft).maximumColorCount(16).generate()
            val col = p.getVibrantColor(p.getMutedColor(p.getDominantColor(0xFFFF5F7D.toInt())))
            withContext(Dispatchers.Main) { S.accent = Color(col) }
        }
    }

    private fun loadFallbackCover(title: String, artist: String, uri: String?) {
        coverJob?.cancel()
        coverJob = scope.launch {
            val bmp = withContext(Dispatchers.IO) {
                var b: Bitmap? = null
                try {
                    if (uri != null) {
                        b = if (uri.startsWith("http")) Net.fetchBitmap(uri)
                        else ctx.contentResolver.openInputStream(Uri.parse(uri))?.use { android.graphics.BitmapFactory.decodeStream(it) }
                    }
                } catch (_: Exception) {}
                b ?: Net.fetchCover(title, artist)
            }
            if (bmp != null && S.title == title) setArt(bmp)
        }
    }

    // ---------------- Kontrol ----------------
    fun playPause() { controller?.transportControls?.let { if (S.playing) it.pause() else it.play() } }
    fun next() { controller?.transportControls?.skipToNext() }
    fun prev() { controller?.transportControls?.skipToPrevious() }
    fun seekTo(ms: Long) { controller?.transportControls?.seekTo(ms) }
    fun openApp() = controller?.sessionActivity
}
