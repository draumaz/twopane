package io.github.draumaz.twopane.ui

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker

/*
 * Hinge-aware layout, after Lexeme's Panes.kt. A book only ever splits side by side: a vertical
 * fold or hinge (the Surface Duo's two screens, a book-style foldable's crease) becomes the
 * spine, and nothing is ever drawn into the hinge. A horizontal fold (the Duo on its side)
 * gets one page; a wide landscape tablet gets a spread split down the middle.
 */

/** Both panes of a split, in dp, from the window's left edge. [hinge] is a physical gap, not a crease. */
@Immutable
data class PaneSplit(val start: Dp, val gap: Dp, val end: Dp, val hinge: Boolean) {
    val endOffset: Dp get() = start + gap
}

val LocalPaneSplit = staticCompositionLocalOf<PaneSplit?> { null }

/** The page frames in window pixels: a spread's two pages, or [left] null for a single page. */
@Immutable
data class PageFrames(val left: IntRect?, val right: IntRect) {
    val spread: Boolean get() = left != null
    /** Where a touch counts as the left page: up to the middle of the gap between the pages. */
    val spineX: Int get() = left?.let { (it.right + right.left) / 2 } ?: right.left
}

private val ExpandedWidth = 840.dp
private val MinPaneWidth = 300.dp
private val MinSplitHeight = 400.dp

/**
 * The split for this window, or null for one pane. Recomputed when the window or its folds
 * change (spanning the Duo, unfolding, rotating), without recreating the activity.
 */
@Composable
fun rememberPaneSplit(): PaneSplit? {
    val activity = LocalActivity.current
    val layoutFlow = remember(activity) {
        activity?.let { WindowInfoTracker.getOrCreate(it).windowLayoutInfo(it) }
    }
    val layoutInfo = layoutFlow?.collectAsStateWithLifecycle(initialValue = null)?.value
    val size = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current
    return remember(layoutInfo, size, density) {
        with(density) {
            val width = size.width.toDp()
            val height = size.height.toDp()
            val fold = layoutInfo?.displayFeatures?.filterIsInstance<FoldingFeature>()?.firstOrNull {
                it.orientation == FoldingFeature.Orientation.VERTICAL &&
                    it.bounds.left.toDp() >= MinPaneWidth && width - it.bounds.right.toDp() >= MinPaneWidth
            }
            when {
                fold != null && height >= MinSplitHeight -> {
                    val start = fold.bounds.left.toDp()
                    val gap = (fold.bounds.right - fold.bounds.left).toDp()
                    PaneSplit(start, gap, width - start - gap, hinge = fold.isSeparating && gap > 0.dp)
                }
                width >= ExpandedWidth && width > height * 1.15f && height >= MinSplitHeight -> {
                    val start = width / 2
                    PaneSplit(start, 0.dp, width - start, hinge = false)
                }
                else -> null
            }
        }
    }
}

/** The page frames for [split] over the whole window, in pixels. */
@Composable
fun rememberPageFrames(split: PaneSplit?): PageFrames {
    val size = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current
    return remember(split, size, density) {
        if (split == null) {
            PageFrames(null, IntRect(0, 0, size.width, size.height))
        } else with(density) {
            val leftEnd = split.start.roundToPx()
            val rightStart = split.endOffset.roundToPx()
            // Pages are typeset once for both sides, so both get the narrower width.
            val w = minOf(leftEnd, size.width - rightStart)
            PageFrames(
                IntRect(leftEnd - w, 0, leftEnd, size.height),
                IntRect(rightStart, 0, rightStart + w, size.height),
            )
        }
    }
}

/** The two panes of [split] side by side, each filling the height; the gap stays empty. */
@Composable
fun Panes(
    split: PaneSplit,
    modifier: Modifier = Modifier,
    hingeGutter: Dp = HingeGutter,
    start: @Composable BoxScope.() -> Unit,
    end: @Composable BoxScope.() -> Unit,
) {
    Row(modifier.fillMaxSize()) {
        Box(Modifier.width(split.start).fillMaxHeight().padding(end = hingeGutter), content = start)
        Spacer(Modifier.width(split.gap))
        Box(Modifier.weight(1f).fillMaxHeight().padding(start = hingeGutter), content = end)
    }
}

/** Extra room kept beside the hinge (or crease), so content doesn't crowd the spine. */
val HingeGutter = 20.dp
