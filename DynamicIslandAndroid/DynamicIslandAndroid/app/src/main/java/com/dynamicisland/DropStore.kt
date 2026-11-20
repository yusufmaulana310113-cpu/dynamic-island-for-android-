package com.dynamicisland

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.provider.Settings
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Rak penyimpanan sementara (Quick Drop). File disalin ke cacheDir/drop lalu dibagikan via FileProvider. */
object DropStore {
    private val main = Handler(Looper.getMainLooper())

    fun dir(ctx: Context): File = File(ctx.cacheDir, "drop").apply { mkdirs() }

    fun loadExisting(ctx: Context) {
        val files = dir(ctx).listFiles()?.sortedBy { it.lastModified() }?.takeLast(C.MAX_DROP_FILES) ?: return
        IslandStore.dropFiles.clear()
        files.forEach { IslandStore.dropFiles.add(DropFile(it, displayName(it), mimeOf(it.name))) }
    }

    private fun displayName(f: File) = f.name.substringAfter('_', f.name)
    private fun mimeOf(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    private fun register(file: File, name: String, mime: String) {
        main.post {
            val l = IslandStore.dropFiles
            l.add(DropFile(file, name, mime))
            while (l.size > C.MAX_DROP_FILES) { l[0].file.delete(); l.removeAt(0) }
            IslandBus.onDropAdded?.invoke()
        }
    }

    fun addFromUri(ctx: Context, uri: Uri): Boolean {
        val cr = ctx.contentResolver
        var name = "file"
        try {
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) name = it.getString(0) ?: name }
        } catch (_: Exception) {}
        val mime = cr.getType(uri) ?: mimeOf(name)
        val safe = name.replace(Regex("[^A-Za-z0-9._ -]"), "_")
        val out = File(dir(ctx), "${System.currentTimeMillis()}_$safe")
        try {
            val input = cr.openInputStream(uri) ?: return false
            var total = 0L
            input.use { ins ->
                out.outputStream().use { os ->
                    val buf = ByteArray(32 * 1024)
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > C.DROP_FILE_MAX) { out.delete(); return false }
                        os.write(buf, 0, n)
                    }
                }
            }
        } catch (_: Exception) { out.delete(); return false }
        register(out, name, mime)
        return true
    }

    fun addText(ctx: Context, text: String): Boolean {
        val t = text.take(C.DROP_TEXT_MAX)
        if (t.isBlank()) return false
        val stamp = SimpleDateFormat("HHmmss", Locale.US).format(Date())
        val name = "teks_$stamp.txt"
        val out = File(dir(ctx), "${System.currentTimeMillis()}_$name")
        out.writeText(t)
        register(out, name, "text/plain")
        return true
    }

    fun clear(ctx: Context) {
        IslandStore.dropFiles.clear()
        dir(ctx).listFiles()?.forEach { it.delete() }
    }

    /** Intent Android Share Sheet untuk seluruh isi rak. */
    fun shareIntent(ctx: Context): Intent? {
        val files = IslandStore.dropFiles.toList()
        if (files.isEmpty()) return null
        val uris = ArrayList(files.map { FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", it.file) })
        val send = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        val mimes = files.map { it.mime }.distinct()
        send.type = if (mimes.size == 1) mimes[0] else "*/*"
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, "Bagikan Quick Drop").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

/** Target Share Sheet: "Dynamic Island Quick Drop". */
class ShareReceiverActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val it = intent
        lifecycleScope.launch {
            val added = withContext(Dispatchers.IO) { handle(it) }
            if (added > 0) {
                if (!IslandService.running) IslandService.start(applicationContext)
                Toast.makeText(this@ShareReceiverActivity, "Ditambahkan ke Quick Drop ($added)", Toast.LENGTH_SHORT).show()
            }
            finish()
            overridePendingTransition(0, 0)
        }
    }

    private fun handle(i: Intent?): Int {
        i ?: return 0
        var n = 0
        val uris = ArrayList<Uri>()
        if (i.action == Intent.ACTION_SEND_MULTIPLE) {
            val l = if (Build.VERSION.SDK_INT >= 33) i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
            if (l != null) uris.addAll(l)
        } else {
            val u = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            if (u != null) uris.add(u)
        }
        uris.forEach { if (DropStore.addFromUri(this, it)) n++ }
        if (uris.isEmpty()) {
            i.getStringExtra(Intent.EXTRA_TEXT)?.let { if (DropStore.addText(this, it)) n++ }
        }
        return n
    }
}

/**
 * Android 10+ hanya mengizinkan baca clipboard bila jendela punya fokus. Saat clipboard berubah,
 * service membuka activity transparan ini sepersekian detik untuk membaca teksnya.
 */
class ClipboardReaderActivity : Activity() {
    private var done = false
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !done) {
            done = true
            try {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = cm.primaryClip
                if (clip != null && clip.itemCount > 0) {
                    val t = clip.getItemAt(0).coerceToText(this)?.toString()
                    if (!t.isNullOrBlank()) IslandBus.onClipText?.invoke(t)
                }
            } catch (_: Exception) {}
            finish()
            overridePendingTransition(0, 0)
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        Prefs.init(context)
        if (Prefs.enabled && Settings.canDrawOverlays(context)) IslandService.start(context)
    }
}
