package io.github.draumaz.twopane

import android.app.Application
import io.github.draumaz.twopane.data.Library
import io.github.draumaz.twopane.data.SettingsStore

class TwoPaneApp : Application() {
    val library by lazy { Library(this) }
    val settings by lazy { SettingsStore(this) }
}
