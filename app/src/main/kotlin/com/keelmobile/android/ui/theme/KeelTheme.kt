package com.keelmobile.android.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path

/**
 * Canonical Keel visual identity palette from keel/docs/brand/README.md
 * and keel-mobile/Sources/KeelStyle.swift.
 */
object KeelPalette {
    val Ink = Color(0xFF0A1112)
    val SurfaceDark = Color(0xFF101A1C)
    val SurfaceRaisedDark = Color(0xFF162426)
    val Paper = Color(0xFFF7F3E9)
    val SurfaceLight = Color(0xFFFFFFFF)
    val SurfaceRaisedLight = Color(0xFFECE6D9)
    val Foam = Color(0xFFEEF4F2)
    val TextLightMode = Color(0xFF2A241C)
    val MutedDark = Color(0xFF8CA3A0)
    val MutedLight = Color(0xFF7A7266)
    val Jade = Color(0xFF36B394)
    val JadeBright = Color(0xFF76DFC2)
    val JadeDeep = Color(0xFF217A65)
    val JadeLightAccent = Color(0xFF1E705C)
    val Rust = Color(0xFFDC7648)
    val RustBright = Color(0xFFF09467)
    val RustDeep = Color(0xFF8F4224)
    val RustLightAccent = Color(0xFF9E4722)
    val BubbleLight = Color(0xFFE5EAE0)
}

data class KeelExtendedColors(
    val background: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val textPrimary: Color,
    val textMuted: Color,
    val accent: Color,
    val detail: Color,
    val userBubble: Color,
    val hairline: Color
)

private val DarkKeelColors = KeelExtendedColors(
    background = KeelPalette.Ink,
    surface = KeelPalette.SurfaceDark,
    surfaceRaised = KeelPalette.SurfaceRaisedDark,
    textPrimary = KeelPalette.Foam,
    textMuted = KeelPalette.MutedDark,
    accent = KeelPalette.JadeBright,
    detail = KeelPalette.Rust,
    userBubble = KeelPalette.SurfaceRaisedDark,
    hairline = KeelPalette.JadeBright.copy(alpha = 0.18f)
)

private val LightKeelColors = KeelExtendedColors(
    background = KeelPalette.Paper,
    surface = KeelPalette.SurfaceLight,
    surfaceRaised = KeelPalette.SurfaceRaisedLight,
    textPrimary = KeelPalette.TextLightMode,
    textMuted = KeelPalette.MutedLight,
    accent = KeelPalette.JadeLightAccent,
    detail = KeelPalette.RustLightAccent,
    userBubble = KeelPalette.BubbleLight,
    hairline = KeelPalette.JadeLightAccent.copy(alpha = 0.18f)
)

val LocalKeelColors = staticCompositionLocalOf { DarkKeelColors }

@Composable
fun KeelTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val ext = if (darkTheme) DarkKeelColors else LightKeelColors
    val m3Scheme = if (darkTheme) {
        darkColorScheme(
            primary = KeelPalette.JadeBright,
            onPrimary = KeelPalette.Ink,
            secondary = KeelPalette.Rust,
            onSecondary = KeelPalette.Ink,
            background = KeelPalette.Ink,
            onBackground = KeelPalette.Foam,
            surface = KeelPalette.SurfaceDark,
            onSurface = KeelPalette.Foam,
            surfaceVariant = KeelPalette.SurfaceRaisedDark,
            onSurfaceVariant = KeelPalette.MutedDark,
            outline = KeelPalette.JadeBright.copy(alpha = 0.25f)
        )
    } else {
        lightColorScheme(
            primary = KeelPalette.JadeLightAccent,
            onPrimary = Color.White,
            secondary = KeelPalette.RustLightAccent,
            onSecondary = Color.White,
            background = KeelPalette.Paper,
            onBackground = KeelPalette.TextLightMode,
            surface = KeelPalette.SurfaceLight,
            onSurface = KeelPalette.TextLightMode,
            surfaceVariant = KeelPalette.SurfaceRaisedLight,
            onSurfaceVariant = KeelPalette.MutedLight,
            outline = KeelPalette.JadeLightAccent.copy(alpha = 0.25f)
        )
    }

    CompositionLocalProvider(LocalKeelColors provides ext) {
        MaterialTheme(
            colorScheme = m3Scheme,
            content = content
        )
    }
}

/**
 * Exact Keel jade and rust sail mark, matching KeelStyle.swift cubic Bezier geometry.
 */
@Composable
fun KeelMark(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.aspectRatio(1f)) {
        val sx = size.width / 512f
        val sy = size.height / 512f

        val rustPath = Path().apply {
            moveTo(86f * sx, 426f * sy)
            cubicTo(214f * sx, 357f * sy, 316f * sx, 367f * sy, 426f * sx, 391f * sy)
            lineTo(426f * sx, 252f * sy)
            cubicTo(418f * sx, 337f * sy, 231f * sx, 336f * sy, 86f * sx, 426f * sy)
            close()
        }
        drawPath(
            path = rustPath,
            brush = Brush.linearGradient(
                colors = listOf(KeelPalette.RustDeep, KeelPalette.RustBright),
                start = Offset(86f * sx, 340f * sy),
                end = Offset(426f * sx, 340f * sy)
            )
        )

        val jadePath = Path().apply {
            moveTo(86f * sx, 426f * sy)
            lineTo(426f * sx, 86f * sy)
            lineTo(426f * sx, 252f * sy)
            cubicTo(426f * sx, 328f * sy, 304f * sx, 324f * sy, 203f * sx, 367f * sy)
            cubicTo(157f * sx, 386f * sy, 118f * sx, 408f * sy, 86f * sx, 426f * sy)
            close()
        }
        drawPath(
            path = jadePath,
            brush = Brush.linearGradient(
                colors = listOf(KeelPalette.JadeBright, KeelPalette.JadeDeep),
                start = Offset(86f * sx, 86f * sy),
                end = Offset(426f * sx, 426f * sy)
            )
        )
    }
}
