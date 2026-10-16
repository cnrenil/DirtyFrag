package df.root.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import df.root.R
import df.root.kmi.Kmi
import df.root.script.ModuleEngine
import df.root.term.TerminalScreen
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.Layers
import top.yukonga.miuix.kmp.icon.extended.HorizontalSplit
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PagerNavigationSpringSpec
import top.yukonga.miuix.kmp.utils.springAnimateToPage

/** Four bottom-bar destinations share one swipe pager and preserve their state. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DfShell(
    state: MainUiState,
    callbacks: MainCallbacks,
    theme: DfThemeState,
    onThemeChange: (DfThemeState) -> Unit,
    overlayContent: @Composable () -> Unit = {},
) {
    val context = LocalContext.current
    val info = remember { Kmi.readInfo() }
    var selection by remember { mutableStateOf(Kmi.readSelection(context)) }
    // All bottom-bar destinations participate in the same swipe navigation.
    val pager = rememberPagerState(pageCount = { 4 })
    var selectedPage by remember { mutableIntStateOf(pager.currentPage) }
    var navigationJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val backdrop = rememberPageBackdrop()
    val labels = listOf(stringResource(R.string.nav_home), stringResource(R.string.ui_modules_title),
        stringResource(R.string.tab_terminal), stringResource(R.string.tab_settings))
    val icons = listOf(MiuixIcons.Home, MiuixIcons.Layers, MiuixIcons.HorizontalSplit, MiuixIcons.Settings)
    val execution by ModuleEngine.state.collectAsState()
    val runningLabel = stringResource(R.string.ui_modules_current)
    val imeVisible = WindowInsets.isImeVisible

    // While a tab click is animating the pager, cancelling the previous spring
    // settles on an intermediate page. That must not overwrite the tab we just
    // selected, or the bar flashes target → old → target.
    var navigationTarget by remember { mutableIntStateOf(-1) }

    fun navigate(page: Int) {
        if (page != 0) callbacks.onCancelRunConfirmation()
        selectedPage = page
        navigationTarget = page
        navigationJob?.cancel()
        navigationJob = scope.launch {
            try {
                pager.springAnimateToPage(page)
            } finally {
                if (navigationTarget == page) navigationTarget = -1
            }
        }
    }

    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.collect { page ->
            if (navigationTarget >= 0 && page != navigationTarget) return@collect
            if (selectedPage != page) {
                selectedPage = page
                if (page != 0) callbacks.onCancelRunConfirmation()
            }
        }
    }

    BackHandler(enabled = selectedPage != 0) { navigate(0) }

    Scaffold(
        modifier = Modifier.fillMaxSize().imePadding(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            AnimatedVisibility(visible = !imeVisible,
                enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                if (theme.floatingNavigation) {
                    if (theme.floatingNavigationStyle == 1) {
                        IosLiquidGlassNavigation(
                            items = labels.mapIndexed { index, label -> IosNavigationItem(icons[index], label) },
                            selectedIndex = selectedPage,
                            onItemClick = ::navigate,
                            backdrop = backdrop,
                            blurActive = theme.blurBars,
                            badge = { index ->
                                if (index == 1 && execution.running) {
                                    { Badge(containerColor = MiuixTheme.colorScheme.primary) }
                                } else null
                            },
                        )
                    } else {
                        FloatingPageNavigation(backdrop) {
                            labels.forEachIndexed { index, label ->
                                FloatingNavigationBarItem(selected = selectedPage == index,
                                    onClick = { navigate(index) },
                                    icon = icons[index], label = label, badge = {
                                        if (index == 1 && execution.running) Badge(
                                            modifier = Modifier.semantics { contentDescription = runningLabel },
                                            containerColor = MiuixTheme.colorScheme.primary)
                                    })
                            }
                        }
                    }
                } else {
                    BlurredPageBar(backdrop) {
                        NavigationBar(color = if (backdrop != null) Color.Transparent else MiuixTheme.colorScheme.surface) {
                            labels.forEachIndexed { index, label ->
                                NavigationBarItem(selected = selectedPage == index,
                                    onClick = { navigate(index) }, icon = icons[index], label = label, badge = {
                                    if (index == 1 && execution.running) Badge(
                                        modifier = Modifier.semantics { contentDescription = runningLabel },
                                        containerColor = MiuixTheme.colorScheme.primary)
                                })
                            }
                        }
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)) {
            HorizontalPager(
                state = pager,
                modifier = Modifier.fillMaxSize(),
                userScrollEnabled = true,
                // Keep module editor drafts and terminal state composed across every tab switch.
                beyondViewportPageCount = 3,
                flingBehavior = PagerDefaults.flingBehavior(state = pager, snapAnimationSpec = PagerNavigationSpringSpec),
            ) { page ->
                when (page) {
                    0 -> StatusScreen(state = state, callbacks = callbacks, contentPadding = padding)
                    1 -> ScriptsScreen(kernelInfo = info, kernelSelection = selection,
                        onKernelSelectionChange = { callbacks.onCancelRunConfirmation(); selection = it; ModuleEngine.dismissIssues() },
                        contentPadding = padding, active = selectedPage == 1)
                    2 -> TerminalScreen(contentPadding = padding, active = selectedPage == 2)
                    else -> SettingsScreen(state = state, callbacks = callbacks, theme = theme,
                        onThemeChange = onThemeChange, kernelInfo = info, contentPadding = padding,
                        onClose = { navigate(0) })
                }
            }
        }
        overlayContent()
        ModuleRequirementsDialog()
    }
}

/**
 * The blocking precondition warning.
 *
 * Shown whenever a run was refused *or* whenever the user asked to run and the
 * plan cannot be satisfied. It lists every unmet condition with the step that
 * caused it, because "the run did nothing" is otherwise indistinguishable from
 * a permission problem.
 */
@Composable
private fun ModuleRequirementsDialog() {
    val execution by ModuleEngine.state.collectAsState()
    val kindLabels = stringArrayResource(R.array.ui_modules_kinds)
    OverlayDialog(show = execution.issues.isNotEmpty(),
        title = stringResource(R.string.ui_modules_requirements_title),
        summary = stringResource(R.string.ui_modules_requirements_summary),
        onDismissRequest = ModuleEngine::dismissIssues) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(execution.issues) { issue ->
                    val kind = kindLabels.getOrElse(issue.kind.ordinal) { issue.kind.name }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.ui_modules_requirement_step, issue.position,
                            issue.name.ifBlank { kind }, kind), style = MiuixTheme.textStyles.subtitle)
                        Text(issue.reason, style = MiuixTheme.textStyles.body2)
                    }
                }
            }
            TextButton(text = stringResource(R.string.ui_modules_requirements_dismiss),
                onClick = ModuleEngine::dismissIssues, modifier = Modifier.fillMaxWidth())
        }
    }
}
