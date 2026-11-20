package com.dynamicisland

import android.app.Notification
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.graphics.asImageBitmap

/** Setara NotificationWatcher: menangkap notifikasi + ikon aplikasi + aksi balas (RemoteInput). */
class IslandNotificationListener : NotificationListenerService() {

    private val main = Handler(Looper.getMainLooper())
    private val seen = object : LinkedHashMap<String, Int>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>?) = size > 64
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            val item = build(sbn) ?: return
            main.post { IslandBus.onNotification?.invoke(item) }
        } catch (_: Exception) {
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        val key = sbn.key
        seen.remove(key)
        main.post { IslandBus.onNotificationRemoved?.invoke(key) }
    }

    private fun build(sbn: StatusBarNotification): NotifItem? {
        val n = sbn.notification ?: return null
        if (sbn.packageName == packageName) return null
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
        if (n.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE) != 0) return null
        when (n.category) {
            Notification.CATEGORY_TRANSPORT, Notification.CATEGORY_PROGRESS,
            Notification.CATEGORY_SERVICE, Notification.CATEGORY_SYSTEM,
            Notification.CATEGORY_STATUS -> return null
        }
        val ex = n.extras
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val body = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString().orEmpty()
        if (title.isBlank() && body.isBlank()) return null
        val sig = (title + "\u0000" + body).hashCode()
        if (seen[sbn.key] == sig) return null
        seen[sbn.key] = sig

        val pm = packageManager
        val app = try { pm.getApplicationLabel(pm.getApplicationInfo(sbn.packageName, 0)).toString() } catch (_: Exception) { sbn.packageName }
        val icon = try { pm.getApplicationIcon(sbn.packageName).toBitmap(96, 96).asImageBitmap() } catch (_: Exception) { null }

        val acts = n.actions.orEmpty()
        val reply = acts.firstOrNull { a ->
            a.semanticAction == Notification.Action.SEMANTIC_ACTION_REPLY &&
                a.remoteInputs?.any { it.allowFreeFormInput } == true
        } ?: acts.firstOrNull { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true }

        return NotifItem(
            key = sbn.key, pkg = sbn.packageName, app = app,
            title = title.ifBlank { app }, body = body,
            icon = icon, contentIntent = n.contentIntent, replyAction = reply,
        )
    }

    companion object {
        fun isEnabled(ctx: Context): Boolean =
            NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)

        fun componentName(ctx: Context) = ComponentName(ctx, IslandNotificationListener::class.java)
    }
}

/** Setara ReplySender: mengirim balasan lewat RemoteInput milik notifikasi (WhatsApp, Telegram, dll). */
object ReplySender {
    fun send(ctx: Context, item: NotifItem, text: String): Result<Unit> = runCatching {
        if (item.demo) {
            Thread.sleep(600)
        } else {
            val act = item.replyAction ?: error("Notifikasi ini tidak mendukung balasan")
            val inputs = act.remoteInputs ?: error("Aksi balas tidak valid")
            val intent = Intent()
            val results = Bundle()
            inputs.forEach { if (it.allowFreeFormInput) results.putCharSequence(it.resultKey, text) }
            RemoteInput.addResultsToIntent(inputs, intent, results)
            if (Build.VERSION.SDK_INT >= 28) RemoteInput.setResultsSource(intent, RemoteInput.SOURCE_FREE_FORM_INPUT)
            act.actionIntent.send(ctx, 0, intent)
        }
    }
}
