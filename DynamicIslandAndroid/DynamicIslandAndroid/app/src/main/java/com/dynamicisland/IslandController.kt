package com.dynamicisland

import android.app.ActivityOptions
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class IslandController(
    private val ctx: Context,
    private val scope: CoroutineScope,
    private val host: Host,
) {
    interface Host { fun setFocusable(focusable: Boolean) }

    private val S = IslandStore
    lateinit var media: MediaWatcher

    private val queue = ArrayDeque<NotifItem>()
    private val held = ArrayDeque<NotifItem>()
    private var notifJob: Job? = null
    private var chargeJob: Job? = null
    private var hudJob: Job? = null
    private var songJob: Job? = null
    private var playerJob: Job? = null
    private var dropJob: Job? = null
    private var shotJob: Job? = null
    private var restJob: Job? = null
    private var replyIdleJob: Job? = null
    private var clipMuteUntil = 0L
    private val lyricsCache = HashMap<String, String>()
    private var prevFilter: Int? = null

    fun start() {
        // Auto-hide mengikuti perubahan mode / pengaturan secara otomatis
        scope.launch {
            snapshotFlow { Triple(S.baseMode, Prefs.autoHide, Prefs.hideDelay) }.collect { autoHideCheck() }
        }
        IslandBus.onNotification = { onNotification(it) }
        IslandBus.onNotificationRemoved = { key -> queue.removeAll { it.key == key } }
        IslandBus.onClipText = { onClipText(it) }
        IslandBus.onDropAdded = { onDropAdded() }
    }

    fun stop() {
        IslandBus.onNotification = null; IslandBus.onNotificationRemoved = null
        IslandBus.onClipText = null; IslandBus.onDropAdded = null
        prevFilter?.let { restoreSystemDnd() }
    }

    // =====================================================================
    // Auto-hide
    // =====================================================================
    fun autoHideCheck() {
        restJob?.cancel()
        val b = S.baseMode
        val quiet = b == Mode.IDLE || b == Mode.MUSIC
        if (Prefs.autoHide && quiet) {
            if (!S.hidden) restJob = scope.launch { delay(Prefs.hideDelay * 1000L); S.hidden = true }
        } else {
            S.hidden = false
        }
    }

    // =====================================================================
    // Gestur dari UI
    // =====================================================================
    fun onTap() {
        when (S.mode) {
            Mode.HIDDEN -> { S.hidden = false; autoHideCheck() }
            Mode.IDLE -> openApp()
            Mode.MUSIC -> openPlayer()
            Mode.NOTIF -> { S.notif?.contentIntent?.let { fire(it) }; endNotif() }
            Mode.CHARGE -> endCharge()
            Mode.HUD -> S.hudKind = null
            Mode.SCREENSHOT -> openShot()
            else -> {}
        }
    }

    fun onLongPress() {
        when (S.mode) {
            Mode.IDLE, Mode.MUSIC -> toggleDnd()
            Mode.PLAYER -> openApp()
            else -> {}
        }
    }

    fun swipeUp() {
        when (S.mode) {
            Mode.REPLY -> cancelReply()
            Mode.SCREENSHOT -> endShot()
            Mode.NOTIF -> endNotif()
            Mode.CHARGE -> endCharge()
            Mode.HUD -> S.hudKind = null
            Mode.DROP -> closeDrop()
            Mode.PLAYER -> { S.playerOpen = false; S.songPopup = false }
            Mode.LYRICS -> S.lyricsOpen = false
            Mode.CLIP -> S.clipOpen = false
            else -> {}
        }
    }

    fun onBack() {
        when {
            S.reply != null -> cancelReply()
            S.lyricsOpen -> S.lyricsOpen = false
            S.clipOpen -> S.clipOpen = false
            else -> {}
        }
    }

    fun openApp() {
        ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Mengirim PendingIntent milik aplikasi lain; Android 14+ butuh opt-in background activity start. */
    fun fire(pi: PendingIntent) {
        try {
            val opts = ActivityOptions.makeBasic()
            if (Build.VERSION.SDK_INT >= 34) {
                opts.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
            }
            pi.send(ctx, 0, null, null, null, null, opts.toBundle())
        } catch (_: Exception) {}
    }

    // =====================================================================
    // Media / Player
    // =====================================================================
    fun onTrackChanged(newTrack: Boolean) {
        if (!S.hasTrack) {
            S.playerOpen = false; S.songPopup = false; S.lyricsOpen = false
            return
        }
        if (newTrack) {
            S.lyrics = ""
            if (S.lyricsOpen) loadLyrics()
            if (S.playing) {
                S.songPopup = true
                songJob?.cancel()
                songJob = scope.launch { delay(C.SONG_POPUP_MS); S.songPopup = false }
            }
        }
    }

    fun openPlayer() {
        S.playerOpen = true
        touchPlayer()
    }

    fun touchPlayer() {
        playerJob?.cancel()
        playerJob = scope.launch { delay(C.AUTO_SHRINK_MS); S.playerOpen = false; S.songPopup = false }
    }

    fun mediaCommand(cmd: String) {
        touchPlayer()
        if (S.demo) {
            if (cmd == "play") {
                val now = SystemClock.elapsedRealtime()
                if (S.playing) S.posMs += ((now - S.stampMs) * S.speed).toLong()
                S.stampMs = now
                S.playing = !S.playing
            }
            return
        }
        when (cmd) {
            "play" -> media.playPause()
            "next" -> media.next()
            "prev" -> media.prev()
        }
    }

    fun seek(frac: Float) {
        touchPlayer()
        if (S.durMs <= 0 || !S.canSeek && !S.demo) return
        val ms = (frac.coerceIn(0f, 1f) * S.durMs).toLong()
        S.posMs = ms; S.stampMs = SystemClock.elapsedRealtime()
        if (!S.demo) media.seekTo(ms)
    }

    fun openMediaApp() {
        media.openApp()?.let { fire(it) }
    }

    fun toggleLyrics() {
        S.lyricsOpen = !S.lyricsOpen
        if (S.lyricsOpen) { S.clipOpen = false; loadLyrics() }
    }

    private fun loadLyrics() {
        val key = "${S.title}|${S.artist}"
        lyricsCache[key]?.let { S.lyrics = it; return }
        S.lyrics = "Memuat lirik…"
        val title = S.title; val artist = S.artist
        scope.launch {
            val text = withContext(Dispatchers.IO) { Net.fetchLyrics(title, artist) }
            if (!text.startsWith("Gagal")) lyricsCache[key] = text
            if ("${S.title}|${S.artist}" == key) S.lyrics = text
        }
    }

    fun demoMusic() {
        S.demo = true
        S.hasTrack = true; S.title = "Blinding Lights"; S.artist = "The Weeknd"
        S.playing = true; S.durMs = 200_000L; S.posMs = 62_000L
        S.stampMs = SystemClock.elapsedRealtime(); S.speed = 1f; S.canSeek = true
        S.art = null; S.accent = Color(0xFFFF5F7D)
        onTrackChanged(true)
    }

    // =====================================================================
    // Notifikasi (+ antrean, DND hold, balas cepat)
    // =====================================================================
    fun onNotification(item: NotifItem) {
        if (S.dndActive) {
            held.addLast(item)
            while (held.size > 5) held.removeFirst()
            return
        }
        enqueueNotif(item)
    }

    private fun enqueueNotif(item: NotifItem) {
        if (S.notif?.key == item.key) {
            S.notif = item; S.lastNotif = item; restartNotifTimer(); return
        }
        queue.removeAll { it.key == item.key }
        queue.addLast(item)
        while (queue.size > 20) queue.removeFirst()
        if (S.notif == null && S.reply == null) nextNotif()
    }

    private fun nextNotif() {
        val n = queue.removeFirstOrNull()
        S.notif = n
        if (n != null) { S.lastNotif = n; restartNotifTimer() }
    }

    private fun restartNotifTimer() {
        notifJob?.cancel()
        notifJob = scope.launch { delay(C.NOTIF_MS); endNotif() }
    }

    fun endNotif() {
        notifJob?.cancel()
        S.notif = null
        if (S.reply == null && queue.isNotEmpty()) nextNotif()
    }

    fun startReply() {
        val n = S.notif ?: return
        if (!n.canReply) return
        notifJob?.cancel()
        S.reply = n; S.lastReply = n
        S.notif = null
        S.replyStatus = ""; S.replyMsg = ""
        host.setFocusable(true)
        touchReply()
    }

    fun touchReply() {
        replyIdleJob?.cancel()
        replyIdleJob = scope.launch { delay(C.REPLY_IDLE_MS); if (S.replyStatus != "sending") cancelReply() }
    }

    fun submitReply(text: String) {
        val t = text.trim()
        val n = S.reply ?: return
        if (t.isEmpty() || S.replyStatus == "sending") return
        S.replyStatus = "sending"; S.replyMsg = "Mengirim…"
        replyIdleJob?.cancel()
        scope.launch {
            val res = withContext(Dispatchers.IO) { ReplySender.send(ctx, n, t) }
            if (res.isSuccess) {
                S.replyStatus = "ok"; S.replyMsg = "Terkirim"
                delay(C.REPLY_RESULT_MS)
                finishReply()
            } else {
                S.replyStatus = "fail"; S.replyMsg = "Gagal: " + (res.exceptionOrNull()?.message ?: "tidak diketahui")
                touchReply()
            }
        }
    }

    fun cancelReply() { if (S.replyStatus != "sending") finishReply() }

    private fun finishReply() {
        replyIdleJob?.cancel()
        S.reply = null; S.replyStatus = ""
        host.setFocusable(false)
        if (S.notif == null && queue.isNotEmpty()) nextNotif()
    }

    fun demoNotif(whatsapp: Boolean) {
        val item = if (whatsapp) NotifItem(
            key = "demo-wa-${System.currentTimeMillis()}", pkg = "com.whatsapp", app = "WhatsApp", title = "Budi",
            body = "Halo! Jadi ketemu jam 5 sore ya? Jangan lupa bawa laptopnya.",
            icon = null, contentIntent = null, replyAction = null, demo = true, canReply = true,
        ) else NotifItem(
            key = "demo-gm-${System.currentTimeMillis()}", pkg = "com.google.android.gm", app = "Gmail", title = "Tim Recruiter",
            body = "Undangan interview untuk hari Senin", icon = null, contentIntent = null, replyAction = null, demo = true, canReply = false,
        )
        enqueueNotif(item)
    }

    // =====================================================================
    // Pengisian daya
    // =====================================================================
    fun startCharge(pct: Int) {
        if (S.dndActive) return
        S.chargePct = pct; S.charging = true
        chargeJob?.cancel()
        chargeJob = scope.launch { delay(C.CHARGE_MS); endCharge() }
    }

    fun onBatteryLevel(pct: Int) { if (S.charging) S.chargePct = pct }
    fun endCharge() { chargeJob?.cancel(); S.charging = false }

    // =====================================================================
    // HUD
    // =====================================================================
    fun showHud(kind: HudKind, value: Float, title: String, sub: String, muted: Boolean) {
        S.hudKind = kind; S.lastHudKind = kind
        S.hudValue = value.coerceIn(0f, 1f); S.hudTitle = title; S.hudSub = sub; S.hudMuted = muted
        hudJob?.cancel()
        hudJob = scope.launch { delay(C.HUD_MS); S.hudKind = null }
    }

    // =====================================================================
    // Mode Fokus / Auto-DND
    // =====================================================================
    fun toggleDnd() {
        S.dndManual = !S.dndManual
        showHud(HudKind.FOCUS, if (S.dndManual) 1f else 0f, "Mode Fokus", if (S.dndManual) "Aktif" else "Nonaktif", false)
        onDndChanged()
    }

    fun onFullscreen(fs: Boolean) {
        if (S.fsBusy == fs) return
        S.fsBusy = fs
        onDndChanged()
    }

    private fun onDndChanged() {
        applySystemDnd()
        if (!S.dndActive) {
            val items = held.toList().takeLast(3)
            held.clear()
            items.forEach { enqueueNotif(it) }
        }
    }

    private fun applySystemDnd() {
        try {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (!nm.isNotificationPolicyAccessGranted) return
            if (S.dndActive) {
                if (prevFilter == null) {
                    prevFilter = nm.currentInterruptionFilter
                    nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
                }
            } else restoreSystemDnd()
        } catch (_: Exception) {}
    }

    private fun restoreSystemDnd() {
        try {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            prevFilter?.let { nm.setInterruptionFilter(it) }
        } catch (_: Exception) {}
        prevFilter = null
    }

    // =====================================================================
    // Clipboard
    // =====================================================================
    fun onClipText(text: String) {
        if (System.currentTimeMillis() < clipMuteUntil) return
        val s = text.trim()
        if (s.isEmpty() || s.length > C.CLIP_MAX_CHARS) return
        S.clip.remove(s)
        S.clip.add(0, s)
        while (S.clip.size > C.CLIP_MAX) S.clip.removeAt(S.clip.lastIndex)
    }

    fun toggleClip() {
        S.clipOpen = !S.clipOpen
        if (S.clipOpen) S.lyricsOpen = false
    }

    fun copyClip(text: String) {
        clipMuteUntil = System.currentTimeMillis() + 1500
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Dynamic Island", text))
        S.clipOpen = false
    }

    fun clearClip() { S.clip.clear(); S.clipOpen = false }

    // =====================================================================
    // Quick Drop
    // =====================================================================
    private fun onDropAdded() {
        S.dropOpen = true
        scheduleDropClose(C.DROP_AFTER_MS)
    }

    fun openDrop() {
        if (S.dropFiles.isEmpty()) return
        S.dropOpen = true
        scheduleDropClose(C.DROP_MS)
    }

    fun touchDrop() { scheduleDropClose(C.DROP_MS) }

    private fun scheduleDropClose(ms: Long) {
        dropJob?.cancel()
        dropJob = scope.launch { delay(ms); S.dropOpen = false }
    }

    fun closeDrop() { dropJob?.cancel(); S.dropOpen = false }

    fun clearDrop() { DropStore.clear(ctx); closeDrop() }

    fun shareDrop() {
        DropStore.shareIntent(ctx)?.let { try { ctx.startActivity(it) } catch (_: Exception) {} }
        closeDrop()
    }

    // =====================================================================
    // Screenshot
    // =====================================================================
    fun onScreenshot(uri: Uri, thumb: ImageBitmap?) {
        S.shotUri = uri; S.shotThumb = thumb
        shotJob?.cancel()
        shotJob = scope.launch { delay(C.SHOT_MS); endShot() }
    }

    fun endShot() { shotJob?.cancel(); S.shotUri = null }

    fun shareShot() {
        val u = S.shotUri ?: return
        val send = Intent(Intent.ACTION_SEND).setType("image/*").putExtra(Intent.EXTRA_STREAM, u)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try { ctx.startActivity(Intent.createChooser(send, "Bagikan screenshot").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
        endShot()
    }

    fun openShot() {
        val u = S.shotUri ?: return
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(u, "image/*")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (_: Exception) {}
        endShot()
    }
}
