package au.josh.unifiphone.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import au.josh.unifiphone.data.ThemeMode

// UniFi-style palette: one confident blue, quiet neutrals, restrained accents.
val UbntBlue = Color(0xFF006FFF)
val UbntBlueDim = Color(0xFF3D8BFF)
val SuccessGreen = Color(0xFF38CC77)
val DangerRed = Color(0xFFF0383B)
val WarnAmber = Color(0xFFF5A524)

private val LightColors = lightColorScheme(
    primary = UbntBlue,
    onPrimary = Color.White,
    secondary = Color(0xFF50565E),
    background = Color(0xFFF6F8FA),
    onBackground = Color(0xFF1A2027),
    surface = Color.White,
    onSurface = Color(0xFF1A2027),
    surfaceVariant = Color(0xFFEDF1F5),
    onSurfaceVariant = Color(0xFF6B7480),
    outline = Color(0xFFDCE1E7),
    error = DangerRed,
)

private val DarkColors = darkColorScheme(
    primary = UbntBlueDim,
    onPrimary = Color.White,
    secondary = Color(0xFF9AA3AE),
    background = Color(0xFF0E1420),
    onBackground = Color(0xFFF0F3F7),
    surface = Color(0xFF151D2C),
    onSurface = Color(0xFFF0F3F7),
    surfaceVariant = Color(0xFF1D2637),
    onSurfaceVariant = Color(0xFF8A94A3),
    outline = Color(0xFF283246),
    error = DangerRed,
)

private val UniFiShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(10.dp),
    large = RoundedCornerShape(14.dp),
)

@Composable
fun UniFiPhoneTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        shapes = UniFiShapes,
        content = content,
    )
}
