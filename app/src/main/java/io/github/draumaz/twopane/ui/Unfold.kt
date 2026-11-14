package io.github.draumaz.twopane.ui

import android.graphics.Camera
import android.graphics.Matrix
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.first
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer

/*
 * The unfold: when the window goes from one screen to two (the Duo opened out, the app
 * spanned), both halves swing open from the hinge like a book's covers, on a spring that
 * carries them a touch past flat before they settle. Progress runs 0 (shut) to 1 (open flat);
 * it's read only in the draw phase, so the whole thing costs redraws, never recomposition.
 */

/** Unfold progress, 1 when nothing is unfolding. */
val LocalUnfold = staticCompositionLocalOf<() -> Float> { { 1f } }

/**
 * Whether what's on screen is ready to be shown open. The reader lowers it while it re-sets the
 * book for the new window, and the unfold holds shut until it's raised again.
 */
val LocalUnfoldReady = staticCompositionLocalOf { androidx.compose.runtime.mutableStateOf(true) }

/** Unfold progress for [spanned], which starts from shut whenever the window goes from one screen to two. */
@Composable
fun rememberUnfold(spanned: Boolean, ready: androidx.compose.runtime.State<Boolean>): () -> Float {
    val was = remember { mutableStateOf(spanned) }
    val unfold = remember(spanned) { Animatable(if (spanned && !was.value) 0f else 1f) }
    SideEffect { was.value = spanned }
    LaunchedEffect(unfold) {
        if (unfold.value < 1f) {
            // The second screen is still lighting up as the window spans: wait until it's on,
            // so the swing plays out where it can be seen.
            kotlinx.coroutines.delay(300)
            kotlinx.coroutines.withTimeoutOrNull(3_000) {
                androidx.compose.runtime.snapshotFlow { ready.value }.first { it }
            }
            unfold.animateTo(1f, spring(dampingRatio = 0.62f, stiffness = 70f))
        }
    }
    return remember(unfold) { { unfold.value } }
}

/** How far the right-hand half is turned from flat, in degrees: edge-on when shut. */
fun unfoldEndAngle(p: Float) = (1f - p) * 92f

/** The left-hand half tips back less, and the other way. */
fun unfoldStartAngle(p: Float) = -(1f - p) * 28f

/** The start pane, hinged on its right edge. */
fun Modifier.unfoldStart(unfold: () -> Float): Modifier = graphicsLayer {
    val p = unfold()
    if (p != 1f) {
        rotationY = unfoldStartAngle(p)
        transformOrigin = TransformOrigin(1f, 0.5f)
        cameraDistance = 30f * density
        alpha = (0.35f + p).coerceIn(0f, 1f)
    }
}

/** The end pane, hinged on its left edge. */
fun Modifier.unfoldEnd(unfold: () -> Float): Modifier = graphicsLayer {
    val p = unfold()
    if (p != 1f) {
        rotationY = unfoldEndAngle(p)
        transformOrigin = TransformOrigin(0f, 0.5f)
        cameraDistance = 30f * density
        alpha = (p * 2.5f).coerceIn(0f, 1f)
    }
}

private val camera = Camera()

/** A 3D turn of [degrees] about the vertical line x = [pivotX], as a matrix for a canvas. */
fun hingeMatrix(degrees: Float, pivotX: Float, pivotY: Float, out: Matrix, density: Float): Matrix {
    camera.save()
    camera.setLocation(0f, 0f, -30f * density / 2.5f * 1.6f)
    camera.rotateY(-degrees)
    camera.getMatrix(out)
    camera.restore()
    out.preTranslate(-pivotX, -pivotY)
    out.postTranslate(pivotX, pivotY)
    return out
}
