package io.github.draumaz.twopane.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * The shelf. Each book is copied into app storage as books/<id>/book.<ext> (the id is a hash of
 * its bytes, so importing the same file twice finds the copy already there), with its cover
 * extracted beside it. The index, reading positions and bookmarks live in library.json.
 */
class Library(private val context: Context) {
    private val root = File(context.filesDir, "books").apply { mkdirs() }
    private val indexFile = File(context.filesDir, "library.json")
    private val lock = Mutex()

    private val _entries = MutableStateFlow(load())
    val entries: StateFlow<List<BookEntry>> = _entries.asStateFlow()

    /** Drop books here over adb (Android/data/<package>/files/import) and they're shelved on launch. */
    val dropFolder: File? get() = context.getExternalFilesDir("import")?.apply { mkdirs() }

    fun bookFile(e: BookEntry) = File(File(root, e.id), "book.${e.format.extension}")
    fun coverFile(id: String) = File(File(root, id), "cover.jpg")

    suspend fun import(uri: Uri): BookEntry = withContext(Dispatchers.IO) {
        val name = displayName(uri)
        val input = context.contentResolver.openInputStream(uri) ?: error("Couldn't open $name")
        input.use { importStream(it, name) }
    }

    suspend fun importDropped(): List<BookEntry> = withContext(Dispatchers.IO) {
        val files = dropFolder?.listFiles()?.filter { it.isFile }.orEmpty()
        files.mapNotNull { f ->
            runCatching { f.inputStream().use { importStream(it, f.name) } }.getOrNull().also { f.delete() }
        }
    }

    private suspend fun importStream(input: InputStream, name: String): BookEntry {
        val tmp = File.createTempFile("import", null, context.cacheDir)
        try {
            val digest = MessageDigest.getInstance("SHA-1")
            tmp.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                    out.write(buf, 0, n)
                }
            }
            val id = digest.digest().take(10).joinToString("") { "%02x".format(it) }
            entries.value.firstOrNull { it.id == id }?.let { return it }

            val isZip = tmp.inputStream().use { s -> ByteArray(2).also { s.read(it) }.contentEquals(byteArrayOf(0x50, 0x4B)) }
            val format = if (isZip) BookFormat.Epub else BookFormat.Text
            val dir = File(root, id).apply { mkdirs() }
            val dest = File(dir, "book.${format.extension}")
            tmp.copyTo(dest, overwrite = true)

            val entry = when (format) {
                BookFormat.Epub -> {
                    val meta = runCatching { EpubParser.readMeta(dest) }.getOrElse {
                        dir.deleteRecursively()
                        throw IllegalArgumentException("$name isn't a readable EPUB")
                    }
                    val cover = meta.coverPath?.let { saveCover(dest, it, coverFile(id)) } ?: false
                    BookEntry(id, meta.title ?: name.substringBeforeLast('.'), meta.author, format, System.currentTimeMillis(), hasCover = cover)
                }
                BookFormat.Text -> {
                    val (title, author) = TextParser.title(dest)
                    BookEntry(id, if (title == dest.nameWithoutExtension) name.substringBeforeLast('.') else title, author, format, System.currentTimeMillis())
                }
            }
            mutate { it + entry }
            return entry
        } finally {
            tmp.delete()
        }
    }

    /** Decodes the cover from the archive, scaled down to at most 720px tall, as a JPEG. */
    private fun saveCover(epub: File, path: String, out: File): Boolean = runCatching {
        ZipFile(epub).use { zip ->
            val entry = zip.getEntry(path) ?: return false
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            zip.getInputStream(entry).use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (bounds.outHeight / (sample * 2) >= 720) sample *= 2
            val bmp = zip.getInputStream(entry).use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: return false
            out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            bmp.recycle()
            true
        }
    }.getOrDefault(false)

    fun parse(entry: BookEntry): Book {
        val book = when (entry.format) {
            BookFormat.Epub -> EpubParser.parse(bookFile(entry), entry.id)
            BookFormat.Text -> TextParser.parse(bookFile(entry), entry.id, entry.title, entry.author)
        }
        if (book.chapters.all { it.blocks.isEmpty() }) {
            book.close()
            error("There's nothing in it to read.")
        }
        return book
    }

    suspend fun opened(id: String) = mutate { list -> list.map { if (it.id == id) it.copy(openedAt = System.currentTimeMillis()) else it } }

    suspend fun saveProgress(id: String, position: Position, progress: Float) = mutate { list ->
        list.map { if (it.id == id) it.copy(position = position, progress = progress) else it }
    }

    suspend fun setBookmarks(id: String, bookmarks: List<Bookmark>) = mutate { list ->
        list.map { if (it.id == id) it.copy(bookmarks = bookmarks) else it }
    }

    suspend fun remove(id: String) {
        mutate { list -> list.filterNot { it.id == id } }
        withContext(Dispatchers.IO) { File(root, id).deleteRecursively() }
    }

    private suspend fun mutate(change: (List<BookEntry>) -> List<BookEntry>) = lock.withLock {
        _entries.update(change)
        withContext(Dispatchers.IO) {
            val tmp = File(indexFile.path + ".tmp")
            tmp.writeText(encode(_entries.value).toString())
            tmp.renameTo(indexFile)
        }
    }

    private fun displayName(uri: Uri): String =
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "book"

    private fun load(): List<BookEntry> = runCatching {
        val arr = JSONArray(indexFile.readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val marks = o.optJSONArray("bookmarks")
            BookEntry(
                id = o.getString("id"),
                title = o.getString("title"),
                author = o.optString("author").ifBlank { null },
                format = BookFormat.valueOf(o.getString("format")),
                addedAt = o.getLong("addedAt"),
                openedAt = o.optLong("openedAt"),
                progress = o.optDouble("progress", 0.0).toFloat(),
                position = Position.decode(o.optString("position").ifBlank { null }),
                hasCover = o.optBoolean("hasCover"),
                bookmarks = (0 until (marks?.length() ?: 0)).mapNotNull { j ->
                    val m = marks!!.getJSONObject(j)
                    Position.decode(m.getString("at"))?.let { Bookmark(it, m.optString("label"), m.optLong("createdAt")) }
                },
            )
        }
    }.getOrDefault(emptyList())

    private fun encode(list: List<BookEntry>) = JSONArray().apply {
        list.forEach { e ->
            put(JSONObject().apply {
                put("id", e.id)
                put("title", e.title)
                put("author", e.author ?: "")
                put("format", e.format.name)
                put("addedAt", e.addedAt)
                put("openedAt", e.openedAt)
                put("progress", e.progress.toDouble())
                put("position", e.position?.encode() ?: "")
                put("hasCover", e.hasCover)
                put("bookmarks", JSONArray().apply {
                    e.bookmarks.forEach { b ->
                        put(JSONObject().put("at", b.position.encode()).put("label", b.label).put("createdAt", b.createdAt))
                    }
                })
            })
        }
    }
}
