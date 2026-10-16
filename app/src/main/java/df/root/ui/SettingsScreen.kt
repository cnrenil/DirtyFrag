package df.root.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import df.root.R
import df.root.kmi.KmiInfo
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.*
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle

@Composable
fun SettingsScreen(
    state: MainUiState,
    callbacks: MainCallbacks,
    theme: DfThemeState,
    onThemeChange: (DfThemeState) -> Unit,
    kernelInfo: KmiInfo,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    onClose: () -> Unit = {},
) {
    val scrollBehavior = MiuixScrollBehavior()
    val listState = rememberLazyListState()
    val backdrop = rememberPageBackdrop()
    val unknown = stringResource(R.string.ui_settings_unknown)
    val colorModes = stringArrayResource(R.array.ui_settings_color_modes).toList()
    val topBarStyles = stringArrayResource(R.array.ui_settings_top_bar_styles).toList()
    val keyColors = stringArrayResource(R.array.ui_settings_key_colors).toList()
    val paletteLabels = stringArrayResource(R.array.ui_settings_palette_styles)
    val paletteItems = ThemePaletteStyle.entries.map { paletteLabels.getOrNull(it.ordinal) ?: it.name }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            PageTopBar(title = stringResource(R.string.ui_settings_title), backdrop = backdrop,
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(MiuixIcons.Back, contentDescription = stringResource(R.string.ui_modules_back))
                    }
                })
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = (if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
                .fillMaxSize().pageNestedScroll(scrollBehavior),
            state = listState,
            contentPadding = pageListPadding(innerPadding, contentPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "appearance") {
                SmallTitle(stringResource(R.string.ui_settings_appearance))
                Card {
                    OverlayDropdownPreference(
                        title = stringResource(R.string.ui_settings_color_mode), items = colorModes,
                        selectedIndex = theme.colorMode.coerceIn(colorModes.indices),
                        onSelectedIndexChange = { onThemeChange(theme.copy(colorMode = it)) },
                    )
                    if (theme.colorMode in 3..5) {
                        OverlayDropdownPreference(
                            title = stringResource(R.string.ui_settings_key_color), items = keyColors,
                            selectedIndex = theme.keyColor.coerceIn(keyColors.indices),
                            onSelectedIndexChange = { onThemeChange(theme.copy(keyColor = it)) },
                        )
                        OverlayDropdownPreference(
                            title = stringResource(R.string.ui_settings_palette), items = paletteItems,
                            selectedIndex = theme.paletteStyle.coerceIn(paletteItems.indices),
                            onSelectedIndexChange = { onThemeChange(theme.copy(paletteStyle = it)) },
                        )
                    }
                }
            }
            item(key = "chrome") {
                SmallTitle(stringResource(R.string.ui_settings_navigation))
                Card {
                    SwitchPreference(title = stringResource(R.string.ui_settings_floating_navigation),
                        summary = stringResource(R.string.ui_settings_floating_navigation_note),
                        checked = theme.floatingNavigation,
                        onCheckedChange = { onThemeChange(theme.copy(floatingNavigation = it)) })
                    if (theme.floatingNavigation) {
                        OverlayDropdownPreference(title = stringResource(R.string.ui_settings_floating_navigation_style),
                            items = stringArrayResource(R.array.ui_settings_floating_navigation_styles).toList(),
                            selectedIndex = theme.floatingNavigationStyle,
                            onSelectedIndexChange = { onThemeChange(theme.copy(floatingNavigationStyle = it)) })
                    }
                    OverlayDropdownPreference(title = stringResource(R.string.ui_settings_top_bar),
                        items = topBarStyles, selectedIndex = theme.topBarStyle,
                        onSelectedIndexChange = { onThemeChange(theme.copy(topBarStyle = it)) })
                    SwitchPreference(title = stringResource(R.string.ui_settings_bar_blur),
                        summary = stringResource(R.string.ui_settings_bar_blur_note), checked = theme.blurBars,
                        onCheckedChange = { onThemeChange(theme.copy(blurBars = it)) })
                }
            }
            item(key = "logs") {
                SmallTitle(stringResource(R.string.ui_settings_logs))
                Card {
                    SwitchPreference(
                        title = stringResource(R.string.ui_settings_advanced_log), summary = stringResource(R.string.ui_settings_advanced_log_summary),
                        checked = state.advancedLog, onCheckedChange = callbacks::onAdvancedLogChange,
                    )
                }
            }
            item(key = "device") {
                SmallTitle(stringResource(R.string.ui_settings_device))
                Card {
                    InfoRow(stringResource(R.string.ui_settings_model), kernelInfo.manufacturerModel)
                    InfoRow(stringResource(R.string.ui_settings_android), stringResource(R.string.ui_settings_android_value, kernelInfo.androidRelease, kernelInfo.apiLevel))
                    InfoRow(stringResource(R.string.ui_settings_patch), kernelInfo.securityPatch.ifBlank { unknown })
                    InfoRow(stringResource(R.string.ui_settings_release), kernelInfo.release ?: unknown)
                    InfoRow(stringResource(R.string.ui_settings_arch), kernelInfo.machine ?: unknown)
                    InfoRow(stringResource(R.string.ui_settings_detected), kernelInfo.fullKmi ?: kernelInfo.detectedBranch ?: unknown)
                }
            }
            item(key = "about") {
                SmallTitle(stringResource(R.string.ui_settings_about))
                Card {
                    InfoRow(stringResource(R.string.ui_settings_version), state.versionName)
                    ArrowPreference(title = stringResource(R.string.ui_settings_updates),
                        summary = stringResource(if (state.updateAvailable) R.string.ui_settings_update_available else R.string.ui_settings_update_summary), onClick = callbacks::onOpenGithub)
                    ArrowPreference(title = stringResource(R.string.ui_settings_github), summary = stringResource(R.string.ui_settings_github_summary), onClick = callbacks::onOpenGithub)
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    BasicComponent(title = label, summary = value, onClick = null)
}
