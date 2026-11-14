package io.github.draumaz.twopane.data

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.io.File
import java.net.URLDecoder
import java.util.zip.ZipFile

/** Reads EPUB 2 and 3 files: metadata, the spine as chapters, and the table of contents. */
object EpubParser {
    class Meta(val title: String?, val author: String?, val coverPath: String?)

    private class Package(
        val dir: String,
        val doc: Document,
        /** Manifest id to (path in zip, media type, properties). */
        val manifest: Map<String, Triple<String, String, String>>,
    )

    private fun Element.local(): String = tagName().substringAfter(':').lowercase()

    private fun Element.allLocal(name: String): List<Element> = getAllElements().filter { it.local() == name }

    private fun ZipFile.text(path: String): String? =
        getEntry(path)?.let { e -> getInputStream(e).use { it.readBytes().toString(Charsets.UTF_8) } }

    private fun readPackage(zip: ZipFile): Package {
        val container = zip.text("META-INF/container.xml") ?: error("Not an EPUB: no container.xml")
        val opfPath = Jsoup.parse(container, "", Parser.xmlParser()).allLocal("rootfile")
            .firstOrNull()?.attr("full-path") ?: error("Not an EPUB: no package file")
        val opf = zip.text(opfPath) ?: error("Missing package file $opfPath")
        val doc = Jsoup.parse(opf, "", Parser.xmlParser())
        val dir = opfPath.substringBeforeLast('/', "")
        val manifest = doc.allLocal("item").associate {
            it.attr("id") to Triple(resolve(dir, it.attr("href")), it.attr("media-type"), it.attr("properties"))
        }
        return Package(dir, doc, manifest)
    }

    fun readMeta(file: File): Meta = ZipFile(file).use { zip ->
        val pkg = readPackage(zip)
        Meta(
            title = pkg.doc.allLocal("title").firstOrNull()?.text()?.trim()?.ifBlank { null },
            author = pkg.doc.allLocal("creator").firstOrNull()?.text()?.trim()?.ifBlank { null },
            coverPath = coverPath(pkg),
        )
    }

    private fun coverPath(pkg: Package): String? {
        pkg.manifest.values.firstOrNull { "cover-image" in it.third }?.let { return it.first }
        val metaId = pkg.doc.allLocal("meta").firstOrNull { it.attr("name") == "cover" }?.attr("content")
        pkg.manifest[metaId]?.takeIf { it.second.startsWith("image/") }?.let { return it.first }
        return pkg.manifest.entries.firstOrNull { (id, item) ->
            item.second.startsWith("image/") && ("cover" in id.lowercase() || "cover" in item.first.lowercase())
        }?.value?.first
    }

    fun parse(file: File, id: String): Book {
        val zip = ZipFile(file)
        val pkg = readPackage(zip)
        val title = pkg.doc.allLocal("title").firstOrNull()?.text()?.trim().orEmpty().ifBlank { file.nameWithoutExtension }
        val author = pkg.doc.allLocal("creator").firstOrNull()?.text()?.trim()?.ifBlank { null }
        val spine = pkg.doc.allLocal("itemref").mapNotNull { pkg.manifest[it.attr("idref")] }
            .filter { it.second.contains("html") || it.first.endsWith("htm") || it.first.endsWith("html") }

        val chapterPaths = spine.map { it.first }
        val chapters = chapterPaths.map { path ->
            val html = zip.text(path)
            if (html == null) {
                Chapter(null, emptyList(), emptyMap())
            } else {
                val doc = Jsoup.parse(html, "", Parser.htmlParser())
                val dir = path.substringBeforeLast('/', "")
                val reader = HtmlBlocks { src -> resolve(dir, src) }.read(doc.body())
                Chapter(null, reader.blocks, reader.anchors)
            }
        }.toMutableList()

        val rawToc = readToc(zip, pkg)
        fun locate(href: String): Pair<Int, Int>? {
            val path = href.substringBefore('#')
            val chapter = chapterPaths.indexOf(path).takeIf { it >= 0 } ?: return null
            val fragment = href.substringAfter('#', "")
            return chapter to (chapters[chapter].anchors[fragment] ?: 0)
        }
        val toc = rawToc.mapNotNull { (label, href, depth) ->
            locate(href)?.let { (c, b) -> TocEntry(label, c, b, depth) }
        }.toMutableList()

        // Each chapter's running title: its first table-of-contents entry, else its first heading.
        for (i in chapters.indices) {
            val ch = chapters[i]
            val name = toc.firstOrNull { it.chapter == i }?.title
                ?: ch.blocks.firstOrNull { it.kind == BlockKind.Heading }?.text?.replace('\n', ' ')
            chapters[i] = Chapter(name, ch.blocks, ch.anchors)
        }

        // A cover page up front, unless the book already opens with a picture.
        val cover = coverPath(pkg)
        val opensWithImage = chapters.firstOrNull { it.blocks.isNotEmpty() }?.blocks?.firstOrNull()?.kind == BlockKind.Image
        if (cover != null && !opensWithImage) {
            chapters.add(0, Chapter(null, listOf(Block(BlockKind.Image, title, level = 1, image = cover)), emptyMap()))
            toc.replaceAll { TocEntry(it.title, it.chapter + 1, it.block, it.depth) }
        } else if (opensWithImage) {
            val first = chapters.indexOfFirst { it.blocks.isNotEmpty() }
            val ch = chapters[first]
            if (ch.blocks.size == 1) chapters[first] = Chapter(ch.title, listOf(Block(BlockKind.Image, title, level = 1, image = ch.blocks[0].image)), ch.anchors)
        }

        val names = zip.entries().asSequence().map { it.name }.toSet()
        return Book(id, title, author, chapters, toc) { path ->
            if (path in names) zip.getEntry(path)?.let { zip.getInputStream(it) } else null
        }
    }

    /** (label, href resolved against the archive root, depth) from the EPUB 3 nav or the EPUB 2 NCX. */
    private fun readToc(zip: ZipFile, pkg: Package): List<Triple<String, String, Int>> {
        val out = mutableListOf<Triple<String, String, Int>>()
        pkg.manifest.values.firstOrNull { "nav" in it.third.split(' ') }?.let { nav ->
            val html = zip.text(nav.first) ?: return@let
            val dir = nav.first.substringBeforeLast('/', "")
            val doc = Jsoup.parse(html, "", Parser.htmlParser())
            val navEl = doc.select("nav").firstOrNull { it.attr("epub:type") == "toc" } ?: doc.selectFirst("nav") ?: return@let
            fun walk(ol: Element, depth: Int) {
                for (li in ol.children().filter { it.normalName() == "li" }) {
                    val a = li.children().firstOrNull { it.normalName() == "a" || it.normalName() == "span" }
                    val href = a?.attr("href").orEmpty()
                    if (a != null && href.isNotBlank()) out += Triple(a.text().trim(), resolve(dir, href), depth)
                    li.children().firstOrNull { it.normalName() == "ol" || it.normalName() == "ul" }?.let { walk(it, depth + 1) }
                }
            }
            navEl.children().firstOrNull { it.normalName() == "ol" || it.normalName() == "ul" }?.let { walk(it, 0) }
        }
        if (out.isNotEmpty()) return out
        val ncxId = pkg.doc.allLocal("spine").firstOrNull()?.attr("toc")
        val ncx = pkg.manifest[ncxId] ?: pkg.manifest.values.firstOrNull { it.second == "application/x-dtbncx+xml" } ?: return out
        val xml = zip.text(ncx.first) ?: return out
        val dir = ncx.first.substringBeforeLast('/', "")
        val doc = Jsoup.parse(xml, "", Parser.xmlParser())
        fun walk(parent: Element, depth: Int) {
            for (point in parent.children().filter { it.local() == "navpoint" }) {
                val label = point.allLocal("navlabel").firstOrNull()?.text()?.trim().orEmpty()
                val src = point.children().firstOrNull { it.local() == "content" }?.attr("src").orEmpty()
                if (label.isNotBlank() && src.isNotBlank()) out += Triple(label, resolve(dir, src), depth)
                walk(point, depth + 1)
            }
        }
        doc.allLocal("navmap").firstOrNull()?.let { walk(it, 0) }
        return out
    }

    /** [href] (URL-encoded, maybe with ../ and a #fragment) against the directory [dir], as a zip path. */
    fun resolve(dir: String, href: String): String {
        val fragment = href.substringAfter('#', "")
        val raw = href.substringBefore('#')
        val decoded = runCatching { URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8") }.getOrDefault(raw)
        val parts = ArrayDeque<String>()
        val joined = if (decoded.startsWith("/")) decoded.drop(1) else if (dir.isEmpty()) decoded else "$dir/$decoded"
        for (p in joined.split('/')) when (p) {
            "", "." -> Unit
            ".." -> parts.removeLastOrNull()
            else -> parts.addLast(p)
        }
        val path = parts.joinToString("/")
        return if (fragment.isEmpty()) path else "$path#$fragment"
    }
}
