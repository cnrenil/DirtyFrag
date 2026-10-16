package df.root.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeColorSpec
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle

/**
 * Colour mode, mirroring the official MiuiX example app: 0 follows the system,
 * 1/2 force light/dark, and 3-5 use Monet with a user-chosen key colour.
 */
val LocalColorMode = compositionLocalOf { 0 }
val LocalChromeStyle = compositionLocalOf { DfThemeState() }

/** The key colours offered in Monet modes, from the example app's palette. */
val KeyColors: List<Pair<String, Color>> = listOf(
    "Blue" to Color(0xFF3482FF),
    "Green" to Color(0xFF36D167),
    "Purple" to Color(0xFF7C4DFF),
    "Yellow" to Color(0xFFFFB21D),
    "Orange" to Color(0xFFFF5722),
    "Pink" to Color(0xFFE91E63),
    "Teal" to Color(0xFF00BCD4),
)

/** Persisted theme choices, held by the Activity so they survive rotation. */
data class DfThemeState(
    val colorMode: Int = 0,
    val keyColor: Int = 0,
    val paletteStyle: Int = 0,
    val floatingNavigation: Boolean = false,
    val floatingNavigationStyle: Int = 0,
    val topBarStyle: Int = 0,
    val blurBars: Boolean = false,
) {
    fun sanitized() = copy(
        floatingNavigationStyle = floatingNavigationStyle.takeIf { it in 0..1 } ?: 0,
        topBarStyle = topBarStyle.takeIf { it in 0..2 } ?: 0,
        colorMode = colorMode.takeIf { it in 0..5 } ?: 0,
        keyColor = keyColor.takeIf { it in KeyColors.indices } ?: 0,
        paletteStyle = paletteStyle.takeIf { it in ThemePaletteStyle.entries.indices } ?: 0,
    )
}

@Composable
fun DfTheme(
    colorMode: Int = 0,
    keyColor: Color? = null,
    paletteStyle: Int = 0,
    colorSpec: Int = 0,
    content: @Composable () -> Unit,
) {
    val spec = ThemeColorSpec.entries.getOrNull(colorSpec) ?: ThemeColorSpec.Spec2021
    val style = ThemePaletteStyle.entries.getOrNull(paletteStyle) ?: ThemePaletteStyle.Content
    // Remembered on the inputs so switching modes rebuilds the controller
    // instead of mutating one in place.
    val controller = remember(colorMode, keyColor, spec, style) {
        when (colorMode) {
            1 -> ThemeController(ColorSchemeMode.Light)
            2 -> ThemeController(ColorSchemeMode.Dark)
            3 -> ThemeController(ColorSchemeMode.MonetSystem, keyColor = keyColor, colorSpec = spec, paletteStyle = style)
            4 -> ThemeController(ColorSchemeMode.MonetLight, keyColor = keyColor, colorSpec = spec, paletteStyle = style)
            5 -> ThemeController(ColorSchemeMode.MonetDark, keyColor = keyColor, colorSpec = spec, paletteStyle = style)
            else -> ThemeController(ColorSchemeMode.System)
        }
    }
    CompositionLocalProvider(LocalColorMode provides colorMode) {
        MiuixTheme(controller = controller, content = content)
    }
}

/** Whether the UI is currently dark, accounting for a forced mode. */
@Composable
fun isInDarkTheme(): Boolean = when (LocalColorMode.current) {
    1, 4 -> false
    2, 5 -> true
    else -> isSystemInDarkTheme()
}
