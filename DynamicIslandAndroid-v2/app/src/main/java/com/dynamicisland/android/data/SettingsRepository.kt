package com.dynamicisland.android.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.dynamicisland.android.model.IslandSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "dynamic_island_prefs")

class SettingsRepository(private val context: Context) {

    private object Keys {
        val SCALE = floatPreferencesKey("scale")
        val AUTO_HIDE = booleanPreferencesKey("auto_hide")
        val HIDE_DELAY = intPreferencesKey("hide_delay")
        val BG_COLOR = longPreferencesKey("bg_color")
        val COVER_ROUND = booleanPreferencesKey("cover_round")
        val AUTO_DND = booleanPreferencesKey("auto_dnd")
        val SHOW_WEATHER = booleanPreferencesKey("show_weather")
        val TOP_MARGIN = intPreferencesKey("top_margin")
    }

    val settingsFlow: Flow<IslandSettings> = context.dataStore.data.map { prefs ->
        IslandSettings(
            scale = prefs[Keys.SCALE] ?: 1.0f,
            autoHide = prefs[Keys.AUTO_HIDE] ?: false,
            hideDelaySec = prefs[Keys.HIDE_DELAY] ?: 4,
            bgColorArgb = prefs[Keys.BG_COLOR] ?: 0xFF000000,
            coverRound = prefs[Keys.COVER_ROUND] ?: false,
            autoDnd = prefs[Keys.AUTO_DND] ?: true,
            showWeather = prefs[Keys.SHOW_WEATHER] ?: true,
            topMarginDp = prefs[Keys.TOP_MARGIN] ?: 6
        )
    }

    suspend fun update(transform: (IslandSettings) -> IslandSettings) {
        context.dataStore.edit { prefs ->
            val current = IslandSettings(
                scale = prefs[Keys.SCALE] ?: 1.0f,
                autoHide = prefs[Keys.AUTO_HIDE] ?: false,
                hideDelaySec = prefs[Keys.HIDE_DELAY] ?: 4,
                bgColorArgb = prefs[Keys.BG_COLOR] ?: 0xFF000000,
                coverRound = prefs[Keys.COVER_ROUND] ?: false,
                autoDnd = prefs[Keys.AUTO_DND] ?: true,
                showWeather = prefs[Keys.SHOW_WEATHER] ?: true,
                topMarginDp = prefs[Keys.TOP_MARGIN] ?: 6
            )
            val next = transform(current)
            prefs[Keys.SCALE] = next.scale.coerceIn(0.6f, 1.4f)
            prefs[Keys.AUTO_HIDE] = next.autoHide
            prefs[Keys.HIDE_DELAY] = next.hideDelaySec.coerceIn(2, 30)
            prefs[Keys.BG_COLOR] = next.bgColorArgb
            prefs[Keys.COVER_ROUND] = next.coverRound
            prefs[Keys.AUTO_DND] = next.autoDnd
            prefs[Keys.SHOW_WEATHER] = next.showWeather
            prefs[Keys.TOP_MARGIN] = next.topMarginDp.coerceIn(0, 60)
        }
    }

    suspend fun reset() {
        context.dataStore.edit { it.clear() }
    }
}
