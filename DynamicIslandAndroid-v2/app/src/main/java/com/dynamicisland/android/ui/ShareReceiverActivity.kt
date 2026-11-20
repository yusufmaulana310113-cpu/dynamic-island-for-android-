package com.dynamicisland.android.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import com.dynamicisland.android.data.IslandStateHolder
import com.dynamicisland.android.model.DropItem
import java.io.File
import java.util.UUID

/**
 * Menerima file, gambar, atau teks dari Android Share Sheet (Intent.ACTION_SEND / SEND_MULTIPLE)
 * dan menyimpannya ke rak sementara Quick Drop di Dynamic Island.
 */
class ShareReceiverActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIncomingShare(intent)
        finish()
    }

    private fun handleIncomingShare(intent: Intent?) {
        if (intent == null) return
        val dropDir = File(cacheDir, "quick_drop").apply { mkdirs() }
        val collected = mutableListOf<DropItem>()

        when (intent.action) {
            Intent.ACTION_SEND -> {
                val uri = extractStreamUri(intent)
                if (uri != null) {
                    copyToQuickDrop(uri, intent.type ?: "*/*", dropDir)?.let { collected.add(it) }
                } else {
                    val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
                    if (!sharedText.isNullOrBlank()) {
                        val txtFile = File(dropDir, "snippet_${System.currentTimeMillis()}.txt")
                        txtFile.writeText(sharedText)
                        val localUri = FileProvider.getUriForFile(this, "$packageName.fileprovider", txtFile)
                        collected.add(DropItem(UUID.randomUUID().toString(), txtFile.name, localUri, "text/plain"))
                    }
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = extractStreamUriList(intent)
                uris.forEach { uri ->
                    copyToQuickDrop(uri, intent.type ?: "*/*", dropDir)?.let { collected.add(it) }
                }
            }
        }

        if (collected.isNotEmpty()) {
            IslandStateHolder.addDropItems(collected)
        }
    }

    private fun copyToQuickDrop(sourceUri: Uri, mimeType: String, dir: File): DropItem? {
        return try {
            val displayName = queryDisplayName(sourceUri) ?: "drop_${System.currentTimeMillis()}"
            val destFile = File(dir, displayName)
            contentResolver.openInputStream(sourceUri)?.use { input ->
                destFile.outputStream().use { output -> input.copyTo(output) }
            }
            val safeUri = FileProvider.getUriForFile(this, "$packageName.fileprovider", destFile)
            DropItem(
                id = UUID.randomUUID().toString(),
                name = displayName,
                uri = safeUri,
                mimeType = mimeType
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && nameIdx >= 0) cursor.getString(nameIdx) else null
            }
        } catch (_: Exception) {
            uri.lastPathSegment
        }
    }

    private fun extractStreamUri(intent: Intent): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
    }

    private fun extractStreamUriList(intent: Intent): List<Uri> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
        }
    }
}
