package io.github.draumaz.twopane.data

import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/**
 * Flattens an (X)HTML chapter into [Block]s: block-level elements become paragraphs, inline
 * elements become [Span]s, images get blocks of their own. [resolve] turns an image's src into
 * a path inside the book.
 */
class HtmlBlocks(private val resolve: (String) -> String?) {
    val blocks = mutableListOf<Block>()
    val anchors = HashMap<String, Int>()

    private val text = StringBuilder()
    private val spans = mutableListOf<Span>()
    private var kind = BlockKind.Body
    private var level = 0
    private var listDepth = 0

    /** A list item's bullet or number, written ahead of the item's first text. */
    private var marker: String? = null

    fun read(body: Element): HtmlBlocks {
        walk(body, 0)
        flush()
        return this
    }

    private fun walk(node: Node, bits: Int) {
        when (node) {
            is TextNode -> append(node.wholeText, bits)
            is Element -> element(node, bits)
        }
    }

    private fun children(e: Element, bits: Int) {
        for (child in e.childNodes()) walk(child, bits)
    }

    private fun element(e: Element, bits: Int) {
        val tag = e.normalName().substringAfter(':')
        e.id().takeIf { it.isNotBlank() }?.let { anchors.putIfAbsent(it, blocks.size) }
        e.attr("name").takeIf { tag == "a" && it.isNotBlank() }?.let { anchors.putIfAbsent(it, blocks.size) }
        // Navigation aids that aren't reading matter: print page numbers, landmarks.
        if (tag == "nav" && e.attr("epub:type").let { it == "page-list" || it == "landmarks" }) return
        if (e.attr("epub:type") == "pagebreak" || e.attr("role") == "doc-pagebreak") return
        // Printed line numbers in verse: noise on a reflowed page, and they glue onto the line's last word.
        val cls = e.className().lowercase()
        if (LineNumber.containsMatchIn(cls)) return
        when (tag) {
            "script", "style", "head", "title", "noscript", "template", "rt", "rp" -> Unit
            "br" -> newline()
            "img", "image" -> {
                val src = e.attr("src").ifBlank { e.attr("xlink:href").ifBlank { e.attr("href") } }
                val path = src.takeIf { it.isNotBlank() }?.let(resolve)
                if (path != null) {
                    flush()
                    blocks += Block(BlockKind.Image, e.attr("alt"), image = path)
                } else {
                    e.attr("alt").takeIf { it.isNotBlank() }?.let { append(it, bits) }
                }
            }
            "svg" -> children(e, bits) // An SVG wrapper around a cover <image>.
            "hr" -> {
                flush()
                blocks += Block(BlockKind.Rule, "⁂")
            }
            "h1", "h2", "h3", "h4", "h5", "h6" -> block(BlockKind.Heading, tag[1].digitToInt()) { children(e, bits) }
            "blockquote" -> block(BlockKind.Quote) { children(e, bits) }
            "pre" -> block(BlockKind.Pre) { children(e, bits or Inline.MONO) }
            "center" -> block(BlockKind.Center) { children(e, bits) }
            "ul", "ol" -> {
                listDepth++
                block(kind) { children(e, bits) }
                listDepth--
            }
            "li" -> block(BlockKind.ListItem, listDepth) {
                marker = if (e.parent()?.normalName() == "ol") "${e.elementSiblingIndex() + 1}. " else "• "
                children(e, bits)
                marker = null
            }
            "p", "div", "section", "article", "body", "main", "header", "footer", "aside", "figure",
            "figcaption", "dd", "dt", "dl", "table", "tbody", "thead", "tr", "td", "th", "caption",
            "address", "nav", "html", "details", "summary",
            -> {
                val k = when {
                    kind != BlockKind.Body -> kind
                    Poetry.containsMatchIn(cls) -> BlockKind.Verse
                    isCentered(e) -> BlockKind.Center
                    else -> kind
                }
                block(k, level) { children(e, bits) }
            }
            else -> children(e, bits or inlineBits(tag, e))
        }
    }

    private fun inlineBits(tag: String, e: Element): Int {
        var b = when (tag) {
            "b", "strong" -> Inline.BOLD
            "i", "em", "cite", "dfn", "var", "q" -> Inline.ITALIC
            "code", "tt", "kbd", "samp" -> Inline.MONO
            "sup", "sub" -> Inline.SUPER
            "small" -> Inline.SMALL
            else -> 0
        }
        val style = e.attr("style").lowercase()
        if ("italic" in style) b = b or Inline.ITALIC
        if (Regex("font-weight:\\s*(bold|[6-9]00)").containsMatchIn(style)) b = b or Inline.BOLD
        val cls = e.className().lowercase()
        if ("italic" in cls || cls == "i" || cls == "em") b = b or Inline.ITALIC
        if ("bold" in cls) b = b or Inline.BOLD
        if ("smallcap" in cls || "small-cap" in cls) b = b or Inline.SMALL
        return b
    }

    private fun isCentered(e: Element): Boolean {
        val style = e.attr("style").replace(" ", "").lowercase()
        if ("text-align:center" in style) return true
        val cls = e.className().lowercase()
        return cls.split(' ').any { it == "center" || it == "centered" || it == "centre" || it.startsWith("center") }
    }

    private inline fun block(k: BlockKind, l: Int = 0, body: () -> Unit) {
        flush()
        val (oldKind, oldLevel) = kind to level
        // Inside a quotation or list, paragraphs keep that styling.
        if (!(k == BlockKind.Body && kind != BlockKind.Body)) {
            kind = k
            level = l
        }
        body()
        flush()
        kind = oldKind
        level = oldLevel
    }

    private fun append(raw: String, bits: Int) {
        val s = if (kind == BlockKind.Pre) raw else {
            val collapsed = raw.replace(Whitespace, " ")
            if (text.isEmpty() || text.last() == ' ' || text.last() == '\n') collapsed.trimStart() else collapsed
        }
        if (s.isEmpty()) return
        marker?.let {
            text.append(it)
            marker = null
        }
        val start = text.length
        text.append(s)
        if (bits != 0) spans += Span(start, text.length, bits)
    }

    private fun newline() {
        while (text.isNotEmpty() && text.last() == ' ') text.setLength(text.length - 1)
        if (text.isNotEmpty()) text.append('\n')
    }

    private fun flush() {
        var end = text.length
        while (end > 0 && text[end - 1].isWhitespace()) end--
        if (end > 0) {
            val t = text.substring(0, end)
            blocks += Block(
                kind,
                t,
                spans.mapNotNull { sp -> if (sp.start >= end) null else sp.copy(end = minOf(sp.end, end)) },
                level,
            )
        }
        text.setLength(0)
        spans.clear()
    }

    private companion object {
        val Whitespace = Regex("\\s+")
        val LineNumber = Regex("line[_-]?num|lineno|linenum")
        val Poetry = Regex("poe(try|m)|verse|stanza|^line$| line\\b")
    }
}
