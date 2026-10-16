package df.root.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import df.root.kmi.KmiInfo

internal object PreviewCallbacks : MainCallbacks {
    override fun onCancelRunConfirmation() = Unit
    override fun onRunClick() = Unit
    override fun onConfirmRerun() = Unit
    override fun onSaveLog() = Unit
    override fun onAdvancedLogChange(enabled: Boolean) = Unit
    override fun onOpenGithub() = Unit
}

internal val PreviewKernelInfo = KmiInfo(
    "Samsung SM-S928B", "16", 36, "2026-09-01", "6.6.30-android15-8-samsung", "aarch64", "android15-6.6", "6.6-android15-8",
)

class HomePreviewStates : PreviewParameterProvider<MainUiState> {
    private val ready = MainUiState(versionName = "1.15")
    override val values = sequenceOf(
        MainUiState(),
        ready.copy(runArmed = true),
        ready.copy(running = true, hasRun = true, progress1 = 0.6f, statusLeft = "60%", statusRight = "Verification"),
        ready.copy(rooted = true, hasRun = true, progress1 = 1f, progress2 = 1f, statusLeft = "100%", statusRight = "Verified"),
        ready.copy(failedRun = true, hasRun = true, logText = "Execution failed\nRestart required"),
        ready.copy(hasRun = true, advancedLog = true, logText = (1..100).joinToString("\n") { "[$it] Long diagnostic output for preview layout and scrolling" }),
    )
}

@Preview(name = "Home light", showBackground = true, widthDp = 393, heightDp = 852)
@Preview(name = "Home dark", showBackground = true, widthDp = 393, heightDp = 852, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Home narrow, large text", widthDp = 320, heightDp = 640, fontScale = 1.5f)
@Composable
private fun HomePreview(@PreviewParameter(HomePreviewStates::class) state: MainUiState) {
    DfTheme {
        StatusScreen(state, PreviewCallbacks)
    }
}

@Preview(name = "Settings light", widthDp = 393, heightDp = 852)
@Preview(name = "Settings Chinese large text", widthDp = 320, heightDp = 640, fontScale = 1.5f, locale = "zh")
@Composable
private fun SettingsPreview() {
    DfTheme(colorMode = 4, keyColor = KeyColors[2].second) {
        SettingsScreen(
            state = MainUiState(versionName = "1.15"),
            callbacks = PreviewCallbacks,
            theme = DfThemeState(colorMode = 4, keyColor = 2),
            onThemeChange = {},
            kernelInfo = PreviewKernelInfo,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(),
        )
    }
}
