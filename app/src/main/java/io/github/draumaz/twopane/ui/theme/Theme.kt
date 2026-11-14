package io.github.draumaz.twopane.ui.theme

import android.os.Build
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import io.github.draumaz.twopane.R
import io.github.draumaz.twopane.data.PageTheme

// Fallback for devices without dynamic color: bookbinder's leather on cream paper.
private val LightColors = lightColorScheme(
    primary = Color(0xFF8A4F1F), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDCC4), onPrimaryContainer = Color(0xFF6D3908),
    secondary = Color(0xFF745846), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFDCC6), onSecondaryContainer = Color(0xFF5A4130),
    tertiary = Color(0xFF5E6135), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE4E6AE), onTertiaryContainer = Color(0xFF464920),
    background = Color(0xFFFFF8F4), onBackground = Color(0xFF221A14),
    surface = Color(0xFFFFF8F4), onSurface = Color(0xFF221A14),
    surfaceVariant = Color(0xFFF3DED1), onSurfaceVariant = Color(0xFF52443B),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFFFF1E9),
    surfaceContainer = Color(0xFFFAEBE2), surfaceContainerHigh = Color(0xFFF4E5DC),
    surfaceContainerHighest = Color(0xFFEEE0D7), surfaceDim = Color(0xFFE7D7CE),
    surfaceBright = Color(0xFFFFF8F4), outline = Color(0xFF85746A), outlineVariant = Color(0xFFD7C3B7),
    inverseSurface = Color(0xFF382E28), inverseOnSurface = Color(0xFFFFEDE4), inversePrimary = Color(0xFFFFB781),
    surfaceTint = Color(0xFF8A4F1F),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFB781), onPrimary = Color(0xFF4F2500),
    primaryContainer = Color(0xFF6D3908), onPrimaryContainer = Color(0xFFFFDCC4),
    secondary = Color(0xFFE3BFA9), onSecondary = Color(0xFF412B1C),
    secondaryContainer = Color(0xFF5A4130), onSecondaryContainer = Color(0xFFFFDCC6),
    tertiary = Color(0xFFC8CA94), onTertiary = Color(0xFF30320B),
    tertiaryContainer = Color(0xFF464920), onTertiaryContainer = Color(0xFFE4E6AE),
    background = Color(0xFF19120D), onBackground = Color(0xFFEEE0D7),
    surface = Color(0xFF19120D), onSurface = Color(0xFFEEE0D7),
    surfaceVariant = Color(0xFF52443B), onSurfaceVariant = Color(0xFFD7C3B7),
    surfaceContainerLowest = Color(0xFF140D08), surfaceContainerLow = Color(0xFF221A14),
    surfaceContainer = Color(0xFF261E18), surfaceContainerHigh = Color(0xFF312822),
    surfaceContainerHighest = Color(0xFF3C332C), surfaceDim = Color(0xFF19120D),
    surfaceBright = Color(0xFF413731), outline = Color(0xFFA08D82), outlineVariant = Color(0xFF52443B),
    inverseSurface = Color(0xFFEEE0D7), inverseOnSurface = Color(0xFF382E28), inversePrimary = Color(0xFF8A4F1F),
    surfaceTint = Color(0xFFFFB781),
)

val LocalDarkTheme = staticCompositionLocalOf { false }

/** Google Sans Flex, one variable font for every weight, with real italics from its slant axis. */
val GoogleSansFlex = FontFamily(
    (100..900 step 100).flatMap { w ->
        listOf(false, true).map { italic ->
            Font(
                R.font.google_sans_flex,
                FontWeight(w),
                if (italic) FontStyle.Italic else FontStyle.Normal,
                variationSettings = FontVariation.Settings(
                    FontVariation.weight(w),
                    FontVariation.slant(if (italic) -10f else 0f),
                ),
            )
        }
    }
)

private fun Typography.withFamily(family: FontFamily): Typography {
    fun TextStyle.f() = copy(fontFamily = family)
    return Typography(
        displayLarge = displayLarge.f(), displayMedium = displayMedium.f(), displaySmall = displaySmall.f(),
        headlineLarge = headlineLarge.f(), headlineMedium = headlineMedium.f(), headlineSmall = headlineSmall.f(),
        titleLarge = titleLarge.f(), titleMedium = titleMedium.f(), titleSmall = titleSmall.f(),
        bodyLarge = bodyLarge.f(), bodyMedium = bodyMedium.f(), bodySmall = bodySmall.f(),
        labelLarge = labelLarge.f(), labelMedium = labelMedium.f(), labelSmall = labelSmall.f(),
        displayLargeEmphasized = displayLargeEmphasized.f(), displayMediumEmphasized = displayMediumEmphasized.f(),
        displaySmallEmphasized = displaySmallEmphasized.f(), headlineLargeEmphasized = headlineLargeEmphasized.f(),
        headlineMediumEmphasized = headlineMediumEmphasized.f(), headlineSmallEmphasized = headlineSmallEmphasized.f(),
        titleLargeEmphasized = titleLargeEmphasized.f(), titleMediumEmphasized = titleMediumEmphasized.f(),
        titleSmallEmphasized = titleSmallEmphasized.f(), bodyLargeEmphasized = bodyLargeEmphasized.f(),
        bodyMediumEmphasized = bodyMediumEmphasized.f(), bodySmallEmphasized = bodySmallEmphasized.f(),
        labelLargeEmphasized = labelLargeEmphasized.f(), labelMediumEmphasized = labelMediumEmphasized.f(),
        labelSmallEmphasized = labelSmallEmphasized.f(),
    )
}

private val AppTypography = Typography().withFamily(GoogleSansFlex)

@Composable
fun TwoPaneTheme(darkTheme: Boolean, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }
    CompositionLocalProvider(LocalDarkTheme provides darkTheme) {
        MaterialExpressiveTheme(
            colorScheme = scheme,
            motionScheme = MotionScheme.expressive(),
            typography = AppTypography,
            content = content,
        )
    }
}

/** A page's paper, ink and the quieter ink of running heads and folios. */
@Immutable
data class PageColors(val paper: Color, val ink: Color, val muted: Color, val dark: Boolean)

fun pageColors(theme: PageTheme, systemDark: Boolean): PageColors = when (theme) {
    PageTheme.Auto -> pageColors(if (systemDark) PageTheme.Night else PageTheme.Paper, systemDark)
    PageTheme.Paper -> PageColors(Color(0xFFFCF8F1), Color(0xFF26221C), Color(0xFF8F8576), false)
    PageTheme.Sepia -> PageColors(Color(0xFFF2E5CC), Color(0xFF3D2F1F), Color(0xFF8E7657), false)
    PageTheme.Dusk -> PageColors(Color(0xFF3B3A3E), Color(0xFFE7E3DC), Color(0xFFA29E97), true)
    PageTheme.Night -> PageColors(Color(0xFF17140F), Color(0xFFDBD3C6), Color(0xFF85796A), true)
    PageTheme.Black -> PageColors(Color(0xFF000000), Color(0xFFBDB6AC), Color(0xFF6E685F), true)
}
