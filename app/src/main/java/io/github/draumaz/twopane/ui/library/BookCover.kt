package io.github.draumaz.twopane.ui.library

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.draumaz.twopane.data.BookEntry
import io.github.draumaz.twopane.ui.LocalLibrary
import io.github.draumaz.twopane.ui.components.ShapeBadge
import io.github.draumaz.twopane.ui.components.hueFor
import io.github.draumaz.twopane.ui.components.shapeFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val covers = LruCache<String, ImageBitmap>(48)

/** A book's cover is bound on the left: square at the spine, rounded at the fore-edge. */
val CoverShape = RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp, topEnd = 14.dp, bottomEnd = 14.dp)

@Composable
fun BookCover(entry: BookEntry, modifier: Modifier = Modifier, elevation: androidx.compose.ui.unit.Dp = 6.dp) {
    val library = LocalLibrary.current
    val image by produceState(covers.get(entry.id), entry.id, entry.hasCover) {
        if (value == null && entry.hasCover) {
            value = withContext(Dispatchers.IO) {
                runCatching {
                    BitmapFactory.decodeFile(library.coverFile(entry.id).path)?.asImageBitmap()
                }.getOrNull()
            }?.also { covers.put(entry.id, it) }
        }
    }
    Box(modifier.shadow(elevation, CoverShape).clip(CoverShape)) {
        val img = image
        if (img != null) {
            Image(img, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            GeneratedCover(entry)
        }
        // The spine's fold, and a sheen along the cover.
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(0.09f)
                .background(Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.22f), Color.White.copy(alpha = 0.10f), Color.Transparent)))
        )
    }
}

/** For a book without cover art: its own hue, an expressive shape, and the title set large. */
@Composable
private fun GeneratedCover(entry: BookEntry) {
    val hue = hueFor(entry.title)
    BoxWithConstraints(Modifier.fillMaxSize().background(hue.container)) {
        val w = maxWidth
        ShapeBadge(
            shapeFor(entry.title.hashCode() / 7),
            hue.content.copy(alpha = 0.12f),
            size = w * 1.1f,
            modifier = Modifier.align(Alignment.BottomEnd).offset(x = w * 0.35f, y = w * 0.3f),
            rotation = { 18f },
        )
        Column(Modifier.padding(start = w * 0.14f, end = w * 0.08f, top = w * 0.14f)) {
            androidx.compose.material3.Text(
                entry.title,
                color = hue.content,
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Medium,
                fontSize = (w.value * 0.13f).coerceIn(11f, 30f).sp,
                lineHeight = (w.value * 0.155f).coerceIn(13f, 36f).sp,
                maxLines = 5,
                overflow = TextOverflow.Ellipsis,
            )
            entry.author?.let {
                androidx.compose.material3.Text(
                    it,
                    color = hue.content.copy(alpha = 0.75f),
                    fontSize = (w.value * 0.075f).coerceIn(8f, 16f).sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = w * 0.05f),
                )
            }
        }
    }
}
