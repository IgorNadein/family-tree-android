package app.familygem.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val wine = Color(0xFF853451)
private val sand = Color(0xFFFFE0B8)

private val light = lightColorScheme(
    primary = wine,
    onPrimary = Color.White,
    secondary = Color(0xFF80617A),
    tertiary = Color(0xFF976325),
    background = Color(0xFFFFFBFF),
    surface = Color(0xFFFFFBFF),
    surfaceVariant = Color(0xFFF5ECF1),
    onSurface = Color(0xFF231E21),
    onSurfaceVariant = Color(0xFF645860),
)

private val dark = darkColorScheme(
    primary = Color(0xFFF7B0C9),
    onPrimary = Color(0xFF521D34),
    secondary = Color(0xFFE7BADB),
    tertiary = sand,
    background = Color(0xFF191317),
    surface = Color(0xFF221C20),
    surfaceVariant = Color(0xFF3D3038),
)

@Composable
fun FamilyTreeTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val isDark = isSystemInDarkTheme()
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && isDark -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context)
        isDark -> dark
        else -> light
    }
    MaterialTheme(colorScheme = colors, content = content)
}
