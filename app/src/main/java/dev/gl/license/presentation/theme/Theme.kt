package dev.gl.license.presentation.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Gold = Color(0xFFE0B13A)
private val GoldDim = Color(0xFFB8891F)
private val Bg = Color(0xFF0B0F14)
private val Surface = Color(0xFF141A22)
private val SurfaceHi = Color(0xFF1C2530)
private val On = Color(0xFFF2F4F7)
private val Mute = Color(0xFF9AA4B2)
private val Danger = Color(0xFFFF8A80)
private val Ok = Color(0xFF5EE2A6)

private val Scheme = darkColorScheme(
    primary = Gold,
    onPrimary = Color(0xFF1A1404),
    primaryContainer = GoldDim,
    onPrimaryContainer = On,
    secondary = Color(0xFF8EC0FF),
    background = Bg,
    onBackground = On,
    surface = Surface,
    onSurface = On,
    surfaceVariant = SurfaceHi,
    onSurfaceVariant = Mute,
    error = Danger,
    onError = Color(0xFF1A0A0A),
    outline = Color(0xFF3A4554),
)

private val Type = Typography(
    headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 34.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 18.sp, lineHeight = 24.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 18.sp),
)

val GlShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

object GlDimens {
    val screen = 20.dp
    val gap = 12.dp
    val touch = 52.dp
    val radius = 16.dp
    val field = 140.dp
}

object GlMotion {
    const val fast = 150
    const val normal = 220
    const val slow = 300
}

@Composable
fun GlTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = Scheme,
        typography = Type,
        shapes = GlShapes,
        content = content,
    )
}

object GlColors {
    val gold = Gold
    val ok = Ok
    val mute = Mute
}
