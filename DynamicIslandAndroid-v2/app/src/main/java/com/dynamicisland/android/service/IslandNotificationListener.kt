package com.dynamicisland.android.service

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.dynamicisland.android.data.IslandStateHolder
import com.dynamicisland.android.model.MediaState
import com.dynamicisland.android.model.NotifItem
import com.dynamicisland.android.network.RemoteFetchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Menggabungkan NotificationWatcher, ReplySender (RemoteInput), dan MediaWatcher (MediaSessionManager).
 */
class IslandNotificationListener : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var mediaSessionManager: MediaSessionManager? = null
    private var activeController: MediaController? = null
    private var positionTickerJob: Job? = null

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        bindBestMediaController(controllers)
    }

    private val mediaCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            syncMediaController(activeController)
        }

        override fun onMetadataChanged(metadata: MediaMetadata?) {
            syncMediaController(activeController)
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        mediaSessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        val component = ComponentName(this, IslandNotificationListener::class.java)
        try {
            mediaSessionManager?.addOnActiveSessionsChangedListener(sessionsListener, component)
            bindBestMediaController(mediaSessionManager?.getActiveSessions(component))
        } catch (_: SecurityException) {
        }

        IslandStateHolder.mediaActionCallback = { action, seekMs ->
            handleMediaAction(action, seekMs)
        }

        IslandStateHolder.fetchLyricsCallback = { title, artist ->
            scope.launch {
                IslandStateHolder.setLyricsLoading(true)
                val lyrics = RemoteFetchers.fetchLyrics(title, artist)
                IslandStateHolder.updateLyrics(title, artist, lyrics)
            }
        }

        positionTickerJob = scope.launch {
            while (isActive) {
                delay(1000)
                val ctrl = activeController
                if (ctrl != null && ctrl.playbackState?.state == PlaybackState.STATE_PLAYING) {
                    tickPosition(ctrl)
                }
            }
        }
    }

    override fun onListenerDisconnected() {
        positionTickerJob?.cancel()
        activeController?.unregisterCallback(mediaCallback)
        activeController = null
        try {
            mediaSessionManager?.removeOnActiveSessionsChangedListener(sessionsListener)
        } catch (_: Exception) {
        }
        super.onListenerDisconnected()
    }

    private fun bindBestMediaController(controllers: List<MediaController>?) {
        val list = controllers.orEmpty()
        val chosen = list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: list.firstOrNull { it.packageName.contains("spotify", ignoreCase = true) }
            ?: list.firstOrNull()

        if (chosen?.sessionToken != activeController?.sessionToken) {
            activeController?.unregisterCallback(mediaCallback)
            activeController = chosen
            chosen?.registerCallback(mediaCallback)
        }
        syncMediaController(chosen)
    }

    private var lastArtFetchKey: String? = null

    /** Update ringan tiap detik: hanya posisi lagu, tanpa baca ulang metadata/cover/jaringan. */
    private fun tickPosition(controller: MediaController) {
        val pb = controller.playbackState ?: return
        val cur = IslandStateHolder.media.value
        if (!cur.isPlaying) return
        var pos = pb.position
        if (pb.lastPositionUpdateTime > 0) {
            val delta = SystemClock.elapsedRealtime() - pb.lastPositionUpdateTime
            if (delta in 0..30000) pos += delta
        }
        if (cur.durationMs > 0) pos = pos.coerceIn(0L, cur.durationMs)
        IslandStateHolder.updateMediaPosition(pos)
    }

    private fun syncMediaController(controller: MediaController?) {
        if (controller == null) {
            IslandStateHolder.updateMedia(MediaState())
            return
        }
        val meta = controller.metadata
        val pb = controller.playbackState
        val title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: ""
        val artist = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: meta?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: ""
        val duration = meta?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val isPlaying = pb?.state == PlaybackState.STATE_PLAYING

        var pos = pb?.position ?: 0L
        if (isPlaying && pb != null && pb.lastPositionUpdateTime > 0) {
            val delta = SystemClock.elapsedRealtime() - pb.lastPositionUpdateTime
            if (delta in 0..30000) pos += delta
        }
        if (duration > 0) pos = pos.coerceIn(0L, duration)

        val art = meta?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: meta?.getBitmap(MediaMetadata.METADATA_KEY_ART)

        IslandStateHolder.updateMedia(
            MediaState(
                title = title,
                artist = artist,
                isPlaying = isPlaying,
                positionMs = pos,
                durationMs = duration,
                albumArt = art,
                packageName = controller.packageName
            )
        )

        val artKey = "$title|$artist"
        if (art == null && title.isNotBlank() && lastArtFetchKey != artKey) {
            lastArtFetchKey = artKey
            scope.launch {
                val fetched = RemoteFetchers.fetchAlbumArtFallback(title, artist)
                if (fetched != null) {
                    val cur = IslandStateHolder.media.value
                    if (cur.title == title && cur.artist == artist && cur.albumArt == null) {
                        IslandStateHolder.updateMedia(cur.copy(albumArt = fetched))
                    }
                }
            }
        }
    }

    private fun handleMediaAction(action: String, seekMs: Long) {
        val controls = activeController?.transportControls ?: return
        when (action) {
            "play_pause" -> {
                if (activeController?.playbackState?.state == PlaybackState.STATE_PLAYING) {
                    controls.pause()
                } else {
                    controls.play()
                }
            }
            "next" -> controls.skipToNext()
            "prev" -> controls.skipToPrevious()
            "seek" -> controls.seekTo(seekMs)
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        if (sbn.packageName == packageName) return
        if (sbn.isOngoing) return

        val notif = sbn.notification ?: return
        if (notif.category == Notification.CATEGORY_TRANSPORT) return

        val extras = notif.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val body = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString()?.trim().orEmpty()

        if (title.isBlank() && body.isBlank()) return

        // Ekstrak RemoteInput untuk balas cepat (WhatsApp, Telegram, dll.)
        var replyAction: Notification.Action? = null
        var remoteInput: android.app.RemoteInput? = null
        notif.actions?.forEach { act ->
            val ri = act.remoteInputs?.firstOrNull()
            if (ri != null && replyAction == null) {
                replyAction = act
                remoteInput = ri
            }
        }

        val appName = resolveAppName(sbn.packageName)
        val iconBitmap = resolveAppIcon(sbn.packageName)

        IslandStateHolder.enqueueNotification(
            NotifItem(
                key = sbn.key,
                packageName = sbn.packageName,
                appName = appName,
                title = title,
                body = body,
                appIcon = iconBitmap,
                contentIntent = notif.contentIntent,
                replyAction = replyAction?.actionIntent,
                remoteInput = remoteInput
            )
        )
    }

    private fun resolveAppName(pkg: String): String {
        return try {
            val info = packageManager.getApplicationInfo(pkg, 0)
            packageManager.getApplicationLabel(info).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            pkg.substringAfterLast('.')
        }
    }

    private fun resolveAppIcon(pkg: String): Bitmap? {
        return try {
            val drawable = packageManager.getApplicationIcon(pkg)
            drawableToBitmap(drawable)
        } catch (_: Exception) {
            null
        }
    }

    private fun drawableToBitmap(drawable: Drawable): Bitmap {
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            return drawable.bitmap
        }
        val w = drawable.intrinsicWidth.coerceAtLeast(48)
        val h = drawable.intrinsicHeight.coerceAtLeast(48)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bmp
    }
}
