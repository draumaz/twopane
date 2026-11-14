package io.github.draumaz.twopane.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.draumaz.twopane.TwoPaneApp
import io.github.draumaz.twopane.data.Book
import io.github.draumaz.twopane.data.BookEntry
import io.github.draumaz.twopane.data.Bookmark
import io.github.draumaz.twopane.data.Position
import io.github.draumaz.twopane.data.ReaderSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The open book: still being read in ([book] null), ready, or failed with [error]. */
data class Reading(val id: String, val book: Book? = null, val error: String? = null)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val twoPane = app as TwoPaneApp
    val library = twoPane.library
    val books: StateFlow<List<BookEntry>> = library.entries
    val settingsStore = twoPane.settings
    val settings: StateFlow<ReaderSettings> = twoPane.settings.reader

    val reading = MutableStateFlow<Reading?>(null)
    val importing = MutableStateFlow(0)
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val pageKeys = MutableSharedFlow<Int>(extraBufferCapacity = 4)

    /** The last book parsed, kept so closing and reopening it is instant. */
    private var parsed: Book? = null
    private var opening: Job? = null

    init {
        viewModelScope.launch {
            val dropped = library.importDropped()
            if (dropped.isNotEmpty()) messages.emit("Added ${dropped.size} book${if (dropped.size == 1) "" else "s"}")
        }
    }

    fun open(id: String) {
        val entry = books.value.firstOrNull { it.id == id } ?: return
        opening?.cancel()
        parsed?.takeIf { it.id == id }?.let {
            reading.value = Reading(id, it)
            viewModelScope.launch { library.opened(id) }
            return
        }
        reading.value = Reading(id)
        opening = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { library.parse(entry) } }
            result.onSuccess { parsed = it }
            if (reading.value?.id == id) {
                reading.value = result.fold({ Reading(id, it) }, { Reading(id, error = it.message ?: "Couldn't open this book") })
            }
            library.opened(id)
        }
    }

    fun close() {
        opening?.cancel()
        reading.value = null
    }

    fun import(uris: List<Uri>, openWhenDone: Boolean = false) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            importing.update { it + uris.size }
            var last: BookEntry? = null
            var failed = 0
            for (uri in uris) {
                runCatching { library.import(uri) }
                    .onSuccess { last = it }
                    .onFailure { failed++ }
                importing.update { it - 1 }
            }
            if (failed > 0) messages.emit(if (uris.size == 1) "That file couldn't be read as a book" else "$failed of ${uris.size} files couldn't be read")
            if (openWhenDone) last?.let { open(it.id) }
        }
    }

    fun importAndOpen(uri: Uri) = import(listOf(uri), openWhenDone = true)

    fun remove(id: String) {
        viewModelScope.launch {
            if (parsed?.id == id) parsed = null
            library.remove(id)
        }
    }

    fun saveProgress(id: String, position: Position, progress: Float) {
        viewModelScope.launch { library.saveProgress(id, position, progress) }
    }

    fun setBookmarks(id: String, bookmarks: List<Bookmark>) {
        viewModelScope.launch { library.setBookmarks(id, bookmarks) }
    }

    fun updateSettings(change: (ReaderSettings) -> ReaderSettings) = twoPane.settings.update(change)
}
