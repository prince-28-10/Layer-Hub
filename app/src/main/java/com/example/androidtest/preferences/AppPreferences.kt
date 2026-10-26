package com.example.androidtest.preferences

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode {
    SYSTEM, LIGHT, DARK
}

data class BackgroundPalette(
    val name: String,
    val lightColor: Color,
    val darkColor: Color
)

object Palettes {
    val choices = listOf(
        BackgroundPalette("Slate", Color(0xFFF4F7F6), Color(0xFF101820)),
        BackgroundPalette("Ocean Navy", Color(0xFFF0F5FA), Color(0xFF101B2D)),
        BackgroundPalette("Forest Green", Color(0xFFF1F7F1), Color(0xFF17251F)),
        BackgroundPalette("Deep Plum", Color(0xFFF7F2F7), Color(0xFF241C2B)),
        BackgroundPalette("Warm Charcoal", Color(0xFFF5F3F0), Color(0xFF1E1E24)),
        BackgroundPalette("Midnight Blue", Color(0xFFEBF1F5), Color(0xFF0F1A24)),
        BackgroundPalette("Emerald Glow", Color(0xFFEEF5F2), Color(0xFF12221D)),
        BackgroundPalette("Sunset Amber", Color(0xFFFAF4EE), Color(0xFF261D1A))
    )
}

class AppPreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("layerhub_preferences", Context.MODE_PRIVATE)

    private val _themeMode = MutableStateFlow(loadThemeMode())
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    private val _paletteIndex = MutableStateFlow(loadPaletteIndex())
    val paletteIndex: StateFlow<Int> = _paletteIndex.asStateFlow()

    private val _backendUrl = MutableStateFlow(loadBackendUrl())
    val backendUrl: StateFlow<String> = _backendUrl.asStateFlow()

    private fun loadThemeMode(): ThemeMode {
        val name = prefs.getString(KEY_THEME_MODE, ThemeMode.SYSTEM.name) ?: ThemeMode.SYSTEM.name
        return runCatching { ThemeMode.valueOf(name) }.getOrDefault(ThemeMode.SYSTEM)
    }

    private fun loadPaletteIndex(): Int {
        val index = prefs.getInt(KEY_PALETTE_INDEX, 0)
        return if (index in Palettes.choices.indices) index else 0
    }

    private fun loadBackendUrl(): String {
        return prefs.getString(KEY_BACKEND_URL, DEFAULT_BACKEND_URL) ?: DEFAULT_BACKEND_URL
    }

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME_MODE, mode.name).apply()
        _themeMode.value = mode
    }

    fun setPaletteIndex(index: Int) {
        val validIndex = if (index in Palettes.choices.indices) index else 0
        prefs.edit().putInt(KEY_PALETTE_INDEX, validIndex).apply()
        _paletteIndex.value = validIndex
    }

    fun setBackendUrl(url: String) {
        val cleaned = url.trim().trimEnd('/')
        prefs.edit().putString(KEY_BACKEND_URL, cleaned).apply()
        _backendUrl.value = cleaned
    }

    companion object {
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_PALETTE_INDEX = "palette_index"
        private const val KEY_BACKEND_URL = "backend_url"
        // 10.0.2.2 points to host machine loopback inside Android Emulator
        // For physical device on same WiFi, user can enter PC's LAN IP in Settings
        const val DEFAULT_BACKEND_URL = "http://10.0.2.2:3000"
    }
}
