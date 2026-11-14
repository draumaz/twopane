package io.github.draumaz.twopane.ui.reader

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.text.TextMeasurer
import io.github.draumaz.twopane.ui.PageFrames
import io.github.draumaz.twopane.ui.theme.PageColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Rendered pages, a few either side of where the reader is, drawn in the background so a turn
 * never waits on typesetting. Bitmaps are snapshot state: a page that finishes rendering
 * redraws whatever is showing it.
 */
class PageCache(
    private val scope: CoroutineScope,
    private val typesetter: Typesetter,
    val pages: List<Page>,
    val colors: PageColors,
    private val sideOf: (Int) -> Side,
) {
    private val bitmaps = mutableStateMapOf<Int, ImageBitmap>()
    private val jobs = HashMap<Int, Job>()
    private val render = Dispatchers.Default.limitedParallelism(3)
    private val measurers = ThreadLocal<TextMeasurer>()

    private fun draw(i: Int): ImageBitmap {
        val m = measurers.get() ?: typesetter.measurer().also(measurers::set)
        return typesetter.render(m, pages[i], i + 1, sideOf(i), colors)
    }

    /** The cache this one replaced, whose pages show until this one has drawn its own. */
    var fallback: PageCache? = null

    operator fun get(i: Int): ImageBitmap? = bitmaps[i] ?: fallback?.takeIf { it.pages === pages }?.bitmaps?.get(i)

    /** Starts rendering page [i] if it isn't already. Main thread only. */
    fun request(i: Int) {
        if (i !in pages.indices || i in bitmaps || i in jobs) return
        jobs[i] = scope.launch {
            val bmp = withContext(render) { draw(i) }
            bitmaps[i] = bmp
            jobs.remove(i)
        }
    }

    /** Page [i] now, rendered on the spot if need be; a blank sheet past either end of the book. */
    fun now(i: Int): ImageBitmap {
        bitmaps[i]?.let { return it }
        if (i !in pages.indices) return blank
        jobs.remove(i)?.cancel()
        return draw(i).also { bitmaps[i] = it }
    }

    val blank: ImageBitmap by lazy {
        ImageBitmap(typesetter.size.width, typesetter.size.height).also {
            Canvas(it).drawRect(0f, 0f, it.width.toFloat(), it.height.toFloat(), Paint().apply { color = colors.paper })
        }
    }

    /** Lets go of pages outside [keep]. */
    fun trim(keep: IntRange) {
        bitmaps.keys.filter { it !in keep }.forEach { bitmaps.remove(it) }
        jobs.keys.filter { it !in keep }.forEach { jobs.remove(it)?.cancel() }
    }
}

/** Material's emphasized easing, for a tapped turn. */
private val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)

/**
 * Turns pages: starts a [Turn] when a drag or tap asks for one, follows the finger, and on
 * release lets the sheet fall the rest of the way over, or back, with the fling's velocity.
 */
class PageTurner(
    private val scope: CoroutineScope,
    private val frames: PageFrames,
    private val cache: PageCache,
    private val first: () -> Int,
    private val onTurned: (Int) -> Unit,
) {
    var turn by mutableStateOf<Turn?>(null)
        private set
    private var settling: Job? = null
    private var settlesComplete = false

    private val w = frames.right.width.toFloat()
    private val h = frames.right.height.toFloat()
    private val count get() = cache.pages.size

    fun canTurn(forward: Boolean): Boolean {
        val f = first()
        return if (frames.spread) {
            if (forward) f + 2 < count else f - 2 >= 0
        } else {
            if (forward) f + 1 < count else f - 1 >= 0
        }
    }

    /** Lifts a sheet, grabbed at height [grabY]: the next one if [forward], else the previous. */
    fun begin(forward: Boolean, grabY: Float): Boolean {
        finishNow()
        if (!canTurn(forward)) return false
        val f = first()
        val grab = Offset(w, grabY.coerceIn(h * 0.04f, h * 0.96f))
        turn = if (frames.spread) {
            if (forward) Turn(false, cache.now(f + 1), cache.now(f + 2), f + 3, true, f + 2, grab, w, h)
            else Turn(true, cache.now(f), cache.now(f - 1), f - 2, true, f - 2, grab, w, h)
        } else {
            if (forward) {
                val face = cache.now(f)
                Turn(false, face, fadedReverse(face, cache.colors.paper), f + 1, true, f + 1, grab, w, h)
            } else {
                // Going back on a single page, the previous page comes back over from the left.
                val face = cache.now(f - 1)
                Turn(false, face, fadedReverse(face, cache.colors.paper), f, false, f - 1, grab, w, h)
                    .also { it.pull = it.turned }
            }
        }
        return true
    }

    /** Follows a finger that has moved [delta] since it went down. */
    fun drag(delta: Offset) {
        val t = turn ?: return
        val dx = if (t.sheetLeft) -delta.x else delta.x
        t.pull = if (t.forward) {
            Offset(t.grab.x + dx * (if (frames.spread) 1.05f else 1.6f), t.grab.y + delta.y)
        } else {
            Offset((-w + 2f * dx).coerceIn(-w, w), t.grab.y + delta.y)
        }
    }

    /** Lets go with horizontal velocity [vx] (screen pixels a second). */
    fun release(vx: Float) {
        val t = turn ?: return
        val v = if (t.sheetLeft) -vx else vx
        val fling = 350f
        val complete = if (t.forward) {
            v < -fling || (v <= fling && t.over > 0.28f)
        } else {
            v > fling || (v >= -fling && t.over < 0.72f)
        }
        val target = if (complete == t.forward) t.turned else t.flat
        settle(t, target, complete, v)
    }

    /** A whole turn on its own, for a tap or a key. */
    fun tap(forward: Boolean) {
        if (!begin(forward, h * 0.9f)) return
        val t = turn ?: return
        val from = t.pull
        val target = if (t.forward) t.turned else t.flat
        settlesComplete = true
        settling = scope.launch {
            val a = Animatable(0f)
            a.animateTo(1f, tween(620, easing = Emphasized)) {
                // The corner lifts and curls up a little on its way over.
                val lift = -h * 0.14f * sin(PI.toFloat() * value)
                t.pull = lerp(from, target, value) + Offset(0f, lift)
            }
            finish(t, true)
        }
    }

    private fun settle(t: Turn, target: Offset, complete: Boolean, velocity: Float) {
        val from = t.pull
        val distance = (target - from).getDistance()
        settlesComplete = complete
        settling = scope.launch {
            if (distance > 1f) {
                val a = Animatable(0f)
                val towards = if (target.x < from.x) -velocity else velocity
                val initial = (towards / distance).coerceIn(0f, 12f)
                a.animateTo(1f, spring(dampingRatio = 1f, stiffness = 420f), initialVelocity = initial) {
                    t.pull = lerp(from, target, value.coerceAtMost(1f))
                }
            }
            finish(t, complete)
        }
    }

    private fun finish(t: Turn, complete: Boolean) {
        if (turn !== t) return
        if (complete) onTurned(t.result)
        turn = null
        settling = null
    }

    /** Ends a turn that's still settling right away, where it was headed. */
    fun finishNow() {
        val t = turn ?: return
        val job = settling ?: return
        job.cancel()
        finish(t, settlesComplete)
    }

    /** Drops a turn outright, without completing it. */
    fun cancel() {
        settling?.cancel()
        settling = null
        turn = null
    }

    val busy: Boolean get() = turn != null

    @Suppress("unused")
    private fun near(a: Float, b: Float) = abs(a - b) < 0.5f
}
