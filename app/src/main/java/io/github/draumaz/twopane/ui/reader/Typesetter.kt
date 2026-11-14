package io.github.draumaz.twopane.ui.reader

import android.graphics.BitmapFactory
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.draumaz.twopane.data.Block
import io.github.draumaz.twopane.data.BlockKind
import io.github.draumaz.twopane.data.Book
import io.github.draumaz.twopane.data.Inline
import io.github.draumaz.twopane.data.Position
import io.github.draumaz.twopane.data.ReaderSettings
import io.github.draumaz.twopane.data.Typeface
import io.github.draumaz.twopane.ui.theme.GoogleSansFlex
import io.github.draumaz.twopane.ui.theme.PageColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A run of lines [firstLine] until [endLine] of block [block], placed [y] pixels down the text
 * area. An image slice has no lines (-1) and is [height] tall.
 */
class Slice(val block: Int, val firstLine: Int, val endLine: Int, val y: Float, val height: Float)

class Page(val chapter: Int, val slices: List<Slice>, val start: Position, val opensChapter: Boolean)

/** Which side of the spine a page is drawn on, for its gutter shadow and running head. */
enum class Side { Left, Right, Single }

/**
 * Sets a book in pages of [size] pixels. Each block is laid out once at the full text width,
 * and pages take whole lines from those layouts, so a paragraph broken across a page turn keeps
 * its justification and hyphenation exactly as if it hadn't been.
 */
class Typesetter(
    val book: Book,
    private val settings: ReaderSettings,
    val size: IntSize,
    private val density: Density,
    private val fonts: FontFamily.Resolver,
) {
    private val px = density.density
    val marginX = (size.width * 0.085f).coerceAtLeast(22 * px).roundToInt()
    val marginTop = (52 * px).roundToInt()
    val marginBottom = (54 * px).roundToInt()
    val textWidth = size.width - 2 * marginX
    val textHeight = size.height - marginTop - marginBottom

    private val family = when (settings.typeface) {
        Typeface.Serif -> FontFamily.Serif
        Typeface.Sans -> GoogleSansFlex
    }
    private val fontPx = with(density) { settings.fontSize.sp.toPx() }
    private val lineHeightPx = fontPx * settings.lineSpacing

    private val body = TextStyle(
        fontFamily = family,
        fontSize = settings.fontSize.sp,
        lineHeight = (settings.fontSize * settings.lineSpacing).sp,
        textAlign = if (settings.justify) TextAlign.Justify else TextAlign.Start,
        hyphens = Hyphens.Auto,
        lineBreak = LineBreak.Paragraph,
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
    )

    fun measurer() = TextMeasurer(fonts, density, LayoutDirection.Ltr, cacheSize = 0)

    private class BlockStyle(val style: TextStyle, val inset: Float, val before: Float, val after: Float)

    private fun styleOf(blocks: List<Block>, i: Int): BlockStyle {
        val b = blocks[i]
        val prev = blocks.getOrNull(i - 1)?.kind
        // Classic book setting: no indent on a chapter's or section's first paragraph.
        val indent = b.kind == BlockKind.Body && '\n' !in b.text &&
            prev != null && prev == BlockKind.Body
        return when (b.kind) {
            BlockKind.Heading -> {
                val scale = when (b.level) { 1 -> 1.55f; 2 -> 1.38f; 3 -> 1.2f; else -> 1.06f }
                BlockStyle(
                    body.copy(
                        fontSize = (settings.fontSize * scale).sp,
                        lineHeight = (settings.fontSize * scale * 1.22f).sp,
                        fontWeight = if (b.level <= 2) FontWeight.Normal else FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        hyphens = Hyphens.None,
                        lineBreak = LineBreak.Heading,
                    ),
                    inset = 0f, before = fontPx * 1.6f, after = fontPx * 1.1f,
                )
            }
            BlockKind.Quote -> BlockStyle(
                body.copy(fontSize = (settings.fontSize * 0.95f).sp, textIndent = TextIndent()),
                inset = fontPx * 1.4f, before = fontPx * 0.5f, after = fontPx * 0.5f,
            )
            BlockKind.Verse -> BlockStyle(
                // Verse keeps its ragged right edge; a line too long to fit hangs indented.
                body.copy(textAlign = TextAlign.Start, hyphens = Hyphens.None, textIndent = TextIndent(restLine = 1.5.em)),
                inset = fontPx * 0.4f, before = fontPx * 0.15f, after = fontPx * 0.15f,
            )
            BlockKind.Center, BlockKind.Rule -> BlockStyle(
                body.copy(textAlign = TextAlign.Center, hyphens = Hyphens.None),
                inset = 0f, before = fontPx * (if (b.kind == BlockKind.Rule) 0.9f else 0.3f), after = fontPx * (if (b.kind == BlockKind.Rule) 0.9f else 0.3f),
            )
            BlockKind.Pre -> BlockStyle(
                body.copy(
                    fontFamily = FontFamily.Monospace, fontSize = (settings.fontSize * 0.8f).sp,
                    lineHeight = (settings.fontSize * 1.25f).sp, textAlign = TextAlign.Start, hyphens = Hyphens.None,
                ),
                inset = fontPx * 0.5f, before = fontPx * 0.5f, after = fontPx * 0.5f,
            )
            BlockKind.ListItem -> BlockStyle(
                body.copy(textAlign = TextAlign.Start, textIndent = TextIndent(restLine = 1.1.em)),
                inset = fontPx * (0.6f + 0.8f * (b.level - 1).coerceAtLeast(0)), before = fontPx * 0.15f, after = fontPx * 0.15f,
            )
            BlockKind.Image -> BlockStyle(body, 0f, fontPx * 0.8f, fontPx * 0.8f)
            BlockKind.Body -> BlockStyle(
                body.copy(textIndent = if (indent) TextIndent(firstLine = 1.5.em) else TextIndent()),
                inset = 0f, before = 0f, after = 0f,
            )
        }
    }

    private fun annotated(b: Block): AnnotatedString = buildAnnotatedString {
        append(b.text)
        for (s in b.spans) {
            if (s.start >= s.end || s.end > b.text.length) continue
            addStyle(
                SpanStyle(
                    fontWeight = if (s.bits and Inline.BOLD != 0) FontWeight.Bold else null,
                    fontStyle = if (s.bits and Inline.ITALIC != 0) FontStyle.Italic else null,
                    fontFamily = if (s.bits and Inline.MONO != 0) FontFamily.Monospace else null,
                    fontSize = if (s.bits and (Inline.SMALL or Inline.SUPER) != 0) 0.75.em else androidx.compose.ui.unit.TextUnit.Unspecified,
                    baselineShift = if (s.bits and Inline.SUPER != 0) BaselineShift.Superscript else null,
                ),
                s.start, s.end,
            )
        }
    }

    private fun measure(m: TextMeasurer, blocks: List<Block>, i: Int): Pair<TextLayoutResult, BlockStyle> {
        val st = styleOf(blocks, i)
        val width = (textWidth - 2 * st.inset).roundToInt().coerceAtLeast(1)
        return m.measure(annotated(blocks[i]), st.style, constraints = Constraints.fixedWidth(width)) to st
    }

    private val imageSizes = HashMap<String, IntSize?>()

    private fun imageSize(path: String): IntSize? = synchronized(imageSizes) {
        imageSizes.getOrPut(path) {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            book.resources.open(path)?.use { BitmapFactory.decodeStream(it, null, o) }
            if (o.outWidth > 0 && o.outHeight > 0) IntSize(o.outWidth, o.outHeight) else null
        }
    }

    /** An inline image's drawn size: EPUB pixels at about 1.5 per dp, never past the text area. */
    private fun imageFit(sz: IntSize, maxHeight: Float): Size {
        val scale = min(min(textWidth.toFloat() / sz.width, maxHeight / sz.height), px / 1.5f)
        return Size(sz.width * scale, sz.height * scale)
    }

    /** Every chapter at once, in parallel; a chapter always starts a new page. */
    suspend fun paginate(): List<Page> = coroutineScope {
        book.chapters.indices.map { c -> async(Dispatchers.Default) { paginateChapter(c) } }.awaitAll().flatten()
    }

    private fun paginateChapter(c: Int): List<Page> {
        val blocks = book.chapters[c].blocks
        val m = measurer()
        val pages = mutableListOf<Page>()
        var slices = mutableListOf<Slice>()
        var y = 0f
        var start: Position? = null
        val h = textHeight.toFloat()
        fun flush() {
            if (slices.isNotEmpty()) pages += Page(c, slices, start!!, opensChapter = pages.isEmpty())
            slices = mutableListOf()
            y = 0f
            start = null
        }
        for (i in blocks.indices) {
            val b = blocks[i]
            if (b.kind == BlockKind.Image) {
                val sz = b.image?.let(::imageSize) ?: continue
                if (b.level == 1) {
                    // A cover takes a page of its own, edge to edge.
                    flush()
                    start = Position(c, i, 0)
                    slices += Slice(i, -1, -1, 0f, h)
                    flush()
                    continue
                }
                val st = styleOf(blocks, i)
                val fit = imageFit(sz, h)
                var top = if (y == 0f) 0f else y + st.before
                if (top + fit.height > h) {
                    flush()
                    top = 0f
                }
                if (start == null) start = Position(c, i, 0)
                slices += Slice(i, -1, -1, top, fit.height)
                y = top + fit.height + st.after
                continue
            }
            val (layout, st) = measure(m, blocks, i)
            val n = layout.lineCount
            var line = 0
            while (line < n) {
                // A chapter's opening heading sinks a little way down its first page.
                var top = when {
                    line == 0 && y == 0f && pages.isEmpty() && slices.isEmpty() && b.kind == BlockKind.Heading -> h * 0.12f
                    y == 0f -> 0f
                    line == 0 -> y + st.before
                    else -> y
                }
                val lineTop = layout.getLineTop(line)
                var end = line
                while (end < n && top + (layout.getLineBottom(end) - lineTop) <= h + 0.5f) end++
                if (end == line) {
                    if (slices.isEmpty()) end = line + 1 else { flush(); continue }
                }
                val height = layout.getLineBottom(end - 1) - lineTop
                // Keep a heading with at least two lines of what follows it.
                if (b.kind == BlockKind.Heading && end == n && slices.isNotEmpty() &&
                    h - (top + height + st.after) < 2 * lineHeightPx
                ) { flush(); continue }
                // No lone first line of a paragraph at the foot of a page (an orphan)...
                if (line == 0 && end == 1 && n > 1 && slices.isNotEmpty()) { flush(); continue }
                // ...and no lone last line at the head of the next (a widow).
                if (end == n - 1 && end - line >= 3) end--
                val sliceHeight = layout.getLineBottom(end - 1) - lineTop
                if (start == null) start = Position(c, i, layout.getLineStart(line))
                slices += Slice(i, line, end, top, sliceHeight)
                y = top + sliceHeight
                line = end
                if (line < n) flush()
            }
            if (y > 0f) y += st.after
        }
        flush()
        return pages
    }

    // Rendering

    private val headStyle = TextStyle(
        fontFamily = GoogleSansFlex,
        fontSize = (settings.fontSize * 0.6f).coerceIn(10.5f, 14f).sp,
        letterSpacing = 0.14.em,
        fontWeight = FontWeight.Medium,
        textAlign = TextAlign.Center,
    )

    /** Draws page [number] (counting from 1) of [page] into a new bitmap the size of a page. */
    fun render(m: TextMeasurer, page: Page, number: Int, side: Side, colors: PageColors): ImageBitmap {
        val bmp = ImageBitmap(size.width, size.height, ImageBitmapConfig.Argb8888)
        val canvas = Canvas(bmp)
        val w = size.width.toFloat()
        val hgt = size.height.toFloat()
        val chapter = book.chapters[page.chapter]
        CanvasDrawScope().draw(density, LayoutDirection.Ltr, canvas, Size(w, hgt)) {
            drawRect(colors.paper)
            val cover = page.slices.singleOrNull()?.takeIf { it.firstLine < 0 && chapter.blocks[it.block].level == 1 }
            if (cover != null) {
                chapter.blocks[cover.block].image?.let { path ->
                    decodeImage(path, this@Typesetter.size.width, this@Typesetter.size.height)?.let { img ->
                        val scale = min(w / img.width, hgt / img.height)
                        val dw = (img.width * scale).roundToInt()
                        val dh = (img.height * scale).roundToInt()
                        drawImage(img, dstOffset = IntOffset(((w - dw) / 2).roundToInt(), ((hgt - dh) / 2).roundToInt()), dstSize = IntSize(dw, dh))
                    }
                }
            } else {
                translate(marginX.toFloat(), marginTop.toFloat()) {
                    for (s in page.slices) {
                        val b = chapter.blocks[s.block]
                        if (s.firstLine < 0) {
                            val path = b.image ?: continue
                            val sz = imageSize(path) ?: continue
                            val fit = imageFit(sz, textHeight.toFloat())
                            val img = decodeImage(path, fit.width.roundToInt(), fit.height.roundToInt()) ?: continue
                            drawImage(
                                img,
                                dstOffset = IntOffset(((textWidth - fit.width) / 2).roundToInt(), s.y.roundToInt()),
                                dstSize = IntSize(fit.width.roundToInt(), fit.height.roundToInt()),
                                alpha = if (colors.dark) 0.88f else 1f,
                            )
                            continue
                        }
                        val (layout, st) = measure(m, chapter.blocks, s.block)
                        val lineTop = layout.getLineTop(s.firstLine)
                        clipRect(-marginX.toFloat(), s.y - 2f, textWidth + marginX.toFloat(), s.y + s.height + 2f) {
                            translate(st.inset, s.y - lineTop) { drawText(layout, color = colors.ink) }
                        }
                    }
                }
                // Running head: the book's title on a left page, the chapter's on the right.
                val head = when {
                    page.opensChapter -> null
                    side == Side.Left -> book.title
                    else -> chapter.title ?: book.title
                }
                if (head != null) {
                    val t = m.measure(
                        head.uppercase(), headStyle.copy(color = colors.muted),
                        constraints = Constraints.fixedWidth(textWidth), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    drawText(t, topLeft = Offset(marginX.toFloat(), marginTop * 0.45f - t.size.height / 2f))
                }
                val folio = m.measure(number.toString(), headStyle.copy(color = colors.muted, letterSpacing = 0.04.em), constraints = Constraints.fixedWidth(textWidth))
                drawText(folio, topLeft = Offset(marginX.toFloat(), hgt - marginBottom * 0.5f - folio.size.height / 2f))
            }
            // The page curves down into the spine.
            val gutter = w * 0.07f
            val shade = Color.Black.copy(alpha = if (colors.dark) 0.32f else 0.085f)
            when (side) {
                Side.Left -> drawRect(Brush.horizontalGradient(listOf(Color.Transparent, shade), startX = w - gutter, endX = w), Offset(w - gutter, 0f), Size(gutter, hgt))
                Side.Right -> drawRect(Brush.horizontalGradient(listOf(shade, Color.Transparent), startX = 0f, endX = gutter), Offset.Zero, Size(gutter, hgt))
                Side.Single -> Unit
            }
        }
        return bmp
    }

    private fun decodeImage(path: String, width: Int, height: Int): ImageBitmap? = runCatching {
        val sz = imageSize(path) ?: return null
        var sample = 1
        while (sz.width / (sample * 2) >= width && sz.height / (sample * 2) >= height) sample *= 2
        book.resources.open(path)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        }?.asImageBitmap()
    }.getOrNull()

    /** A short excerpt of a page's text, to label a bookmark. */
    fun excerpt(page: Page): String {
        val blocks = book.chapters[page.chapter].blocks
        val s = page.slices.firstOrNull { it.firstLine >= 0 } ?: return book.chapters[page.chapter].title ?: book.title
        val text = blocks[s.block].text.substring(page.start.char.coerceAtMost(blocks[s.block].text.length).takeIf { page.start.block == s.block } ?: 0)
        return text.replace('\n', ' ').take(90).trim()
    }
}

/** The page holding [pos]: the last page starting at or before it. */
fun List<Page>.indexFor(pos: Position): Int {
    var lo = 0
    var hi = size - 1
    var best = 0
    while (lo <= hi) {
        val mid = (lo + hi) / 2
        if (this[mid].start <= pos) {
            best = mid
            lo = mid + 1
        } else hi = mid - 1
    }
    return best
}
