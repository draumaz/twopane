package io.github.draumaz.twopane.ui.reader

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.graphics.shapes.Morph
import io.github.draumaz.twopane.data.Book
import io.github.draumaz.twopane.data.Bookmark
import io.github.draumaz.twopane.data.PageTheme
import io.github.draumaz.twopane.data.ReaderSettings
import io.github.draumaz.twopane.data.Typeface
import io.github.draumaz.twopane.ui.components.MorphShape
import io.github.draumaz.twopane.ui.components.pressScale
import io.github.draumaz.twopane.ui.components.rememberSpin
import io.github.draumaz.twopane.ui.theme.GoogleSansFlex
import io.github.draumaz.twopane.ui.theme.LocalDarkTheme
import io.github.draumaz.twopane.ui.theme.pageColors

/** Segmented-list corners: round at a group's ends, tight between neighbours. */
private fun segment(first: Boolean, last: Boolean) = RoundedCornerShape(
    topStart = if (first) 24.dp else 6.dp, topEnd = if (first) 24.dp else 6.dp,
    bottomStart = if (last) 24.dp else 6.dp, bottomEnd = if (last) 24.dp else 6.dp,
)

@Composable
private fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, fontFor: (T) -> FontFamily? = { null }) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
        options.forEachIndexed { i, (value, label) ->
            ToggleButton(
                checked = value == selected,
                onCheckedChange = { onSelect(value) },
                modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
                shapes = when (i) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
            ) { Text(label, fontFamily = fontFor(value), maxLines = 1) }
        }
    }
}

@Composable
private fun Heading(text: String) = Text(
    text,
    style = MaterialTheme.typography.labelLarge,
    color = MaterialTheme.colorScheme.primary,
    modifier = Modifier.padding(start = 4.dp, top = 20.dp, bottom = 10.dp),
)

@Composable
fun ContentsPanel(
    book: Book,
    pages: List<Page>,
    current: Int,
    bookmarks: List<Bookmark>,
    onJump: (Int) -> Unit,
    onRemoveBookmark: (Bookmark) -> Unit,
) {
    var showBookmarks by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Text(
            book.title,
            style = MaterialTheme.typography.headlineSmallEmphasized,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 8.dp, top = 24.dp, bottom = 16.dp),
        )
        Segmented(listOf(false to "Contents", true to "Bookmarks (${bookmarks.size})"), showBookmarks, { showBookmarks = it })
        Spacer(Modifier.height(12.dp))
        if (!showBookmarks) {
            // Entries with their page numbers; the chapter being read is highlighted.
            val entries = remember(book, pages) {
                val list = book.toc.ifEmpty {
                    book.chapters.mapIndexedNotNull { i, ch -> ch.title?.let { io.github.draumaz.twopane.data.TocEntry(it, i, 0, 0) } }
                }
                list.map { it to pages.indexFor(io.github.draumaz.twopane.data.Position(it.chapter, it.block, 0)) }
            }
            val currentEntry = entries.indexOfLast { it.second <= current }
            val state = rememberLazyListState()
            LaunchedEffect(Unit) { if (currentEntry > 2) state.scrollToItem(currentEntry - 2) }
            if (entries.isEmpty()) {
                Empty("This book has no table of contents.")
            }
            LazyColumn(state = state, contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                itemsIndexed(entries) { i, (entry, page) ->
                    val selected = i == currentEntry
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(segment(i == 0, i == entries.lastIndex))
                            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest)
                            .clickable { onJump(page) }
                            .padding(start = 20.dp + 16.dp * entry.depth, end = 20.dp, top = 16.dp, bottom = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            entry.title,
                            style = if (entry.depth == 0) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
                            color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "${page + 1}",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                }
            }
        } else {
            if (bookmarks.isEmpty()) Empty("No bookmarks yet. Tap the ribbon in the toolbar to mark a page.")
            val sorted = remember(bookmarks) { bookmarks.sortedBy { it.position } }
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                itemsIndexed(sorted, key = { _, b -> b.createdAt }) { i, b ->
                    val page = pages.indexFor(b.position)
                    Row(
                        Modifier
                            .animateItem()
                            .fillMaxWidth()
                            .clip(segment(i == 0, i == sorted.lastIndex))
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                            .clickable { onJump(page) }
                            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.Bookmark, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text("Page ${page + 1}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            Text(b.label, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        IconButton(onClick = { onRemoveBookmark(b) }, shapes = IconButtonDefaults.shapes()) {
                            Icon(Icons.Rounded.Close, contentDescription = "Remove bookmark")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Empty(text: String) = Text(
    text,
    style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(16.dp),
)

@Composable
fun AppearancePanel(settings: ReaderSettings, onChange: ((ReaderSettings) -> ReaderSettings) -> Unit) {
    // Font size applies on release: every change repaginates the whole book.
    var size by remember(settings.fontSize) { mutableFloatStateOf(settings.fontSize) }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp)
    ) {
        Text(
            "Text & page",
            style = MaterialTheme.typography.headlineSmallEmphasized,
            modifier = Modifier.padding(start = 4.dp, top = 16.dp),
        )

        Heading("Size")
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilledTonalIconButton(
                onClick = { onChange { it.copy(fontSize = (it.fontSize - 1f).coerceAtLeast(ReaderSettings.MinFont)) } },
                shapes = IconButtonDefaults.shapes(),
            ) { Icon(Icons.Rounded.Remove, contentDescription = "Smaller text") }
            Slider(
                value = size,
                onValueChange = { size = it },
                onValueChangeFinished = { onChange { s -> s.copy(fontSize = size) } },
                valueRange = ReaderSettings.MinFont..ReaderSettings.MaxFont,
                steps = (ReaderSettings.MaxFont - ReaderSettings.MinFont).toInt() - 1,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            FilledTonalIconButton(
                onClick = { onChange { it.copy(fontSize = (it.fontSize + 1f).coerceAtMost(ReaderSettings.MaxFont)) } },
                shapes = IconButtonDefaults.shapes(),
            ) { Icon(Icons.Rounded.Add, contentDescription = "Larger text") }
        }
        Text(
            "The quick brown fox",
            fontSize = size.sp,
            fontFamily = if (settings.typeface == Typeface.Serif) FontFamily.Serif else GoogleSansFlex,
            modifier = Modifier.padding(start = 4.dp, top = 4.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        Heading("Typeface")
        Segmented(
            listOf(Typeface.Serif to "Serif", Typeface.Sans to "Sans"),
            settings.typeface,
            { t -> onChange { it.copy(typeface = t) } },
            fontFor = { if (it == Typeface.Serif) FontFamily.Serif else GoogleSansFlex },
        )

        Heading("Line spacing")
        Segmented(
            ReaderSettings.Spacings.zip(listOf("Snug", "Book", "Airy")),
            ReaderSettings.Spacings.minBy { kotlin.math.abs(it - settings.lineSpacing) },
            { v -> onChange { it.copy(lineSpacing = v) } },
        )

        Row(
            Modifier.fillMaxWidth().padding(top = 20.dp, start = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Justify", style = MaterialTheme.typography.titleMedium)
                Text("Even right edges, with hyphenation", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(
                checked = settings.justify,
                onCheckedChange = { j -> onChange { it.copy(justify = j) } },
                thumbContent = {
                    if (settings.justify) Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(SwitchDefaults.IconSize))
                },
            )
        }

        Heading("Paper")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            PageTheme.entries.forEach { theme ->
                ThemeSwatch(theme, theme == settings.theme) { onChange { it.copy(theme = theme) } }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** A paper swatch: a circle that blooms into a turning scalloped shape when chosen. */
@Composable
private fun ThemeSwatch(theme: PageTheme, selected: Boolean, onClick: () -> Unit) {
    val colors = pageColors(theme, LocalDarkTheme.current)
    val morph = remember { Morph(MaterialShapes.Circle, MaterialShapes.Cookie9Sided) }
    val progress by animateFloatAsState(
        if (selected) 1f else 0f,
        spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMediumLow),
        label = "swatch",
    )
    val spin = if (selected) rememberSpin(14_000) else null
    val interaction = remember { MutableInteractionSource() }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(48.dp)
                .pressScale(interaction)
                .clickable(interaction, indication = null, onClick = onClick)
                .semantics { contentDescription = theme.name; role = Role.RadioButton },
            contentAlignment = Alignment.Center,
        ) {
            val shape = MorphShape(morph, progress)
            Box(
                Modifier
                    .size(48.dp)
                    .graphicsLayer { rotationZ = spin?.value ?: 0f }
                    .background(colors.paper, shape)
                    .border(if (selected) 2.5.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, shape)
            )
            Text(if (theme == PageTheme.Auto) "A" else "Aa", color = colors.ink, style = MaterialTheme.typography.labelLarge)
        }
        Text(
            theme.name,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp).width(56.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}
