package io.github.draumaz.twopane.ui.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.draumaz.twopane.data.BookEntry
import io.github.draumaz.twopane.ui.BookTransition
import io.github.draumaz.twopane.ui.LocalPaneSplit
import io.github.draumaz.twopane.ui.Panes
import io.github.draumaz.twopane.ui.components.ShapeBadge
import io.github.draumaz.twopane.ui.components.bouncyEnter
import io.github.draumaz.twopane.ui.components.pressScale
import io.github.draumaz.twopane.ui.components.rememberSpin
import kotlinx.coroutines.flow.Flow
import kotlin.math.roundToInt

private val BookTypes = arrayOf("application/epub+zip", "text/plain", "application/octet-stream")

@Composable
fun LibraryScreen(
    books: List<BookEntry>,
    importing: Int,
    messages: Flow<String>,
    transition: BookTransition,
    onImport: (List<android.net.Uri>) -> Unit,
    onOpen: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    val split = LocalPaneSplit.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { onImport(it) }
    val pick = { picker.launch(BookTypes) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { messages.collect { snackbar.showSnackbar(it) } }

    val shelf = remember(books) { books.sortedWith(compareByDescending<BookEntry> { it.openedAt }.thenByDescending { it.addedAt }) }
    val current = shelf.firstOrNull { it.openedAt > 0 }
    val grid = rememberLazyGridState()
    val seen = remember { HashSet<Any>() }

    Box(Modifier.fillMaxSize()) {
        if (split != null) {
            // Spanned: the welcome and the book in hand on the left screen, the shelf on the right.
            Panes(split, start = {
                Column(
                    Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 28.dp, vertical = 24.dp),
                ) {
                    Hero()
                    Spacer(Modifier.height(28.dp))
                    if (current != null) ContinueCard(current, transition, onOpen)
                    else Hint()
                }
            }, end = {
                Shelf(shelf, grid, seen, transition, header = {}, onOpen, onRemove, pick)
                Fab(importing, grid, pick, Modifier.align(Alignment.BottomEnd))
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 88.dp))
            })
        } else {
            Shelf(shelf, grid, seen, transition, header = {
                Column(Modifier.padding(top = 8.dp, bottom = 8.dp)) {
                    Hero()
                    if (current != null) {
                        Spacer(Modifier.height(24.dp))
                        ContinueCard(current, transition, onOpen)
                    }
                }
            }, onOpen, onRemove, pick)
            Fab(importing, grid, pick, Modifier.align(Alignment.BottomEnd))
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 88.dp))
        }
    }
}

@Composable
private fun Hero() {
    Column {
        Text(
            "TwoPane",
            style = MaterialTheme.typography.displayMediumEmphasized,
            fontFamily = FontFamily.Serif,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            "Version ${io.github.draumaz.twopane.BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Hint() {
    Text(
        "Open a book and it lies open across both screens, the hinge as its spine. Swipe a page to turn it.",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun Modifier.sharedCover(id: String, transition: BookTransition): Modifier = with(transition.shared) {
    this@sharedCover.sharedBounds(
        rememberSharedContentState("book-$id"),
        animatedVisibilityScope = transition.visibility,
        resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds(androidx.compose.ui.layout.ContentScale.Crop),
    )
}

@Composable
private fun ContinueCard(entry: BookEntry, transition: BookTransition, onOpen: (String) -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Surface(
        onClick = { onOpen(entry.id) },
        shape = RoundedCornerShape(32.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        interactionSource = interaction,
        modifier = Modifier.fillMaxWidth().pressScale(interaction, 0.97f),
    ) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            BookCover(entry, Modifier.width(96.dp).aspectRatio(2f / 3f).sharedCover(entry.id, transition))
            Column(Modifier.padding(start = 20.dp).weight(1f)) {
                Text("Continue reading", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f))
                Text(
                    entry.title,
                    style = MaterialTheme.typography.headlineSmallEmphasized,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
                entry.author?.let { Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                Spacer(Modifier.height(16.dp))
                LinearWavyProgressIndicator(progress = { entry.progress.coerceIn(0.01f, 1f) }, modifier = Modifier.fillMaxWidth())
                Text(
                    "${(entry.progress * 100).roundToInt()}% read",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun Shelf(
    books: List<BookEntry>,
    grid: LazyGridState,
    seen: MutableSet<Any>,
    transition: BookTransition,
    header: @Composable () -> Unit,
    onOpen: (String) -> Unit,
    onRemove: (String) -> Unit,
    onAdd: () -> Unit,
) {
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    LazyVerticalGrid(
        columns = GridCells.Adaptive(136.dp),
        state = grid,
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = top + 20.dp, bottom = bottom + 112.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }, key = "header") { header() }
        if (books.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "empty") { EmptyShelf(onAdd) }
        } else {
            item(span = { GridItemSpan(maxLineSpan) }, key = "label") {
                Text("Library", style = MaterialTheme.typography.titleLargeEmphasized, modifier = Modifier.padding(top = 4.dp))
            }
            items(books, key = { it.id }) { entry ->
                BookTile(
                    entry,
                    transition,
                    onOpen,
                    onRemove,
                    Modifier.animateItem().bouncyEnter(entry.id, seen, books.indexOf(entry)),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookTile(
    entry: BookEntry,
    transition: BookTransition,
    onOpen: (String) -> Unit,
    onRemove: (String) -> Unit,
    modifier: Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    var menu by remember { mutableStateOf(false) }
    Column(modifier) {
        Box {
            BookCover(
                entry,
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f)
                    .pressScale(interaction)
                    .sharedCover(entry.id, transition)
                    .combinedClickable(
                        interactionSource = interaction,
                        indication = null,
                        onClick = { onOpen(entry.id) },
                        onLongClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            menu = true
                        },
                    ),
            )
            DropdownMenu(menu, onDismissRequest = { menu = false }, shape = RoundedCornerShape(20.dp)) {
                DropdownMenuItem(
                    text = { Text("Remove from library") },
                    leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
                    onClick = {
                        menu = false
                        onRemove(entry.id)
                    },
                )
            }
        }
        Text(
            entry.title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 10.dp),
        )
        entry.author?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (entry.progress > 0f) {
            Text(
                if (entry.progress >= 0.995f) "Finished" else "${(entry.progress * 100).roundToInt()}%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun EmptyShelf(onAdd: () -> Unit) {
    val spin = rememberSpin(20_000)
    Column(
        Modifier.fillMaxWidth().padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ShapeBadge(
            MaterialShapes.Cookie9Sided,
            MaterialTheme.colorScheme.primaryContainer,
            size = 132.dp,
            rotation = { spin.value },
        ) {
            Icon(
                Icons.AutoMirrored.Rounded.MenuBook,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(52.dp),
            )
        }
        Text("Your shelf is empty", style = MaterialTheme.typography.headlineSmallEmphasized, modifier = Modifier.padding(top = 24.dp))
        Text(
            "Add EPUB or plain-text books to start reading.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp, bottom = 20.dp),
        )
        Button(onClick = onAdd) {
            Icon(Icons.Rounded.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Add books")
        }
    }
}

@Composable
private fun Fab(importing: Int, grid: LazyGridState, onClick: () -> Unit, modifier: Modifier) {
    // Tucks into its icon while the shelf scrolls down, and stretches back out at the top.
    val expanded by remember(grid) { derivedStateOf { grid.firstVisibleItemIndex == 0 } }
    ExtendedFloatingActionButton(
        onClick = onClick,
        expanded = expanded || importing > 0,
        icon = {
            AnimatedContent(
                importing > 0,
                transitionSpec = { (scaleIn() + fadeIn()) togetherWith (scaleOut() + fadeOut()) },
                label = "fabIcon",
            ) { busy ->
                if (busy) LoadingIndicator(Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimaryContainer)
                else Icon(Icons.Rounded.Add, contentDescription = null)
            }
        },
        text = { Text(if (importing > 0) "Adding…" else "Add books") },
        modifier = modifier.navigationBarsPadding().padding(20.dp),
    )
}
