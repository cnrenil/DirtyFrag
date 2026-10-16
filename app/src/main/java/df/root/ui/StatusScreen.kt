package df.root.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import df.root.R
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun StatusScreen(
    state: MainUiState,
    callbacks: MainCallbacks,
    contentPadding: PaddingValues = PaddingValues(),
    modifier: Modifier = Modifier,
) {
    val scroll = MiuixScrollBehavior()
    val backdrop = rememberPageBackdrop()
    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            PageTopBar(title = stringResource(R.string.app_name),
                scrollBehavior = scroll, backdrop = backdrop)
        },
    ) { inner ->
        LazyColumn(
            modifier = Modifier.fillMaxSize()
                .pageNestedScroll(scroll)
                .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier),
            contentPadding = pageListPadding(inner, contentPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "status") { StatusCard(state) }
            item(key = "run") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.runArmed) Text(stringResource(R.string.home_confirm_hint),
                        style = MiuixTheme.textStyles.body2, modifier = Modifier.padding(horizontal = 8.dp),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    TextButton(
                        text = stringResource(if (state.running) R.string.running else if (state.runArmed) R.string.home_confirm_run else R.string.ui_modules_run_plan),
                        enabled = state.canRun,
                        onClick = callbacks::onRunClick,
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            item(key = "log") {
                SmallTitle(stringResource(R.string.home_log_group))
                LogCard(state, callbacks)
            }
        }
    }
}

@Composable
private fun StatusCard(state: MainUiState) {
    val label = when {
        state.running -> R.string.running
        state.failedRun -> R.string.status_failed
        state.rooted -> R.string.ui_modules_daemon_ready
        state.hasRun -> R.string.status_not_rooted
        else -> R.string.status_idle
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.status_label), style = MiuixTheme.textStyles.subtitle, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                Text(stringResource(label), style = MiuixTheme.textStyles.title2, fontWeight = FontWeight.Bold,
                    color = if (state.running || state.rooted) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface)
            }
            if (state.running || state.hasRun) {
                LinearProgressIndicator(progress = state.progress1, modifier = Modifier.fillMaxWidth())
                Text(listOf(state.statusLeft, state.statusRight).filter { it.isNotBlank() }.joinToString(" · "),
                    style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
    }
}

@Composable
private fun LogCard(state: MainUiState, callbacks: MainCallbacks) {
    val lines = remember(state.logText, state.advancedLog) {
        val all = state.logText.trimEnd().lines().filter { it.isNotEmpty() }
        if (state.advancedLog) all else all.takeLast(8)
    }
    val logState = rememberLazyListState()
    var followOutput by remember { mutableStateOf(true) }
    LaunchedEffect(logState) {
        androidx.compose.runtime.snapshotFlow { logState.isScrollInProgress to logState.canScrollForward }
            .collect { (scrolling, canScroll) -> if (scrolling) followOutput = !canScroll }
    }
    LaunchedEffect(state.logText, state.advancedLog) {
        if (followOutput && lines.isNotEmpty()) logState.scrollToItem(lines.lastIndex)
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (lines.isEmpty()) {
                Text(stringResource(R.string.home_log_empty), style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            } else {
                SelectionContainer {
                    LazyColumn(state = logState, modifier = Modifier.fillMaxWidth().heightIn(max = if (state.advancedLog) 360.dp else 220.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(lines.size) { index ->
                            Text(lines[index], fontFamily = FontFamily.Monospace, style = MiuixTheme.textStyles.footnote1)
                        }
                    }
                }
            }
            TextButton(
                text = stringResource(R.string.save_log),
                onClick = callbacks::onSaveLog,
                enabled = state.logText.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
