package io.github.draumaz.twopane.data

import java.io.Closeable
import java.io.InputStream

/** What a block of a chapter is, which decides how it's typeset. */
enum class BlockKind { Body, Heading, Quote, Verse, Center, Pre, ListItem, Rule, Image }

/** Inline style bits for a [Span]. */
object Inline {
    const val BOLD = 1
    const val ITALIC = 2
    const val SMALL = 4
    const val MONO = 8
    const val SUPER = 16
}

/** A run of [Inline] styles over [start] until [end] of its block's text. */
data class Span(val start: Int, val end: Int, val bits: Int)

/**
 * One paragraph-level piece of a chapter: a paragraph, heading, quotation, rule or image.
 * Pages break between and inside blocks, never anywhere else.
 */
class Block(
    val kind: BlockKind,
    val text: String,
    val spans: List<Span> = emptyList(),
    /** Heading level (1–6); for images, 1 marks a full-page cover. */
    val level: Int = 0,
    /** Path of an image inside the book's archive. */
    val image: String? = null,
)

class Chapter(
    val title: String?,
    val blocks: List<Block>,
    /** Element ids (link targets) and the block each one starts at. */
    val anchors: Map<String, Int>,
)

class TocEntry(val title: String, val chapter: Int, val block: Int, val depth: Int)

/** Opens resources (images) inside a book's file; null if missing. */
fun interface BookResources {
    fun open(path: String): InputStream?
}

class Book(
    val id: String,
    val title: String,
    val author: String?,
    val chapters: List<Chapter>,
    val toc: List<TocEntry>,
    val resources: BookResources = BookResources { null },
    /** Releases whatever [resources] reads from (an EPUB's open archive). */
    private val onClose: () -> Unit = {},
) : Closeable {
    override fun close() = onClose()
}

/** A place in a book that survives repagination: a character in a block of a chapter. */
data class Position(val chapter: Int, val block: Int, val char: Int) : Comparable<Position> {
    override fun compareTo(other: Position): Int =
        compareValuesBy(this, other, Position::chapter, Position::block, Position::char)

    fun encode(): String = "$chapter:$block:$char"

    companion object {
        val Start = Position(0, 0, 0)
        fun decode(s: String?): Position? = s?.split(':')?.takeIf { it.size == 3 }?.let { parts ->
            val n = parts.map { it.toIntOrNull() ?: return null }
            Position(n[0], n[1], n[2])
        }
    }
}

enum class BookFormat(val extension: String) { Epub("epub"), Text("txt") }

data class Bookmark(val position: Position, val label: String, val createdAt: Long)

/** A book on the shelf, as stored in the library index. */
data class BookEntry(
    val id: String,
    val title: String,
    val author: String?,
    val format: BookFormat,
    val addedAt: Long,
    val openedAt: Long = 0L,
    /** How far through the book, 0 to 1. */
    val progress: Float = 0f,
    val position: Position? = null,
    val hasCover: Boolean = false,
    val bookmarks: List<Bookmark> = emptyList(),
)
