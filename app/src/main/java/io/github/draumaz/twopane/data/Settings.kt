package io.github.draumaz.twopane.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class PageTheme { Auto, Paper, Sepia, Dusk, Night, Black }

enum class Typeface { Serif, Sans }

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
        prefs.edit()
            .putFloat("fontSize", s.fontSize)
            .putString("typeface", s.typeface.name)
            .putFloat("lineSpacing", s.lineSpacing)
            .putBoolean("justify", s.justify)
            .putString("theme", s.theme.name)
            .apply()
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: default
}
