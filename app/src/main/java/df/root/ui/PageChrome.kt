package df.root.ui

import androidx.compose.foundation.layout.*
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import top.yukonga.miuix.kmp.basic.FloatingNavigationBar
import top.yukonga.miuix.kmp.basic.FloatingToolbarDefaults
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun rememberPageBackdrop(): LayerBackdrop? {
    val surface = MiuixTheme.colorScheme.surface
    val backdrop = rememberLayerBackdrop {
        drawRect(surface)
        drawContent()
    }
    return if (LocalChromeStyle.current.blurBars && !LocalInspectionMode.current && isRuntimeShaderSupported()) backdrop else null
}

@Composable
fun BlurredPageBar(backdrop: LayerBackdrop?, shape: Shape = RectangleShape, content: @Composable () -> Unit) {
    val modifier = if (backdrop != null) Modifier.textureBlur(
        backdrop = backdrop,
        shape = shape,
        blurRadius = 25f,
        colors = BlurDefaults.blurColors(
            blendColors = listOf(BlendColorEntry(MiuixTheme.colorScheme.surface.copy(alpha = 0.85f))),
        ),
    ) else Modifier
    Box(Modifier.fillMaxWidth().clip(shape).then(modifier)) { content() }
}

/** All pages share the selected header treatment; compact content can opt out of expansion. */
@Composable
fun PageTopBar(
    title: String,
    backdrop: LayerBackdrop?,
    subtitle: String = "",
    scrollBehavior: ScrollBehavior? = null,
    compact: Boolean = false,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
) {
    val style = LocalChromeStyle.current.topBarStyle
    val floating = style == 2
    val wrapper = if (floating) Modifier.statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp) else Modifier
    Box(wrapper) {
        BlurredPageBar(backdrop, if (floating) RoundedCornerShape(24.dp) else RectangleShape) {
            val color = if (backdrop != null) Color.Transparent else if (floating)
                MiuixTheme.colorScheme.surfaceContainer else MiuixTheme.colorScheme.surface
            if (style != 0 || compact) {
                SmallTopAppBar(title = title, subtitle = subtitle, color = color,
                    scrollBehavior = null, navigationIcon = navigationIcon, actions = actions,
                    defaultWindowInsetsPadding = !floating)
            } else {
                TopAppBar(title = title, subtitle = subtitle, color = color,
                    scrollBehavior = scrollBehavior, navigationIcon = navigationIcon, actions = actions)
            }
        }
    }
}

/** The native Miuix floating bar, with the same backdrop treatment as the example. */
@Composable
fun FloatingPageNavigation(backdrop: LayerBackdrop?, content: @Composable () -> Unit) {
    val blur = if (backdrop != null) Modifier.textureBlur(
        backdrop = backdrop,
        shape = RoundedCornerShape(FloatingToolbarDefaults.CornerRadius),
        blurRadius = 25f,
        colors = BlurDefaults.blurColors(blendColors = listOf(
            BlendColorEntry(MiuixTheme.colorScheme.surfaceContainer.copy(alpha = 0.85f)),
        )),
    ) else Modifier
    FloatingNavigationBar(modifier = blur,
        color = if (backdrop != null) Color.Transparent else MiuixTheme.colorScheme.surfaceContainer,
        content = content)
}

@Composable
fun Modifier.pageNestedScroll(scrollBehavior: ScrollBehavior): Modifier =
    if (LocalChromeStyle.current.topBarStyle == 0) this.nestedScroll(scrollBehavior.nestedScrollConnection) else this

fun pageListPadding(inner: PaddingValues, outer: PaddingValues) = PaddingValues(
    start = 12.dp,
    end = 12.dp,
    top = inner.calculateTopPadding() + outer.calculateTopPadding() + 12.dp,
    bottom = inner.calculateBottomPadding() + outer.calculateBottomPadding() + 12.dp,
)
