/** Applies Shahbaz color and typography tokens to a Material 3 Compose hierarchy. */
package ir.hrka.shahbaz.core.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/** Static dark color scheme used when dynamic colors are disabled. */
private val DarkColorScheme = darkColorScheme(
    primary = Forest80,
    secondary = Sky80,
    tertiary = Amber80
)

/** Static light color scheme used when dynamic colors are disabled. */
private val LightColorScheme = lightColorScheme(
    primary = Forest40,
    secondary = Sky40,
    tertiary = Amber40

    /* Other default colors to override
    background = Color(0xFFFFFBFE),
    surface = Color(0xFFFFFBFE),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = Color(0xFF1C1B1F),
    onSurface = Color(0xFF1C1B1F),
    */
)

/**
 * Provides the Shahbaz Material theme to [content].
 *
 * @param darkTheme whether the dark palette should be selected.
 * @param dynamicColor whether Android system-derived dynamic colors should replace static tokens.
 * @param content composable subtree that receives the selected color scheme and [Typography].
 */
@Composable
fun ShahbazTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
