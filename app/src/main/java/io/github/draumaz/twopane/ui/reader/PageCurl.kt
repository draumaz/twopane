package io.github.draumaz.twopane.ui.reader

import android.graphics.BitmapShader
import android.graphics.Canvas as NativeCanvas
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.createBitmap
import io.github.draumaz.twopane.ui.PageFrames
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * The page curl. A turning sheet is modelled as paper wrapped round a cylinder: everything on
 * the spine's side of the cylinder's axis lies flat, the paper past it wraps round the cylinder
 * (its front facing up for the first quarter-turn, its back after), and whatever's past half a
 * turn lies flat again, face down, back over the sheet. The axis is placed so that the corner
 * you grabbed lands under your finger, and it tilts as you drag up or down.
 *
 * Geometry is worked out in sheet space: u runs from the spine (0) out to the sheet's free edge
 * (W), v down the page. Sheets on the left of the spine are mirrored into it, so one model
 * turns either way. Sheet space maps to the screen around the spine, skipping the hinge: the
 * page lands on the other screen as if the hinge weren't there.
 */

private const val PI_F = PI.toFloat()

/** The axis: a point [q] on it, the unit normal [n] pointing toward the free edge, and the radius [r]. */
class CurlGeometry(val q: Offset, val n: Offset, val r: Float)

private fun rawGeometry(s: Offset, p: Offset, w: Float, rMax: Float): CurlGeometry? {
    val dx = s.x - p.x
    val dy = s.y - p.y
    val len = sqrt(dx * dx + dy * dy)
    if (len < 0.5f) return null
    val n = Offset(dx / len, dy / len)
    // The curl tightens as the sheet comes down flat on the far side.
    val r = rMax * min(1f, len / (rMax * PI_F * 1.5f)) * ((p.x + w) / (0.6f * w)).coerceIn(0f, 1f)
    // Grabbed point S, a distance len from the finger along n, wraps half a turn and lands on it.
    val a = max(0f, (len - PI_F * r) / 2f)
    return CurlGeometry(Offset(p.x + n.x * a, p.y + n.y * a), n, r)
}

/**
 * The curl for a sheet [w] by [h] grabbed at [s] and pulled to [p]. Vertical pull is damped,
 * and eased off further wherever the axis would cross the spine and peel the sheet away from it.
 */
fun curlGeometry(s: Offset, p: Offset, w: Float, h: Float, rMax: Float): CurlGeometry? {
    val px = p.x.coerceIn(-w, w + 1f)
    var dy = ((p.y - s.y) * 0.55f).coerceIn(-0.3f * h, 0.3f * h)
    repeat(8) {
        val g = rawGeometry(s, Offset(px, s.y + dy), w, rMax) ?: return null
        val top = -g.q.x * g.n.x - g.q.y * g.n.y
        val bottom = -g.q.x * g.n.x + (h - g.q.y) * g.n.y
        if (max(top, bottom) <= 1f || dy == 0f) return g
        dy *= 0.6f
        if (abs(dy) < 1f) dy = 0f
    }
    return rawGeometry(s, Offset(px, s.y), w, rMax)
}

/**
 * One page turn in progress. [front] is the sheet's face, [back] its reverse (the next page in a
 * spread; a faded copy of the face on a single page), and [under] what the turn uncovers.
 */
class Turn(
    val sheetLeft: Boolean,
    val front: ImageBitmap,
    val back: ImageBitmap,
    val under: Int,
    /** True when finishing means the sheet lies flat on the far side; false when it ends flat where it started. */
    val forward: Boolean,
    /** The page index once this turn completes. */
    val result: Int,
    val grab: Offset,
    val w: Float,
    val h: Float,
) {
    var pull by mutableStateOf(grab)

    val flat: Offset get() = grab
    val turned: Offset get() = Offset(-w, grab.y)

    /** How far over the sheet is, 0 lying where it started to 1 lying on the far side. */
    val over: Float get() = ((grab.x - pull.x) / (2 * w)).coerceIn(0f, 1f)

    private val frontShader by lazy { BitmapShader(front.asAndroidBitmap(), Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
    private val backShader by lazy { BitmapShader(back.asAndroidBitmap(), Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
    val frontPaint by lazy { Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { shader = frontShader } }
    val backPaint by lazy { Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { shader = backShader } }

    // The mesh, reused frame to frame. Columns run across the sheet, where the curl bends it.
    val cols = 56
    val rows = 28
    private val count = (cols + 1) * (rows + 1)
    val verts = FloatArray(count * 2)
    val texFront = FloatArray(count * 2)
    val texBack = FloatArray(count * 2)
    // Canvas.drawVertices checks colors against the float count, not the vertex count.
    val colorsFront = IntArray(count * 2)
    val colorsBack = IntArray(count * 2)
    val depth = FloatArray(count)
    val idxFront = ShortArray(cols * rows * 6)
    val idxBack = ShortArray(cols * rows * 6)
    var nFront = 0
    var nBack = 0
}

private fun gray(level: Float): Int {
    val c = (level.coerceIn(0f, 1f) * 255f).toInt()
    return (0xFF shl 24) or (c shl 16) or (c shl 8) or c
}

/** Draws [t] over whatever's beneath it (already drawn), within [frames]. */
fun DrawScope.drawTurn(t: Turn, frames: PageFrames, shadowDark: Boolean) {
    val w = t.w
    val h = t.h
    val rMax = w * 0.1f
    val g = curlGeometry(t.grab, t.pull, w, h, rMax)
    val right = frames.right
    val spineRight = right.left.toFloat()
    val spineLeft = frames.left?.right?.toFloat() ?: spineRight
    // Sheet space u to the screen: mirrored for a left sheet, and across the hinge when negative.
    fun screenX(u: Float): Float {
        val x = if (t.sheetLeft) -u else u
        return if (x >= 0f) spineRight + x else spineLeft + x
    }
    val sheetRect = if (t.sheetLeft) frames.left!! else right

    if (g == null) {
        drawImage(t.front, Offset(sheetRect.left.toFloat(), sheetRect.top.toFloat()))
        return
    }
    val q = g.q
    val n = g.n
    val r = g.r
    val halfTurn = PI_F * r

    // The shadow the lifted paper casts on the page it uncovers.
    run {
        val sw = r * 1.4f + 18f * density
        val from = Offset(q.x + n.x * r, q.y + n.y * r)
        val to = Offset(from.x + n.x * sw, from.y + n.y * sw)
        val strength = (if (shadowDark) 0.55f else 0.28f) * (1f - t.over * 0.6f)
        clipRect(sheetRect.left.toFloat(), sheetRect.top.toFloat(), sheetRect.right.toFloat(), sheetRect.bottom.toFloat()) {
            drawRect(
                Brush.linearGradient(
                    listOf(Color.Black.copy(alpha = strength), Color.Transparent),
                    start = Offset(screenX(from.x), from.y),
                    end = Offset(screenX(to.x), to.y),
                ),
            )
        }
    }

    val cols = t.cols
    val rows = t.rows
    var k = 0
    for (row in 0..rows) {
        val v = h * row / rows
        for (col in 0..cols) {
            val u = w * col / cols
            val d = (u - q.x) * n.x + (v - q.y) * n.y
            val pu: Float
            val pv: Float
            val shadeFront: Float
            val shadeBack: Float
            when {
                d <= 0f -> {
                    pu = u; pv = v
                    shadeFront = 1f; shadeBack = 1f
                }
                d < halfTurn -> {
                    val theta = d / r
                    val along = r * sin(theta) - d
                    pu = u + n.x * along
                    pv = v + n.y * along
                    val s = sin(theta)
                    // The face darkens as it rolls away from the light; the reverse is lit as it comes over.
                    shadeFront = 1f - 0.32f * s * s
                    shadeBack = 0.8f + 0.2f * (1f - s) - 0.06f * (1f + cos(theta))
                }
                else -> {
                    val along = halfTurn - 2f * d
                    pu = u + n.x * along
                    pv = v + n.y * along
                    shadeFront = 0.92f; shadeBack = 0.97f
                }
            }
            t.verts[2 * k] = screenX(pu)
            t.verts[2 * k + 1] = pv
            t.depth[k] = d
            val faceU = if (t.sheetLeft) w - u else u
            t.texFront[2 * k] = faceU
            t.texFront[2 * k + 1] = v
            // The reverse of a right-hand sheet is a left-hand page and vice versa; a single
            // page's reverse is its own (faded) face, seen through the paper.
            t.texBack[2 * k] = when {
                t.back === t.front -> faceU
                t.sheetLeft -> u
                else -> w - u
            }
            t.texBack[2 * k + 1] = v
            t.colorsFront[k] = gray(shadeFront)
            t.colorsBack[k] = gray(shadeBack)
            k++
        }
    }

    // Each cell faces up or down depending on how far round the cylinder it has gone.
    var nf = 0
    var nb = 0
    val quarter = halfTurn / 2f
    for (row in 0 until rows) {
        for (col in 0 until cols) {
            val a = row * (cols + 1) + col
            val b = a + 1
            val c = a + cols + 1
            val dd = c + 1
            val mid = (t.depth[a] + t.depth[b] + t.depth[c] + t.depth[dd]) / 4f
            if (mid > quarter && r >= 0f && mid > 0f) {
                t.idxBack[nb++] = a.toShort(); t.idxBack[nb++] = c.toShort(); t.idxBack[nb++] = b.toShort()
                t.idxBack[nb++] = b.toShort(); t.idxBack[nb++] = c.toShort(); t.idxBack[nb++] = dd.toShort()
            } else {
                t.idxFront[nf++] = a.toShort(); t.idxFront[nf++] = c.toShort(); t.idxFront[nf++] = b.toShort()
                t.idxFront[nf++] = b.toShort(); t.idxFront[nf++] = c.toShort(); t.idxFront[nf++] = dd.toShort()
            }
        }
    }
    t.nFront = nf
    t.nBack = nb

    drawIntoCanvas { canvas ->
        val nc: NativeCanvas = canvas.nativeCanvas
        val floats = t.verts.size
        if (nf > 0) nc.drawVertices(
            NativeCanvas.VertexMode.TRIANGLES, floats, t.verts, 0, t.texFront, 0, t.colorsFront, 0,
            t.idxFront, 0, nf, t.frontPaint,
        )
        if (nb > 0) nc.drawVertices(
            NativeCanvas.VertexMode.TRIANGLES, floats, t.verts, 0, t.texBack, 0, t.colorsBack, 0,
            t.idxBack, 0, nb, t.backPaint,
        )
    }
}

/** A single page's reverse: its face, faint through the paper. */
fun fadedReverse(face: ImageBitmap, paper: Color): ImageBitmap {
    val bmp = createBitmap(face.width, face.height)
    val c = NativeCanvas(bmp)
    c.drawColor(paper.toArgb())
    c.drawBitmap(face.asAndroidBitmap(), 0f, 0f, Paint().apply { alpha = 40 })
    return bmp.asImageBitmap()
}
