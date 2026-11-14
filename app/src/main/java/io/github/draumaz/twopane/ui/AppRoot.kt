package io.github.draumaz.twopane.ui

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.draumaz.twopane.data.Library
import io.github.draumaz.twopane.ui.library.LibraryScreen
import io.github.draumaz.twopane.ui.reader.ReaderScreen
import io.github.draumaz.twopane.ui.theme.LocalDarkTheme
import io.github.draumaz.twopane.ui.theme.pageColors
import kotlinx.coroutines.CancellationException

val LocalLibrary = staticCompositionLocalOf<Library> { error("No library") }

/** Lets a book's cover in the library grow into the open book, and shrink back. */
@OptIn(ExperimentalSharedTransitionApi::class)
class BookTransition(val shared: SharedTransitionScope, val visibility: androidx.compose.animation.AnimatedVisibilityScope)

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun AppRoot(vm: AppViewModel, openExternal: (String) -> Unit) {
    val books by vm.books.collectAsStateWithLifecycle()
    val reading by vm.reading.collectAsStateWithLifecycle()
    val importing by vm.importing.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val split = rememberPaneSplit()

    var settingsOpen by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    // Settings shrink under the finger, then slide away.
    var settingsBack by remember { mutableFloatStateOf(0f) }
    PredictiveBackHandler(enabled = settingsOpen && reading == null) { events ->
        try {
            events.collect { settingsBack = it.progress }
            settingsOpen = false
        } catch (e: CancellationException) {
            throw e
        } finally {
            settingsBack = 0f
        }
    }

    // Predictive back folds the book away under the finger, then closes it.
    var backProgress by remember { mutableFloatStateOf(0f) }
    PredictiveBackHandler(enabled = reading != null) { events ->
        try {
            events.collect { backProgress = it.progress }
            vm.close()
        } catch (e: CancellationException) {
            throw e
        } finally {
            backProgress = 0f
        }
    }

    val unfoldReady = remember { androidx.compose.runtime.mutableStateOf(true) }
    val unfold = rememberUnfold(split != null, unfoldReady)
    CompositionLocalProvider(
        LocalUnfold provides unfold,
        LocalUnfoldReady provides unfoldReady,
        LocalPaneSplit provides split,
        LocalLibrary provides vm.library,
        androidx.compose.material3.LocalContentColor provides MaterialTheme.colorScheme.onSurface,
    ) {
        SharedTransitionLayout(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
            AnimatedContent(
                targetState = reading?.id,
                transitionSpec = {
                    val opening = targetState != null
                    if (opening) {
                        (fadeIn(tween(220)) + scaleIn(spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow), initialScale = 0.96f))
                            .togetherWith(fadeOut(tween(260)) + scaleOut(targetScale = 1.04f))
                    } else {
                        (fadeIn(tween(260)) + scaleIn(spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow), initialScale = 1.04f))
                            .togetherWith(fadeOut(tween(200)))
                            .apply { targetContentZIndex = -1f }
                    }
                },
                label = "screens",
            ) { id ->
                val transition = BookTransition(this@SharedTransitionLayout, this)
                if (id == null) {
                    LibraryScreen(
                        books = books,
                        importing = importing,
                        messages = vm.messages,
                        transition = transition,
                        onImport = { vm.import(it) },
                        onOpen = vm::open,
                        onRemove = vm::remove,
                        onOpenSettings = { settingsOpen = true },
                    )
                } else {
                    val state = reading?.takeIf { it.id == id }
                    val entry = books.firstOrNull { it.id == id }
                    val paper = pageColors(settings.theme, LocalDarkTheme.current).paper
                    Box(
                        Modifier
                            .fillMaxSize()
                            .then(
                                with(transition.shared) {
                                    Modifier.sharedBounds(
                                        rememberSharedContentState("book-$id"),
                                        animatedVisibilityScope = this@AnimatedContent,
                                        resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds(ContentScale.Crop),
                                        clipInOverlayDuringTransition = OverlayClip(RoundedCornerShape(16.dp)),
                                    )
                                }
                            )
                            .graphicsLayer {
                                val p = backProgress
                                val s = 1f - 0.12f * p
                                scaleX = s
                                scaleY = s
                                shape = RoundedCornerShape(36.dp * p)
                                clip = p > 0f
                            }
                            .background(paper)
                    ) {
                        when {
                            state?.error != null -> OpenError(state.error, vm::close)
                            state?.book != null && entry != null -> ReaderScreen(
                                book = state.book,
                                startAt = entry.position,
                                bookmarks = entry.bookmarks,
                                settings = settings,
                                onSettings = vm::updateSettings,
                                onProgress = { pos, p -> vm.saveProgress(id, pos, p) },
                                onBookmarks = { vm.setBookmarks(id, it) },
                                pageKeys = vm.pageKeys,
                                onClose = vm::close,
                            )
                            else -> entry?.let { io.github.draumaz.twopane.ui.reader.ReaderLoading(it.title, it.author) }
                        }
                    }
                }
            }

            // On a spread, settings slide in over the shelf on the right screen.
            androidx.compose.animation.AnimatedVisibility(
                visible = settingsOpen && reading == null,
                enter = androidx.compose.animation.slideInHorizontally(spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)) { it } + fadeIn(),
                exit = androidx.compose.animation.slideOutHorizontally(spring(stiffness = Spring.StiffnessMedium)) { it } + fadeOut(),
                modifier = if (split != null) Modifier.padding(start = split.endOffset).unfoldEnd(unfold) else Modifier,
            ) {
                SettingsScreen(
                    settings = vm.settingsStore,
                    openExternal = openExternal,
                    onBack = { settingsOpen = false },
                    modifier = Modifier.graphicsLayer {
                        val p = settingsBack
                        val s = 1f - 0.1f * p
                        scaleX = s
                        scaleY = s
                        translationX = p * 32.dp.toPx()
                        shape = RoundedCornerShape(36.dp * p)
                        clip = p > 0f
                    },
                )
            }
        }
    }
}

@Composable
private fun OpenError(message: String, onClose: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Text("This book couldn't be opened", style = MaterialTheme.typography.headlineSmallEmphasized)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = onClose) { Text("Back to library") }
    }
}
