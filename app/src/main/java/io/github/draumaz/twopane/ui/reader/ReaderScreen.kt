package io.github.draumaz.twopane.ui.reader

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Toc
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.draumaz.twopane.data.Book
import io.github.draumaz.twopane.data.Bookmark
import io.github.draumaz.twopane.data.Position
import io.github.draumaz.twopane.data.ReaderSettings
import io.github.draumaz.twopane.ui.LocalPaneSplit
import io.github.draumaz.twopane.ui.Panes
import io.github.draumaz.twopane.ui.rememberPageFrames
import io.github.draumaz.twopane.ui.theme.LocalDarkTheme
import io.github.draumaz.twopane.ui.theme.pageColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

private enum class Panel { None, Contents, Appearance }

@Composable
fun ReaderScreen(
    book: Book,
    startAt: Position?,
    bookmarks: List<Bookmark>,
    settings: ReaderSettings,
    onSettings: ((ReaderSettings) -> ReaderSettings) -> Unit,
    onProgress: (Position, Float) -> Unit,
    onBookmarks: (List<Bookmark>) -> Unit,
    pageKeys: Flow<Int>,
    onClose: () -> Unit,
) {
    val split = LocalPaneSplit.current
    val frames = rememberPageFrames(split)
    val density = LocalDensity.current
    val fonts = LocalFontFamilyResolver.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    val layoutSettings = settings.copy(theme = io.github.draumaz.twopane.data.PageTheme.Auto)
    val pageSize = androidx.compose.ui.unit.IntSize(frames.right.width, frames.right.height)
    val typesetter = remember(book, layoutSettings, pageSize, density) {
        Typesetter(book, layoutSettings, pageSize, density, fonts)
    }
    // Pages from the last typesetting stay up while a new one runs, so a fold, a rotation or
    // a font change never blanks the book; only the very first setting shows the indicator.
    var pages by remember(book) { mutableStateOf<List<Page>?>(null) }
    var pagesFor by remember(book) { mutableStateOf<Typesetter?>(null) }
    // Every setting of the book is kept, by page size, density and type settings: folding and
    // unfolding goes back and forth between two, and returning to one is instant.
    val layouts = remember(book) { HashMap<List<Any>, List<Page>>() }
    val layoutKey = listOf(layoutSettings, pageSize, density.density, density.fontScale)
    val unfoldReady = io.github.draumaz.twopane.ui.LocalUnfoldReady.current
    val current = pagesFor === typesetter
    SideEffect { unfoldReady.value = current }
    DisposableEffect(Unit) { onDispose { unfoldReady.value = true } }
    LaunchedEffect(typesetter) {
        val known = layouts[layoutKey]
        if (known == null) {
            if (pages != null) kotlinx.coroutines.delay(150) // the window may still be settling
            layouts[layoutKey] = typesetter.paginate()
        }
        pages = layouts[layoutKey]
        pagesFor = typesetter
    }

    val colors = pageColors(settings.theme, LocalDarkTheme.current)
    val perView = if (frames.spread) 2 else 1

    // Where the reader is, as a position in the text: it outlives repagination.
    var anchor by remember { mutableStateOf(startAt ?: Position.Start) }
    var index by remember { mutableIntStateOf(0) }
    LaunchedEffect(pages) { pages?.let { index = it.indexFor(anchor) } }
    val first = index - index % perView

    var chrome by remember { mutableStateOf(false) }
    var panel by remember { mutableStateOf(Panel.None) }

    val sideOf: (Int) -> Side = { i -> if (!frames.spread) Side.Single else if (i % 2 == 0) Side.Left else Side.Right }
    val previousCache = remember { arrayOfNulls<PageCache>(1) }
    val cache = pages?.let { p ->
        val setter = pagesFor ?: typesetter
        remember(setter, p, colors, frames.spread) {
            PageCache(scope, setter, p, colors, sideOf).also {
                // Spanning re-renders every page (the gutter moves): until it has, show the old ones.
                it.fallback = previousCache[0]?.also { old -> old.fallback = null }
                previousCache[0] = it
            }
        }
    }
    val unfold = io.github.draumaz.twopane.ui.LocalUnfold.current
    val hinge = remember { android.graphics.Matrix() }
    val firstState = rememberUpdatedState(first)
    val turner = cache?.let { c ->
        remember(c, frames) {
            PageTurner(scope, frames, c, { firstState.value }) { result ->
                index = result
                anchor = c.pages[result].start
            }
        }
    }

    val handoff = remember(turner) { HandoffSlot() }

    // Jumps (and opening the book) land without a curl, so the page fades in instead of
    // popping. It waits for the page to render, so a far jump doesn't fade in blank paper.
    val landing = remember { Animatable(0f) }
    var landings by remember { mutableIntStateOf(0) }
    val landingCache by rememberUpdatedState(cache)
    val landingAt by rememberUpdatedState(first)
    LaunchedEffect(landings) {
        landing.snapTo(0f)
        snapshotFlow { landingCache?.get(landingAt) != null }.first { it }
        landing.animateTo(1f, tween(180, easing = LinearOutSlowInEasing))
    }

    fun jump(to: Int) {
        val p = pages?.takeIf { it.isNotEmpty() } ?: return
        turner?.cancel()
        val target = to.coerceIn(0, p.lastIndex)
        if (target - target % perView != first) landings++
        index = target
        anchor = p[index].start
    }

    // Keep the neighbourhood rendered: this spread first, then the ones either side.
    LaunchedEffect(cache, first) {
        val c = cache ?: return@LaunchedEffect
        val order = (0 until perView) + (perView until 3 * perView) + (-2 * perView until 0)
        order.forEach { c.request(first + it) }
        c.trim((first - 3 * perView)..(first + 4 * perView))
    }
    LaunchedEffect(pages, first) {
        val p = pages ?: return@LaunchedEffect
        if (p.isEmpty()) return@LaunchedEffect
        val last = (first + perView - 1).coerceAtMost(p.lastIndex)
        onProgress(p[first.coerceAtMost(p.lastIndex)].start, if (p.size <= 1) 1f else last.toFloat() / p.lastIndex)
    }
    LaunchedEffect(turner) {
        pageKeys.collect { dir ->
            if (panel == Panel.None) turner?.tap(dir > 0)
        }
    }

    // Immersive while reading; the system bars come back with the controls.
    val activity = LocalActivity.current
    DisposableEffect(activity, chrome) {
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (chrome) controller?.show(WindowInsetsCompat.Type.systemBars())
        else controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { }
    }
    DisposableEffect(activity) {
        onDispose {
            activity?.window?.let { WindowCompat.getInsetsController(it, it.decorView).show(WindowInsetsCompat.Type.systemBars()) }
        }
    }
    // The bars sit over the paper, not the app theme: dark icons on light paper, light on dark.
    val appDark = LocalDarkTheme.current
    DisposableEffect(activity, colors.dark, appDark) {
        val controller = activity?.window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        controller?.isAppearanceLightStatusBars = !colors.dark
        controller?.isAppearanceLightNavigationBars = !colors.dark
        onDispose {
            controller?.isAppearanceLightStatusBars = !appDark
            controller?.isAppearanceLightNavigationBars = !appDark
        }
    }

    BackHandler(panel != Panel.None) { panel = Panel.None }

    val bookmarked by remember(bookmarks, pages, first) {
        derivedStateOf {
            val p = pages ?: return@derivedStateOf null
            if (p.isEmpty()) return@derivedStateOf null
            val from = p[first.coerceAtMost(p.lastIndex)].start
            val until = p.getOrNull(first + perView)?.start
            bookmarks.firstOrNull { it.position >= from && (until == null || it.position < until) }
        }
    }

    Box(Modifier.fillMaxSize().background(colors.paper)) {
        if (cache == null || turner == null) {
            ReaderLoading(book.title, book.author)
        } else {
            val count = cache.pages.size
            Canvas(
                Modifier
                    .fillMaxSize()
                    .pointerInput(turner) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val slop = viewConfiguration.touchSlop
                            // Touches are taken in book space, where the hinge has no width. And the
                            // Duo's two screens track touches separately: a finger crossing the hinge
                            // lifts off one and lands on the other as a new touch. A drag that ends
                            // by the hinge is held for a moment so the new touch can pick it up.
                            val pending = handoff.value
                            handoff.value = null
                            pending?.timeout?.cancel()
                            var track = down.id
                            var tracker = VelocityTracker()
                            var moved = false
                            var dragging = false
                            var last = Offset.Zero
                            var start: Offset
                            if (pending != null && turner.turn != null && frames.nearHinge(down.position, density.density)) {
                                start = frames.toBook(down.position) - pending.delta
                                last = pending.delta
                                moved = true
                                dragging = true
                                down.consume()
                            } else {
                                if (pending != null) turner.release(pending.vx)
                                start = frames.toBook(down.position)
                            }
                            tracker.addPosition(down.uptimeMillis, frames.toBook(down.position))
                            var lastScreen = down.position
                            while (true) {
                                val event = awaitPointerEvent()
                                var change = event.changes.firstOrNull { it.id == track }
                                if (change == null || !change.pressed) {
                                    // The other screen may report the finger as a second pointer
                                    // before the first lets go: follow it from where the page is.
                                    val other = event.changes.firstOrNull { it.pressed && it.id != track }
                                    if (dragging && other != null) {
                                        track = other.id
                                        start = frames.toBook(other.position) - last
                                        tracker = VelocityTracker()
                                        change = other
                                    } else {
                                        change?.let { lastScreen = it.position }
                                        break
                                    }
                                }
                                lastScreen = change.position
                                val at = frames.toBook(change.position)
                                tracker.addPosition(change.uptimeMillis, at)
                                val delta = at - start
                                if (!moved && delta.getDistance() > slop) {
                                    moved = true
                                    if (panel == Panel.None && abs(delta.x) > abs(delta.y) * 0.7f) {
                                        dragging = turner.begin(forward = delta.x < 0, grabY = down.position.y)
                                        if (dragging) {
                                            chrome = false
                                            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                        }
                                    }
                                }
                                if (dragging) {
                                    last = delta
                                    turner.drag(delta)
                                    change.consume()
                                }
                            }
                            if (dragging) {
                                val vx = runCatching { tracker.calculateVelocity().x }.getOrDefault(0f)
                                if (frames.nearHinge(lastScreen, density.density)) {
                                    val h = Handoff(last, vx)
                                    h.timeout = scope.launch {
                                        delay(220)
                                        if (handoff.value === h) {
                                            handoff.value = null
                                            turner.release(vx)
                                        }
                                    }
                                    handoff.value = h
                                } else {
                                    turner.release(vx)
                                }
                            } else if (!moved) {
                                tapAt(down.position, frames, panel != Panel.None, chrome,
                                    closePanel = { panel = Panel.None },
                                    toggleChrome = { chrome = !chrome },
                                    turn = { forward -> turner.tap(forward) },
                                )
                            }
                        }
                    }
            ) {
                val t = turner.turn
                val opening = unfold()
                fun page(i: Int, rect: androidx.compose.ui.unit.IntRect) {
                    val bmp = if (i in 0 until count) cache[i] else cache.blank
                    val leftSide = frames.left != null && rect == frames.left
                    val turning = frames.left != null && opening != 1f
                    drawIntoCanvas { c ->
                        if (turning) {
                            // Unfolding: each page swings open on the spine.
                            val angle = if (leftSide) io.github.draumaz.twopane.ui.unfoldStartAngle(opening) else io.github.draumaz.twopane.ui.unfoldEndAngle(opening)
                            val pivot = if (leftSide) rect.right.toFloat() else rect.left.toFloat()
                            c.save()
                            c.nativeCanvas.concat(io.github.draumaz.twopane.ui.hingeMatrix(angle, pivot, rect.height / 2f, hinge, density.density))
                        }
                        val alpha = if (t != null || turning) 1f else landing.value
                        if (bmp != null && !(turning && !current)) {
                            if (alpha < 1f) drawRect(colors.paper, Offset(rect.left.toFloat(), rect.top.toFloat()),
                                androidx.compose.ui.geometry.Size(rect.width.toFloat(), rect.height.toFloat()))
                            drawImage(bmp, Offset(rect.left.toFloat(), rect.top.toFloat()), alpha = alpha)
                        } else drawRect(colors.paper, Offset(rect.left.toFloat(), rect.top.toFloat()),
                            androidx.compose.ui.geometry.Size(rect.width.toFloat(), rect.height.toFloat()))
                        if (turning) {
                            // The page is in shadow until it comes round to face the light.
                            val shade = ((1f - opening) * (if (leftSide) 0.35f else 0.75f)).coerceIn(0f, 0.75f)
                            drawRect(Color.Black.copy(alpha = shade), Offset(rect.left.toFloat(), rect.top.toFloat()),
                                androidx.compose.ui.geometry.Size(rect.width.toFloat(), rect.height.toFloat()))
                            c.restore()
                        }
                    }
                }
                val left = frames.left
                if (left != null) {
                    page(if (t != null && t.sheetLeft) t.under else first, left)
                    page(if (t != null && !t.sheetLeft) t.under else first + 1, frames.right)
                } else {
                    page(t?.under ?: first, frames.right)
                }
                if (t != null) drawTurn(t, frames, colors.dark)
            }

            // The bookmark ribbon hangs from the top of the right-hand page.
            Ribbon(
                visible = bookmarked != null && turner.turn == null,
                modifier = Modifier.offset { IntOffset(frames.right.right - 64.dp.roundToPx(), 0) },
            )

            Chrome(
                visible = chrome,
                book = book,
                chapterTitle = cache.pages.getOrNull(first)?.let { book.chapters[it.chapter].title },
                first = first,
                perView = perView,
                count = count,
                bookmarked = bookmarked != null,
                onClose = onClose,
                onJump = ::jump,
                onPanel = { panel = if (panel == it) Panel.None else it },
                onBookmark = bookmark@{
                    if (first !in cache.pages.indices) return@bookmark
                    val existing = bookmarked
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    onBookmarks(
                        if (existing != null) bookmarks - existing
                        else bookmarks + Bookmark(cache.pages[first].start, typesetter.excerpt(cache.pages[first]), System.currentTimeMillis())
                    )
                },
            )

            Panels(
                panel = panel,
                onDismiss = { panel = Panel.None },
                contents = {
                    ContentsPanel(
                        book = book,
                        pages = cache.pages,
                        current = first,
                        bookmarks = bookmarks,
                        onJump = { jump(it); panel = Panel.None; chrome = false },
                        onRemoveBookmark = { onBookmarks(bookmarks - it) },
                    )
                },
                appearance = { AppearancePanel(settings, onSettings) },
            )
        }
    }
}

/** A drag that lifted off by the hinge, waiting for the finger to land on the other screen. */
private class Handoff(val delta: Offset, val vx: Float) {
    var timeout: kotlinx.coroutines.Job? = null
}

private class HandoffSlot {
    var value: Handoff? = null
}

/** Whether [p] is close enough to a hinge for a touch there to be a finger crossing it. */
private fun io.github.draumaz.twopane.ui.PageFrames.nearHinge(p: Offset, density: Float): Boolean {
    val l = left ?: return false
    val reach = 72f * density
    return p.x > l.right - reach && p.x < right.left + reach
}

/** A window position with the gap between a spread's pages taken out. */
private fun io.github.draumaz.twopane.ui.PageFrames.toBook(p: Offset): Offset {
    val l = left ?: return p
    val x = when {
        p.x >= right.left -> p.x - (right.left - l.right)
        p.x <= l.right -> p.x
        else -> l.right.toFloat()
    }
    return Offset(x, p.y)
}

private fun tapAt(
    at: Offset,
    frames: io.github.draumaz.twopane.ui.PageFrames,
    panelOpen: Boolean,
    chromeShown: Boolean,
    closePanel: () -> Unit,
    toggleChrome: () -> Unit,
    turn: (Boolean) -> Unit,
) {
    when {
        panelOpen -> closePanel()
        chromeShown -> toggleChrome()
        else -> {
            val left = frames.left
            if (left != null) {
                // On a spread, the outer edges turn and the inner pages, by the spine, bring up the controls.
                val w = frames.right.width
                when {
                    at.x < left.left + w * 0.38f -> turn(false)
                    at.x > frames.right.right - w * 0.38f -> turn(true)
                    else -> toggleChrome()
                }
            } else {
                val w = frames.right.width
                when {
                    at.x < w * 0.28f -> turn(false)
                    at.x > w * 0.62f -> turn(true)
                    else -> toggleChrome()
                }
            }
        }
    }
}

/** While pages are set: on a spread, the title on the left screen and the indicator on the right, clear of the hinge. */
@Composable
fun ReaderLoading(title: String, author: String?) {
    val split = LocalPaneSplit.current
    @Composable
    fun Indicator() = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        ContainedLoadingIndicator(Modifier.size(84.dp))
    }
    @Composable
    fun Title() = Column(Modifier.padding(horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.headlineSmallEmphasized, color = MaterialTheme.colorScheme.onSurface,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        author?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    if (split != null) {
        Panes(split, hingeGutter = 0.dp, start = { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Title() } }, end = { Indicator() })
    } else {
        Box(Modifier.fillMaxSize()) {
            Indicator()
            Box(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 96.dp)) { Title() }
        }
    }
}

@Composable
private fun Ribbon(visible: Boolean, modifier: Modifier) {
    val drop by animateFloatAsState(
        if (visible) 1f else 0f,
        spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessLow),
        label = "ribbon",
    )
    if (drop <= 0.001f) return
    val color = MaterialTheme.colorScheme.primary
    Box(
        modifier
            .width(26.dp)
            .height(72.dp)
            .graphicsLayer {
                translationY = -size.height * (1f - drop)
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0f)
            }
            .background(color, GenericShape { s, _ ->
                moveTo(0f, 0f)
                lineTo(s.width, 0f)
                lineTo(s.width, s.height)
                lineTo(s.width / 2f, s.height * 0.78f)
                lineTo(0f, s.height)
                close()
            })
    )
}

@Composable
private fun Chrome(
    visible: Boolean,
    book: Book,
    chapterTitle: String?,
    first: Int,
    perView: Int,
    count: Int,
    bookmarked: Boolean,
    onClose: () -> Unit,
    onJump: (Int) -> Unit,
    onPanel: (Panel) -> Unit,
    onBookmark: () -> Unit,
) {
    val split = LocalPaneSplit.current
    val enterTop = slideInVertically(spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow)) { -it } + fadeIn()
    val exitTop = slideOutVertically(spring(stiffness = Spring.StiffnessMedium)) { -it } + fadeOut()
    val enterBottom = slideInVertically(spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow)) { it } + fadeIn()
    val exitBottom = slideOutVertically(spring(stiffness = Spring.StiffnessMedium)) { it } + fadeOut()

    @Composable
    fun BoxScope.Top(content: @Composable () -> Unit) = AnimatedVisibility(
        visible, enter = enterTop, exit = exitTop,
        modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(12.dp),
    ) { content() }

    @Composable
    fun BoxScope.Bottom(content: @Composable () -> Unit) = AnimatedVisibility(
        visible, enter = enterBottom, exit = exitBottom,
        modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp),
    ) { content() }

    val titleBar = @Composable { TitleBar(book, onClose) }
    val scrubber = @Composable { Scrubber(first, perView, count, chapterTitle, onJump) }
    val toolbar = @Composable { Toolbar(bookmarked, onPanel, onBookmark) }

    if (split != null) {
        // A spread: book and position on the left screen, tools on the right, nothing in the hinge.
        Panes(split, start = {
            Top(titleBar)
            Bottom(scrubber)
        }, end = {
            Top { ChapterPill(chapterTitle, first, perView, count) }
            Bottom(toolbar)
        })
    } else {
        Box(Modifier.fillMaxSize()) {
            Top(titleBar)
            Bottom {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    scrubber()
                    toolbar()
                }
            }
        }
    }
}

@Composable
private fun TitleBar(book: Book, onClose: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 6.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
            FilledTonalIconButton(onClick = onClose, shapes = IconButtonDefaults.shapes()) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back to library")
            }
            Column(Modifier.padding(horizontal = 12.dp).weight(1f)) {
                Text(book.title, style = MaterialTheme.typography.titleMediumEmphasized, maxLines = 1, overflow = TextOverflow.Ellipsis)
                book.author?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun ChapterPill(chapterTitle: String?, first: Int, perView: Int, count: Int) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 6.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Rounded.MenuBook, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(
                chapterTitle ?: "—",
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 12.dp).weight(1f),
            )
            Text(
                "${percent(first + perView - 1, count)}%",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

private fun percent(last: Int, count: Int) =
    if (count <= 1) 100 else ((last.coerceAtMost(count - 1)).toFloat() / (count - 1) * 100).roundToInt()

@Composable
private fun Scrubber(first: Int, perView: Int, count: Int, chapterTitle: String?, onJump: (Int) -> Unit) {
    var dragging by remember { mutableStateOf(false) }
    var value by remember { mutableFloatStateOf(first.toFloat()) }
    if (!dragging) value = first.toFloat()
    val shown = value.roundToInt()
    val haptics = LocalHapticFeedback.current
    // A fast fling crosses hundreds of pages: cap the ticks so it purrs rather than buzzes.
    var lastTick by remember { mutableLongStateOf(0L) }
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 6.dp,
        modifier = Modifier.fillMaxWidth().widthIn(max = 560.dp),
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    chapterTitle ?: "",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                val label = if (perView == 2 && shown + 1 < count) "${shown + 1}–${shown + 2} of $count" else "${shown + 1} of $count"
                AnimatedContent(label, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "pageLabel") {
                    Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
            }
            Slider(
                value = value,
                onValueChange = {
                    dragging = true
                    val snapped = (it.roundToInt() / perView * perView).toFloat()
                    if (snapped != value) {
                        val now = android.os.SystemClock.uptimeMillis()
                        if (now - lastTick >= 30) {
                            haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                            lastTick = now
                        }
                    }
                    value = snapped
                },
                onValueChangeFinished = {
                    dragging = false
                    onJump(value.roundToInt())
                },
                valueRange = 0f..(count - 1).coerceAtLeast(1).toFloat(),
            )
        }
    }
}

@Composable
private fun Toolbar(bookmarked: Boolean, onPanel: (Panel) -> Unit, onBookmark: () -> Unit) {
    HorizontalFloatingToolbar(
        expanded = true,
        colors = FloatingToolbarDefaults.vibrantFloatingToolbarColors(),
    ) {
        IconButton(onClick = { onPanel(Panel.Contents) }, shapes = IconButtonDefaults.shapes()) {
            Icon(Icons.Rounded.Toc, contentDescription = "Contents")
        }
        IconButton(onClick = { onPanel(Panel.Appearance) }, shapes = IconButtonDefaults.shapes()) {
            Icon(Icons.Rounded.TextFields, contentDescription = "Text and page")
        }
        FilledIconToggleButton(
            checked = bookmarked,
            onCheckedChange = { onBookmark() },
            shapes = IconButtonDefaults.toggleableShapes(),
        ) {
            AnimatedContent(
                bookmarked,
                transitionSpec = {
                    (scaleIn(spring(dampingRatio = 0.4f, stiffness = Spring.StiffnessMedium), initialScale = 0.3f) + fadeIn())
                        .togetherWith(scaleOut(targetScale = 0.3f) + fadeOut())
                },
                label = "bookmark",
            ) { on ->
                Icon(if (on) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder, contentDescription = if (on) "Remove bookmark" else "Bookmark this page")
            }
        }
    }
}

/** Panels open over a page: contents on the left screen, appearance on the right, or from the bottom on one screen. */
@Composable
private fun Panels(
    panel: Panel,
    onDismiss: () -> Unit,
    contents: @Composable () -> Unit,
    appearance: @Composable () -> Unit,
) {
    val split = LocalPaneSplit.current
    val scrim by animateFloatAsState(if (panel != Panel.None) 0.28f else 0f, label = "scrim")
    if (scrim > 0f) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = scrim))
                .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(); onDismiss() } }
        )
    }

    @Composable
    fun BoxScope.Sheet(shown: Boolean, fullHeight: Boolean, content: @Composable () -> Unit) = AnimatedVisibility(
        shown,
        enter = slideInVertically(spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)) { it / 2 } + fadeIn(),
        exit = slideOutVertically(spring(stiffness = Spring.StiffnessMedium)) { it / 2 } + fadeOut(),
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(12.dp)
            .then(if (fullHeight) Modifier.fillMaxSize() else Modifier.fillMaxWidth().fillMaxHeight(0.72f)),
    ) {
        Surface(
            shape = RoundedCornerShape(32.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            shadowElevation = 12.dp,
            modifier = Modifier.fillMaxSize().pointerInput(Unit) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false) } },
        ) { content() }
    }

    if (split != null) {
        Panes(split, start = { Sheet(panel == Panel.Contents, true, contents) }, end = { Sheet(panel == Panel.Appearance, true, appearance) })
    } else {
        Box(Modifier.fillMaxSize()) {
            Sheet(panel == Panel.Contents, false, contents)
            Sheet(panel == Panel.Appearance, false, appearance)
        }
    }
}
