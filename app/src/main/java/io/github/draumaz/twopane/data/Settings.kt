package io.github.draumaz.twopane.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class PageTheme { Auto, Paper, Sepia, Dusk, Night, Black }

enum class Typeface { Serif, Sans }

enum class ThemeMode { System, Light, Dark }

/** How the reader typesets and colours pages. Everything but [theme] repaginates the book. */
data class ReaderSettings(
    val fontSize: Float = 19f,
    val typeface: Typeface = Typeface.Serif,
    val lineSpacing: Float = 1.5f,
    val justify: Boolean = true,
    val theme: PageTheme = PageTheme.Auto,
) {
    companion object {
        const val MinFont = 13f
        const val MaxFont = 32f
        val Spacings = listOf(1.3f, 1.5f, 1.75f)
    }
}

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("reader", Context.MODE_PRIVATE)

    private val _reader = MutableStateFlow(
        ReaderSettings(
            fontSize = prefs.getFloat("fontSize", 19f),
            typeface = enumOr(prefs.getString("typeface", null), Typeface.Serif),
            lineSpacing = prefs.getFloat("lineSpacing", 1.5f),
            justify = prefs.getBoolean("justify", true),
            theme = enumOr(prefs.getString("theme", null), PageTheme.Auto),
        )
    )
    val reader: StateFlow<ReaderSettings> = _reader.asStateFlow()

    fun update(change: (ReaderSettings) -> ReaderSettings) {
        val s = change(_reader.value)
        _reader.value = s
        prefs.edit {
            putFloat("fontSize", s.fontSize)
            putString("typeface", s.typeface.name)
            putFloat("lineSpacing", s.lineSpacing)
            putBoolean("justify", s.justify)
            putString("theme", s.theme.name)
        }
    }

    private val _dynamicColor = MutableStateFlow(prefs.getBoolean("dynamicColor", true))
    /** Material You ("Monet") colors from the wallpaper; off uses TwoPane's own leather-and-cream palette. */
    val dynamicColor: StateFlow<Boolean> = _dynamicColor.asStateFlow()

    private val _themeMode = MutableStateFlow(enumOr(prefs.getString("themeMode", null), ThemeMode.System))
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    private val _volumeKeys = MutableStateFlow(prefs.getBoolean("volumeKeys", true))
    /** Whether the volume keys turn pages while a book is open. */
    val volumeKeys: StateFlow<Boolean> = _volumeKeys.asStateFlow()

    fun setDynamicColor(on: Boolean) {
        _dynamicColor.value = on
        prefs.edit { putBoolean("dynamicColor", on) }
    }

    fun setThemeMode(mode: ThemeMode) {
        _themeMode.value = mode
        prefs.edit { putString("themeMode", mode.name) }
    }

    fun setVolumeKeys(on: Boolean) {
        _volumeKeys.value = on
        prefs.edit { putBoolean("volumeKeys", on) }
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: default
}
