package com.dynamicisland.android.network

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.roundToInt

/**
 * Porting dari LyricsFetcher (lrclib.net), CoverFetcher (iTunes API),
 * dan WeatherFetcher (ip-api.com + open-meteo.com).
 */
object RemoteFetchers {

    private fun httpGet(urlStr: String, timeoutMs: Int = 7000): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            setRequestProperty("User-Agent", "DynamicIslandAndroid/8.3")
        }
        return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    suspend fun fetchLyrics(title: String, artist: String): String = withContext(Dispatchers.IO) {
        if (title.isBlank()) return@withContext "Lirik tidak tersedia."
        val qTitle = URLEncoder.encode(title, "UTF-8")
        val qArtist = URLEncoder.encode(artist, "UTF-8")
        val base = "https://lrclib.net/api"

        try {
            val raw = httpGet("$base/get?track_name=$qTitle&artist_name=$qArtist")
            val obj = JSONObject(raw)
            val parsed = extractLyricsFromJson(obj)
            if (parsed.isNotBlank()) return@withContext parsed
            if (obj.optBoolean("instrumental", false)) return@withContext "(Instrumental)"
        } catch (_: Exception) {
        }

        val cleanTitle = title.replace(Regex("""\s*[\(\[].*?[\)\]]"""), "")
            .replace(Regex("""\s+-\s+.*$"""), "")
            .trim()
            .ifBlank { title }

        try {
            val qSearch = URLEncoder.encode("$cleanTitle $artist", "UTF-8")
            val rawArr = httpGet("$base/search?q=$qSearch")
            val arr = JSONArray(rawArr)
            for (i in 0 until arr.length()) {
                val parsed = extractLyricsFromJson(arr.getJSONObject(i))
                if (parsed.isNotBlank()) return@withContext parsed
            }
        } catch (e: Exception) {
            return@withContext "Gagal mengambil lirik:\n${e.message}"
        }
        "Lirik tidak ditemukan."
    }

    private fun extractLyricsFromJson(obj: JSONObject): String {
        val plain = obj.optString("plainLyrics", "")
        if (plain.isNotBlank() && plain != "null") return plain.trim()

        val synced = obj.optString("syncedLyrics", "")
        if (synced.isNotBlank() && synced != "null") {
            return synced.lines()
                .joinToString("\n") { it.replace(Regex("""^\s*\[[^\]]*\]\s*"""), "") }
                .trim()
        }
        return ""
    }

    suspend fun fetchAlbumArtFallback(title: String, artist: String): Bitmap? = withContext(Dispatchers.IO) {
        if (title.isBlank()) return@withContext null
        try {
            val clean = title.replace(Regex("""\s*[\(\[].*?[\)\]]"""), "").trim()
            val term = URLEncoder.encode("$artist $clean", "UTF-8")
            val raw = httpGet("https://itunes.apple.com/search?term=$term&entity=song&limit=5")
            val results = JSONObject(raw).optJSONArray("results") ?: return@withContext null
            if (results.length() == 0) return@withContext null
            val artUrl = results.getJSONObject(0)
                .optString("artworkUrl100", "")
                .replace("100x100", "300x300")
            if (artUrl.isBlank()) return@withContext null

            val conn = (URL(artUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 7000
                readTimeout = 7000
            }
            conn.inputStream.use { BitmapFactory.decodeStream(it) }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun fetchWeather(): Pair<Int, String>? = withContext(Dispatchers.IO) {
        try {
            val (lat, lon) = locateByIp() ?: return@withContext null
            val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                "&current=temperature_2m,weather_code&timezone=auto"
            val root = JSONObject(httpGet(url))
            val cur = root.getJSONObject("current")
            val temp = cur.getDouble("temperature_2m").roundToInt()
            val code = cur.getInt("weather_code")
            Pair(temp, weatherCodeToKind(code))
        } catch (_: Exception) {
            null
        }
    }

    private fun locateByIp(): Pair<Double, Double>? {
        return try {
            val obj = JSONObject(httpGet("http://ip-api.com/json/?fields=status,lat,lon", 5000))
            if (obj.optString("status") == "success") {
                Pair(obj.getDouble("lat"), obj.getDouble("lon"))
            } else null
        } catch (_: Exception) {
            try {
                val obj = JSONObject(httpGet("https://ipwho.is/?fields=success,latitude,longitude", 5000))
                if (obj.optBoolean("success")) {
                    Pair(obj.getDouble("latitude"), obj.getDouble("longitude"))
                } else null
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun weatherCodeToKind(code: Int): String = when (code) {
        0, 1 -> "sun"
        51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82, 95, 96, 99 -> "rain"
        else -> "cloud"
    }
}
