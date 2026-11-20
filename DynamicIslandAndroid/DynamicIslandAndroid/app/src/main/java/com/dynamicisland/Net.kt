package com.dynamicisland

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Semua fungsi di sini BLOCKING: panggil dari Dispatchers.IO. */
object Net {
    private const val UA = "DynamicIsland/8"

    fun getBytes(url: String, timeoutMs: Int = 8000): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.setRequestProperty("User-Agent", UA)
            return c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }

    private fun getText(url: String) = String(getBytes(url), Charsets.UTF_8)
    private fun q(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun str(o: JSONObject, k: String) = if (o.isNull(k)) "" else o.optString(k, "")

    private fun cleanTitle(title: String): String =
        title.replace(Regex("\\s*[\\(\\[].*?[\\)\\]]"), "")
            .replace(Regex("\\s+-\\s+.*$"), "")
            .trim()
            .ifEmpty { title }

    // ---------------- Lirik (lrclib.net) ----------------
    private fun lyricsOf(o: JSONObject): String {
        val plain = str(o, "plainLyrics")
        if (plain.isNotBlank()) return plain
        val synced = str(o, "syncedLyrics")
        if (synced.isNotBlank()) {
            return synced.lines().joinToString("\n") { it.replace(Regex("^\\s*\\[[^\\]]*\\]\\s*"), "") }.trim()
        }
        return ""
    }

    fun fetchLyrics(title: String, artist: String): String {
        val base = "https://lrclib.net/api"
        try {
            val o = JSONObject(getText("$base/get?track_name=${q(title)}&artist_name=${q(artist)}"))
            val t = lyricsOf(o)
            if (t.isNotEmpty()) return t
            if (o.optBoolean("instrumental", false)) return "(Instrumental)"
        } catch (_: Exception) {
        }
        return try {
            val arr = JSONArray(getText("$base/search?q=${q(cleanTitle(title) + " " + artist)}"))
            for (i in 0 until arr.length()) {
                val t = lyricsOf(arr.getJSONObject(i))
                if (t.isNotEmpty()) return t
            }
            "Lirik tidak ditemukan."
        } catch (e: Exception) {
            "Gagal mengambil lirik:\n${e.message}"
        }
    }

    // ---------------- Cover (iTunes Search) ----------------
    fun fetchCover(title: String, artist: String): Bitmap? {
        return try {
            val term = q("$artist ${cleanTitle(title)}")
            val res = JSONObject(getText("https://itunes.apple.com/search?term=$term&entity=song&limit=8"))
                .optJSONArray("results") ?: return null
            val want = artist.split(Regex("[,;&]"))[0].trim().lowercase()
            for (i in 0 until res.length()) {
                val it = res.getJSONObject(i)
                val art = str(it, "artworkUrl100")
                if (art.isNotEmpty() && want.isNotEmpty() && str(it, "artistName").lowercase().contains(want)) {
                    val bytes = getBytes(art.replace("100x100", "300x300"))
                    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    fun fetchBitmap(url: String): Bitmap? = try {
        val b = getBytes(url)
        BitmapFactory.decodeByteArray(b, 0, b.size)
    } catch (_: Exception) {
        null
    }

    // ---------------- Cuaca (open-meteo + lokasi IP) ----------------
    fun ipLocation(): Pair<Double, Double> {
        val o = JSONObject(getText("https://ipwho.is/?fields=success,latitude,longitude"))
        if (o.optBoolean("success", false)) return o.getDouble("latitude") to o.getDouble("longitude")
        throw RuntimeException("lokasi tidak ditemukan")
    }

    private fun kind(code: Int) = when (code) {
        0, 1 -> "sun"
        51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82, 95, 96, 99 -> "rain"
        else -> "cloud"
    }

    fun weather(lat: Double, lon: Double): Pair<Int, String> {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=%.2f&longitude=%.2f&current=temperature_2m,weather_code&timezone=auto"
            .format(java.util.Locale.US, lat, lon)
        val cur = JSONObject(getText(url)).getJSONObject("current")
        return Math.round(cur.getDouble("temperature_2m")).toInt() to kind(cur.getInt("weather_code"))
    }
}
