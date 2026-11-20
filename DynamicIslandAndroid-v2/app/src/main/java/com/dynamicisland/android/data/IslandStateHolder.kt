package com.dynamicisland.android.data

import android.app.RemoteInput
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import com.dynamicisland.android.model.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Pusat kendali reaktif (State Machine) yang mereplikasi logika prioritas mode
 * dari DynamicIsland (Python PyQt6):
 * reply > screenshot > notif > drop > hud > charge > player > music > idle / hidden
 */
object IslandStateHolder {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _settings = MutableStateFlow(IslandSettings())
    val settings: StateFlow<IslandSettings> = _settings.asStateFlow()

    private val _mode = MutableStateFlow(IslandMode.IDLE)
    val mode: StateFlow<IslandMode> = _mode.asStateFlow()

    private val _media = MutableStateFlow(MediaState())
    val media: StateFlow<MediaState> = _media.asStateFlow()

    private val _activeNotif = MutableStateFlow<NotifItem?>(null)
    val activeNotif: StateFlow<NotifItem?> = _activeNotif.asStateFlow()

    private val _replyItem = MutableStateFlow<NotifItem?>(null)
    val replyItem: StateFlow<NotifItem?> = _replyItem.asStateFlow()

    private val _replyFeedback = MutableStateFlow("")
    val replyFeedback: StateFlow<String> = _replyFeedback.asStateFlow()

    private val _hud = MutableStateFlow<HudState?>(null)
    val hud: StateFlow<HudState?> = _hud.asStateFlow()

    private val _charge = MutableStateFlow<ChargeState?>(null)
    val charge: StateFlow<ChargeState?> = _charge.asStateFlow()

    private val _weather = MutableStateFlow(WeatherState())
    val weather: StateFlow<WeatherState> = _weather.asStateFlow()

    private val _dropItems = MutableStateFlow<List<DropItem>>(emptyList())
    val dropItems: StateFlow<List<DropItem>> = _dropItems.asStateFlow()

    private val _dropOpen = MutableStateFlow(false)
    val dropOpen: StateFlow<Boolean> = _dropOpen.asStateFlow()

    private val _clipboardHistory = MutableStateFlow<List<String>>(emptyList())
    val clipboardHistory: StateFlow<List<String>> = _clipboardHistory.asStateFlow()

    private val _showClipboardPanel = MutableStateFlow(false)
    val showClipboardPanel: StateFlow<Boolean> = _showClipboardPanel.asStateFlow()

    private val _showLyricsPanel = MutableStateFlow(false)
    val showLyricsPanel: StateFlow<Boolean> = _showLyricsPanel.asStateFlow()

    private val _screenshot = MutableStateFlow<ScreenshotPreview?>(null)
    val screenshot: StateFlow<ScreenshotPreview?> = _screenshot.asStateFlow()

    private val _dndManual = MutableStateFlow(false)
    val dndManual: StateFlow<Boolean> = _dndManual.asStateFlow()

    private val _fullscreenBusy = MutableStateFlow(false)
    val fullscreenBusy: StateFlow<Boolean> = _fullscreenBusy.asStateFlow()

    private var playerExpanded = false
    private var isHidden = false
    private val notifQueue = ArrayDeque<NotifItem>()

    private var notifJob: Job? = null
    private var hudJob: Job? = null
    private var chargeJob: Job? = null
    private var playerPopupJob: Job? = null
    private var autoHideJob: Job? = null
    private var dropJob: Job? = null
    private var shotJob: Job? = null

    var mediaActionCallback: ((String, Long) -> Unit)? = null
    var fetchLyricsCallback: ((String, String) -> Unit)? = null

    fun applySettings(newSettings: IslandSettings) {
        _settings.value = newSettings
        if (!newSettings.autoHide) isHidden = false
        recomputeMode()
    }

    fun isDndActive(): Boolean {
        return _dndManual.value || (_settings.value.autoDnd && _fullscreenBusy.value)
    }

    fun setManualDnd(enabled: Boolean) {
        _dndManual.value = enabled
        if (!isDndActive()) drainNextNotif()
        recomputeMode()
    }

    fun setFullscreenBusy(busy: Boolean) {
        if (_fullscreenBusy.value == busy) return
        _fullscreenBusy.value = busy
        if (!isDndActive()) drainNextNotif()
        recomputeMode()
    }

    fun updateMedia(newState: MediaState) {
        val prev = _media.value
        val trackChanged = prev.title != newState.title || prev.artist != newState.artist
        val merged = if (trackChanged) {
            newState.copy(lyrics = "", isLoadingLyrics = false)
        } else {
            newState.copy(
                lyrics = prev.lyrics,
                isLoadingLyrics = prev.isLoadingLyrics,
                albumArt = newState.albumArt ?: prev.albumArt
            )
        }
        _media.value = merged

        if (merged.title.isNotBlank() && merged.isPlaying && (trackChanged || !prev.isPlaying)) {
            playerExpanded = true
            playerPopupJob?.cancel()
            playerPopupJob = scope.launch {
                delay(4500)
                playerExpanded = false
                recomputeMode()
            }
        }
        if (trackChanged && _showLyricsPanel.value && merged.title.isNotBlank()) {
            fetchLyricsCallback?.invoke(merged.title, merged.artist)
        }
        recomputeMode()
    }

    fun updateMediaPosition(posMs: Long) {
        val cur = _media.value
        if (cur.positionMs == posMs) return
        _media.value = cur.copy(positionMs = posMs)
    }

    fun updateLyrics(title: String, artist: String, lyrics: String) {
        val cur = _media.value
        if (cur.title == title && cur.artist == artist) {
            _media.value = cur.copy(lyrics = lyrics, isLoadingLyrics = false)
        }
    }

    fun setLyricsLoading(loading: Boolean) {
        _media.value = _media.value.copy(isLoadingLyrics = loading)
    }

    fun toggleLyricsPanel() {
        val next = !_showLyricsPanel.value
        _showLyricsPanel.value = next
        if (next) {
            _showClipboardPanel.value = false
            val cur = _media.value
            if (cur.title.isNotBlank() && cur.lyrics.isBlank()) {
                fetchLyricsCallback?.invoke(cur.title, cur.artist)
            }
        }
        recomputeMode()
    }

    fun toggleClipboardPanel() {
        val next = !_showClipboardPanel.value
        _showClipboardPanel.value = next
        if (next) _showLyricsPanel.value = false
        recomputeMode()
    }

    fun addClipboardText(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed.length > 20000) return
        val cur = _clipboardHistory.value
        if (cur.firstOrNull() == trimmed) return
        _clipboardHistory.value = (listOf(trimmed) + cur.filterNot { it == trimmed }).take(5)
        recomputeMode()
    }

    fun copyToClipboard(context: Context, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("DynamicIsland", text))
        _showClipboardPanel.value = false
        recomputeMode()
    }

    fun clearClipboard() {
        _clipboardHistory.value = emptyList()
        recomputeMode()
    }

    fun enqueueNotification(item: NotifItem) {
        if (notifQueue.size >= 20) notifQueue.removeFirst()
        notifQueue.addLast(item)
        drainNextNotif()
    }

    private fun drainNextNotif() {
        if (_activeNotif.value != null || _replyItem.value != null) return
        if (notifQueue.isEmpty()) {
            recomputeMode()
            return
        }
        if (isDndActive() && !notifQueue.first().isDemo) return

        val next = notifQueue.removeFirst()
        _activeNotif.value = next
        notifJob?.cancel()
        notifJob = scope.launch {
            delay(5500)
            dismissNotif()
        }
        recomputeMode()
    }

    fun dismissNotif() {
        notifJob?.cancel()
        _activeNotif.value = null
        recomputeMode()
        if (notifQueue.isNotEmpty()) {
            scope.launch {
                delay(400)
                drainNextNotif()
            }
        }
    }

    fun openReplyForActiveNotif() {
        val item = _activeNotif.value ?: return
        if (!item.canReply) return
        notifJob?.cancel()
        _activeNotif.value = null
        _replyFeedback.value = ""
        _replyItem.value = item
        recomputeMode()
    }

    fun sendQuickReply(context: Context, replyText: String) {
        val item = _replyItem.value ?: return
        val clean = replyText.trim()
        if (clean.isEmpty()) return

        if (item.isDemo) {
            _replyFeedback.value = "Terkirim (Demo)"
            scope.launch {
                delay(1200)
                closeReply()
            }
            return
        }

        try {
            val action = item.replyAction ?: return
            val remote = item.remoteInput ?: return
            val intent = Intent()
            val bundle = Bundle().apply {
                putCharSequence(remote.resultKey, clean)
            }
            RemoteInput.addResultsToIntent(arrayOf(remote), intent, bundle)
            action.send(context, 0, intent)
            _replyFeedback.value = "Terkirim ✓"
            scope.launch {
                delay(1200)
                closeReply()
            }
        } catch (e: Exception) {
            _replyFeedback.value = "Gagal: ${e.message ?: "Error"}"
        }
    }

    fun closeReply() {
        _replyItem.value = null
        _replyFeedback.value = ""
        recomputeMode()
        if (notifQueue.isNotEmpty()) {
            scope.launch {
                delay(400)
                drainNextNotif()
            }
        }
    }

    fun showHud(kind: HudKind, value: Float, title: String, subtitle: String, muted: Boolean = false) {
        _hud.value = HudState(kind, value.coerceIn(0f, 1f), title, subtitle, muted)
        hudJob?.cancel()
        hudJob = scope.launch {
            delay(1600)
            _hud.value = null
            recomputeMode()
        }
        recomputeMode()
    }

    fun showCharging(level: Int) {
        _charge.value = ChargeState(isCharging = true, level = level.coerceIn(0, 100))
        chargeJob?.cancel()
        chargeJob = scope.launch {
            delay(4500)
            _charge.value = null
            recomputeMode()
        }
        recomputeMode()
    }

    fun hideCharging() {
        chargeJob?.cancel()
        _charge.value = null
        recomputeMode()
    }

    fun updateWeather(tempC: Int, kind: String) {
        _weather.value = WeatherState(tempC, kind)
        recomputeMode()
    }

    fun addDropItems(items: List<DropItem>) {
        if (items.isEmpty()) return
        val combined = (_dropItems.value + items).take(10)
        _dropItems.value = combined
        openDropShelf(autoCollapseMs = 3000L)
    }

    fun openDropShelf(autoCollapseMs: Long = 7000L) {
        if (_dropItems.value.isEmpty()) return
        _dropOpen.value = true
        dropJob?.cancel()
        dropJob = scope.launch {
            delay(autoCollapseMs)
            _dropOpen.value = false
            recomputeMode()
        }
        recomputeMode()
    }

    fun closeDropShelf() {
        dropJob?.cancel()
        _dropOpen.value = false
        recomputeMode()
    }

    fun clearDropShelf() {
        _dropItems.value = emptyList()
        closeDropShelf()
    }

    fun showScreenshot(bitmap: Bitmap, label: String) {
        _screenshot.value = ScreenshotPreview(bitmap, label)
        shotJob?.cancel()
        shotJob = scope.launch {
            delay(3500)
            _screenshot.value = null
            recomputeMode()
        }
        recomputeMode()
    }

    fun dismissScreenshot() {
        shotJob?.cancel()
        _screenshot.value = null
        recomputeMode()
    }

    fun onCapsuleTapped() {
        isHidden = false
        when (_mode.value) {
            IslandMode.HIDDEN -> {
                recomputeMode()
            }
            IslandMode.MUSIC -> {
                playerExpanded = true
                playerPopupJob?.cancel()
                playerPopupJob = scope.launch {
                    delay(5000)
                    playerExpanded = false
                    recomputeMode()
                }
                recomputeMode()
            }
            IslandMode.PLAYER -> {
                playerExpanded = false
                recomputeMode()
            }
            IslandMode.NOTIF -> dismissNotif()
            IslandMode.DROP -> closeDropShelf()
            IslandMode.CHARGE -> hideCharging()
            IslandMode.HUD -> {
                hudJob?.cancel()
                _hud.value = null
                recomputeMode()
            }
            IslandMode.SCREENSHOT -> dismissScreenshot()
            IslandMode.IDLE -> {
                if (_dropItems.value.isNotEmpty()) {
                    openDropShelf()
                } else if (_clipboardHistory.value.isNotEmpty()) {
                    toggleClipboardPanel()
                }
            }
            IslandMode.REPLY -> Unit
        }
    }

    fun onCapsuleSwipedUp() {
        when (_mode.value) {
            IslandMode.REPLY -> closeReply()
            IslandMode.NOTIF -> dismissNotif()
            IslandMode.PLAYER -> {
                playerExpanded = false
                recomputeMode()
            }
            IslandMode.DROP -> closeDropShelf()
            IslandMode.CHARGE -> hideCharging()
            IslandMode.HUD -> {
                hudJob?.cancel()
                _hud.value = null
                recomputeMode()
            }
            IslandMode.SCREENSHOT -> dismissScreenshot()
            else -> Unit
        }
    }

    fun recomputeMode() {
        val hasTrack = _media.value.title.isNotBlank()
        val baseMode = when {
            _replyItem.value != null -> IslandMode.REPLY
            _screenshot.value != null -> IslandMode.SCREENSHOT
            _activeNotif.value != null -> IslandMode.NOTIF
            _dropOpen.value && _dropItems.value.isNotEmpty() -> IslandMode.DROP
            _hud.value != null -> IslandMode.HUD
            _charge.value != null -> IslandMode.CHARGE
            hasTrack && playerExpanded -> IslandMode.PLAYER
            hasTrack -> IslandMode.MUSIC
            else -> IslandMode.IDLE
        }

        val cfg = _settings.value
        val canAutoHide = cfg.autoHide &&
            (baseMode == IslandMode.IDLE || baseMode == IslandMode.MUSIC) &&
            !_showLyricsPanel.value && !_showClipboardPanel.value

        if (canAutoHide) {
            if (isHidden) {
                _mode.value = IslandMode.HIDDEN
                return
            }
            if (autoHideJob?.isActive != true) {
                autoHideJob = scope.launch {
                    delay(cfg.hideDelaySec * 1000L)
                    isHidden = true
                    recomputeMode()
                }
            }
        } else {
            isHidden = false
            autoHideJob?.cancel()
        }

        _mode.value = baseMode
    }
}
