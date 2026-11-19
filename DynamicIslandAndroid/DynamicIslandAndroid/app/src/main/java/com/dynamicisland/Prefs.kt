package com.dynamicisland

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Pengaturan (setara DEFAULT_SETTINGS di Python). Observable oleh Compose. */
object Prefs {
    private var sp: SharedPreferences? = null

    var enabled by mutableStateOf(true)
    var scale by mutableFloatStateOf(1f)              // 0.6 .. 1.4
    var yOffset by mutableFloatStateOf(0f)            // dp dari tepi atas layar
    var cutoutGap by mutableFloatStateOf(28f)         // celah untuk lubang kamera (dp)
    var autoHide by mutableStateOf(false)
    var hideDelay by mutableIntStateOf(4)             // detik
    var bg by mutableIntStateOf(0xFF000000.toInt())
    var detectAudio by mutableStateOf(true)           // kontrol semua aplikasi media
    var coverRound by mutableStateOf(false)
    var autoDnd by mutableStateOf(true)
    var showWeather by mutableStateOf(true)

    fun init(ctx: Context) {
        if (sp != null) return
        sp = ctx.applicationContext.getSharedPreferences("dynamic_island", Context.MODE_PRIVATE)
        load()
    }

    private fun load() {
        val s = sp ?: return
        enabled = s.getBoolean("enabled", true)
        scale = s.getFloat("scale", 1f).coerceIn(0.6f, 1.4f)
        yOffset = s.getFloat("yOffset", 0f)
        cutoutGap = s.getFloat("cutoutGap", 28f)
        autoHide = s.getBoolean("autoHide", false)
        hideDelay = s.getInt("hideDelay", 4)
        bg = s.getInt("bg", 0xFF000000.toInt())
        detectAudio = s.getBoolean("detectAudio", true)
        coverRound = s.getBoolean("coverRound", false)
        autoDnd = s.getBoolean("autoDnd", true)
        showWeather = s.getBoolean("showWeather", true)
    }

    fun save() {
        sp?.edit()
            ?.putBoolean("enabled", enabled)
            ?.putFloat("scale", scale)
            ?.putFloat("yOffset", yOffset)
            ?.putFloat("cutoutGap", cutoutGap)
            ?.putBoolean("autoHide", autoHide)
            ?.putInt("hideDelay", hideDelay)
            ?.putInt("bg", bg)
            ?.putBoolean("detectAudio", detectAudio)
            ?.putBoolean("coverRound", coverRound)
            ?.putBoolean("autoDnd", autoDnd)
            ?.putBoolean("showWeather", showWeather)
            ?.apply()
    }

    fun reset() {
        scale = 1f; yOffset = 0f; cutoutGap = 28f; autoHide = false; hideDelay = 4
        bg = 0xFF000000.toInt(); detectAudio = true; coverRound = false; autoDnd = true; showWeather = true
        save()
    }
}
