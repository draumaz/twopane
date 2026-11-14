package io.github.draumaz.twopane.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.toPath
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import io.github.draumaz.twopane.ui.theme.LocalDarkTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Kinetic building blocks shared with Lexeme: morphing shapes, press squish, bouncy entrances.

/** Draws a [Morph] between two normalized polygons as a [Shape]. */
class MorphShape(private val morph: Morph, private val progress: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val path = morph.toPath(progress)
        path.transform(Matrix().apply { scale(size.width, size.height) })
        return Outline.Generic(path)
    }
}

/** The expressive shape set, cycled through so neighbouring covers never match. */
val CoverShapes: List<RoundedPolygon> = listOf(
    MaterialShapes.Cookie9Sided, MaterialShapes.Clover4Leaf, MaterialShapes.Sunny,
    MaterialShapes.Cookie6Sided, MaterialShapes.Flower, MaterialShapes.Gem,
    MaterialShapes.SoftBurst, MaterialShapes.Cookie4Sided, MaterialShapes.Pentagon,
    MaterialShapes.Puffy, MaterialShapes.Cookie7Sided, MaterialShapes.Clover8Leaf,
)

fun shapeFor(seed: Int): RoundedPolygon = CoverShapes[Math.floorMod(seed, CoverShapes.size)]

/** Springy squish while pressed. */
@Composable
fun Modifier.pressScale(interactionSource: MutableInteractionSource, pressed: Float = 0.92f): Modifier {
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (isPressed) pressed else 1f,
        MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "pressScale",
    )
    return graphicsLayer { scaleX = scale; scaleY = scale }
}

/**
 * Pops content in from below with a bouncy spring the first time [key] is shown; [seen]
 * outlives the item so scrolling back doesn't replay it, and [order] staggers a screenful.
 */
@Composable
fun Modifier.bouncyEnter(key: Any, seen: MutableSet<Any>, order: Int = 0): Modifier {
    val first = remember(key) { seen.add(key) }
    if (!first) return this
    val progress = remember(key) { Animatable(0f) }
    LaunchedEffect(key) {
        delay(order.coerceIn(0, 10) * 40L)
        launch { progress.animateTo(1f, spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessMediumLow)) }
    }
    return graphicsLayer {
        val p = progress.value
        alpha = p.coerceIn(0f, 1f)
        translationY = (1f - p) * 48.dp.toPx()
        val s = 0.9f + 0.1f * p
        scaleX = s
        scaleY = s
        transformOrigin = TransformOrigin(0.5f, 0f)
    }
}

/** A filled expressive shape with centered content, optionally turning. */
@Composable
fun ShapeBadge(
    shape: RoundedPolygon,
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    rotation: () -> Float = { 0f },
    content: @Composable () -> Unit = {},
) {
    val outline = remember(shape) { MorphShape(Morph(shape, shape), 0f) }
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .matchParentSize()
                .graphicsLayer { rotationZ = rotation() }
                .background(color, outline)
        )
        content()
    }
}

/**
 * A slow, endless turn in degrees, one full turn every [periodMillis] (negative turns the other
 * way), read in the draw phase so it only redraws. Honours the system animation scale.
 */
@Composable
fun rememberSpin(periodMillis: Int = 18_000): State<Float> {
    val angle = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(periodMillis) {
        val scale = coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f
        if (scale <= 0f) return@LaunchedEffect
        val degreesPerNano = 360f / (periodMillis * scale * 1_000_000f)
        var last = -1L
        while (true) {
            withFrameNanos { now ->
                if (last >= 0) angle.floatValue = (angle.floatValue + (now - last) * degreesPerNano) % 360f
                last = now
            }
        }
    }
    return angle
}

/** A container color plus its content color, from one of twelve hues around the wheel. */
data class HuePair(val container: Color, val content: Color)

private val Hues = listOf(
    0xFFFFB4AB to 0xFF690005, 0xFFFFB77C to 0xFF4A2800, 0xFFF0C048 to 0xFF3F2E00,
    0xFFAED460 to 0xFF1F3700, 0xFF7FDA96 to 0xFF003918, 0xFF82D5CB to 0xFF003733,
    0xFF5FD4FC to 0xFF003545, 0xFFA9C7FF to 0xFF003063, 0xFFC3C0FF to 0xFF241F70,
    0xFFDFB7FF to 0xFF4A1772, 0xFFFFAAEE to 0xFF5B0F55, 0xFFFFB1C8 to 0xFF650033,
)

/** A stable hue for a seed, softened toward the theme so it sits in with dynamic color. */
@Composable
fun hueFor(seed: String): HuePair {
    val (bg, fg) = Hues[Math.floorMod(seed.hashCode(), Hues.size)]
    val primary = MaterialTheme.colorScheme.primary
    val base = Color(bg)
    val container = if (LocalDarkTheme.current) lerp(base, Color.Black, 0.12f) else lerp(base, Color.White, 0.3f)
    return HuePair(lerp(container, primary, 0.08f), Color(fg))
}
