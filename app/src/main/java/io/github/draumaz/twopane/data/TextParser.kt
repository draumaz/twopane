package io.github.draumaz.twopane.data

import java.io.File

/**
 * Plain-text books: paragraphs split at blank lines (hard-wrapped lines rejoined), chapters at
 * lines like "CHAPTER IV" or "Book Two", and Project Gutenberg's licence header and footer
 * trimmed away.
 */
object TextParser {
    private val ChapterLine = Regex(
        "^(chapter|book|part|canto|section|letter|volume|act|scene|prologue|epilogue|preface|introduction|rune)\\b.{0,60}$|^[IVXLC]{1,7}\\.?$",
        RegexOption.IGNORE_CASE,
    )

    fun read(file: File): String {
        val bytes = file.readBytes()
        val utf8 = String(bytes, Charsets.UTF_8)
        // Not valid UTF-8 (replacement characters): older texts are usually Latin-1.
        val text = if ('�' in utf8) String(bytes, Charsets.ISO_8859_1) else utf8
        return text.removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n')
    }

    fun title(file: File): Pair<String, String?> {
        val head = read(file).lineSequence().take(60).toList()
        val title = head.firstOrNull { it.startsWith("Title:") }?.substringAfter(':')?.trim()
        val author = head.firstOrNull { it.startsWith("Author:") }?.substringAfter(':')?.trim()
        return (title ?: file.nameWithoutExtension.replace('_', ' ')) to author
    }

    fun parse(file: File, id: String, title: String, author: String?): Book {
        var lines = read(file).lines()
        val start = lines.indexOfFirst { it.startsWith("*** START OF") }
        val end = lines.indexOfFirst { it.startsWith("*** END OF") }
        if (start >= 0 && end > start) lines = lines.subList(start + 1, end)

        val paragraphs = mutableListOf<String>()
        val current = StringBuilder()
        val hasBlankLines = lines.count { it.isBlank() } > lines.size / 20
        for (line in lines) {
            if (line.isBlank() || !hasBlankLines) {
                if (!hasBlankLines && line.isNotBlank()) current.append(line.trim())
                if (current.isNotEmpty()) paragraphs += current.toString()
                current.setLength(0)
            } else {
                if (current.isNotEmpty()) current.append(' ')
                current.append(line.trim())
            }
        }
        if (current.isNotEmpty()) paragraphs += current.toString()

        val chapters = mutableListOf<Chapter>()
        val toc = mutableListOf<TocEntry>()
        var blocks = mutableListOf<Block>()
        var name: String? = null
        fun close() {
            if (blocks.isNotEmpty()) chapters += Chapter(name, blocks, emptyMap())
            blocks = mutableListOf()
        }
        for (p in paragraphs) {
            if (p.length <= 70 && ChapterLine.matches(p)) {
                close()
                name = p
                toc += TocEntry(p, chapters.size, 0, 0)
                blocks += Block(BlockKind.Heading, p, level = 2)
            } else {
                blocks += Block(BlockKind.Body, p)
                // No chapter headings at all: cut long texts into sections so pagination stays parallel.
                if (toc.isEmpty() && blocks.size >= 400) close()
            }
        }
        close()
        return Book(id, title, author, chapters, toc)
    }
}
