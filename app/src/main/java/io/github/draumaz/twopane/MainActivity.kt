package io.github.draumaz.twopane

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.draumaz.twopane.data.ThemeMode
import io.github.draumaz.twopane.ui.AppRoot
import io.github.draumaz.twopane.ui.AppViewModel
import io.github.draumaz.twopane.ui.theme.TwoPaneTheme

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handle(intent)
        val settings = (application as TwoPaneApp).settings
        setContent {
            val dynamicColor by settings.dynamicColor.collectAsStateWithLifecycle()
            val themeMode by settings.themeMode.collectAsStateWithLifecycle()
            val dark = when (themeMode) {
                ThemeMode.System -> isSystemInDarkTheme()
                ThemeMode.Light -> false
                ThemeMode.Dark -> true
            }
            DisposableEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT)
                else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose { }
            }
            TwoPaneTheme(darkTheme = dark, dynamicColor = dynamicColor) {
                AppRoot(vm, openExternal = ::openExternal)
            }
        }
        // Compose paints edge to edge, so the theme's window background would only be overdraw.
        window.setBackgroundDrawable(null)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /** "Open with TwoPane" from a file manager, or a book shared from another app. */
    private fun handle(intent: Intent?) {
        intent ?: return
        val uri: Uri? = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            else -> null
        }
        uri?.let { vm.importAndOpen(it) }
    }

    private fun openExternal(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (_: ActivityNotFoundException) {
        }
    }

    /** The page a key turns to while a book is open (1 forward, -1 back), or 0 if it doesn't turn pages. */
    private fun turnFor(keyCode: Int): Int {
        if (vm.reading.value == null || !(application as TwoPaneApp).settings.volumeKeys.value) return 0
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> 1
            KeyEvent.KEYCODE_VOLUME_UP -> -1
            KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT -> 1
            KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_DPAD_LEFT -> -1
            else -> 0
        }
    }

    /** The volume keys turn pages while a book is open, instead of changing the volume. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val dir = turnFor(keyCode)
        if (dir == 0) return super.onKeyDown(keyCode, event)
        vm.pageKeys.tryEmit(dir)
        return true
    }

    // Swallowed too, or the volume key's release still plays the system's click.
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        turnFor(keyCode) != 0 || super.onKeyUp(keyCode, event)
}
