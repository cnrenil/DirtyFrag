package df.root.term

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import df.root.R
import df.root.shell.Privilege
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Copy
import top.yukonga.miuix.kmp.icon.extended.Delete
import androidx.compose.ui.tooling.preview.Preview
import df.root.ui.DfTheme
import df.root.ui.PageTopBar
import df.root.ui.rememberPageBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun TerminalScreen(
    contentPadding: PaddingValues = PaddingValues(),
    active: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val model: TerminalViewModel = viewModel()
    TerminalContent(model, contentPadding, active, modifier)
}

@Composable
private fun TerminalContent(
    model: TerminalViewModel,
    contentPadding: PaddingValues = PaddingValues(),
    active: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val pickManagerLabel = stringResource(R.string.shortcut_pick_manager)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val inspection = LocalInspectionMode.current
    val backdrop = rememberPageBackdrop()
    val listState = rememberLazyListState()
    var followOutput by remember { mutableStateOf(true) }
    var shortcut by rememberSaveable { mutableStateOf(0) }
    val revision = model.revision
    // Copy mutable output lists so lazy layout sees a consistent published revision.
    val blocks = remember(revision) {
        model.buffer.blocks.map { if (it is Block.Output) it.copy(lines = it.lines.toMutableList()) else it }
    }

    LaunchedEffect(lifecycle, active, inspection, model) {
        if (active && !inspection) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { model.pollForeground() }
        }
    }
    // Record user intent only during an actual scroll, not during layout after append.
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to listState.canScrollForward }
            .collect { (scrolling, canScroll) -> if (scrolling) followOutput = !canScroll }
    }
    LaunchedEffect(revision) {
        if (followOutput && blocks.isNotEmpty()) {
            // A block can be taller than the viewport; align its bottom as well.
            listState.scrollToItem(blocks.lastIndex)
            val layout = listState.layoutInfo
            val last = layout.visibleItemsInfo.lastOrNull()
            if (last != null) {
                val remaining = (last.offset + last.size - layout.viewportEndOffset + layout.afterContentPadding)
                    .coerceAtLeast(0)
                listState.scrollBy(remaining.toFloat())
            }
        }
    }

    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    val copyLabel = stringResource(R.string.terminal_copy_all)
    val clearLabel = stringResource(R.string.terminal_clear)
    val privilege = when (model.privilege) {
        Privilege.App -> stringResource(R.string.terminal_app_privilege)
        else -> model.privilege.label
    }
    val status = if (model.busy) stringResource(R.string.terminal_running_status, privilege) else privilege

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            PageTopBar(
                backdrop = backdrop, compact = true,
                title = stringResource(R.string.terminal_title),
                subtitle = status,
                scrollBehavior = null,
                actions = {
                    IconButton(
                        onClick = { clipboard?.setPrimaryClip(ClipData.newPlainText(copyLabel, model.buffer.asPlainText())) },
                        modifier = Modifier.semantics { contentDescription = copyLabel },
                    ) { Icon(MiuixIcons.Copy, contentDescription = copyLabel) }
                    IconButton(
                        onClick = model::clear,
                        modifier = Modifier.semantics { contentDescription = clearLabel },
                    ) { Icon(MiuixIcons.Delete, contentDescription = clearLabel) }
                },
            )
        },
    ) { innerPadding ->
        Column(
            Modifier.fillMaxSize()
                .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
                .padding(PaddingValues(start = 12.dp, end = 12.dp,
                    top = innerPadding.calculateTopPadding() + contentPadding.calculateTopPadding() + 8.dp,
                    bottom = innerPadding.calculateBottomPadding() + contentPadding.calculateBottomPadding() + 8.dp)),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Card(Modifier.weight(1f).fillMaxWidth()) {
                SelectionContainer {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(blocks) { block ->
                            Column(Modifier.horizontalScroll(rememberScrollState())) { BlockView(block) }
                        }
                    }
                }
            }
            AuxiliaryKeys(
                enabled = !model.busy,
                onKey = model::appendInput,
                onPaste = { clipboard?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()?.let(model::appendInput) },
                onPrevious = model::previousCommand,
                onNext = model::nextCommand,
            )
            Card {
                val labels = listOf(
                    stringResource(R.string.terminal_choose_shortcut), "daemon", "env", "id", "markers",
                    stringResource(R.string.terminal_modules), stringResource(R.string.terminal_logs), "dmesg", "selinux",
                    stringResource(R.string.shortcut_clean_markers), stringResource(R.string.shortcut_unload_module),
                    stringResource(R.string.shortcut_cleanup),
                )
                val commands = listOf("", "!status", "!env", "id", "ls -la /data/user_de/0/df.root/markers/",
                    "cat /proc/modules | grep dfroot", "!__log", "dmesg | grep -i dfroot | tail -40", "getenforce")
                OverlayDropdownPreference(
                    title = stringResource(R.string.terminal_shortcuts),
                    items = labels,
                    selectedIndex = shortcut,
                    enabled = !model.busy,
                    onSelectedIndexChange = { index ->
                        shortcut = index
                        if (index < commands.size) {
                            commands.getOrNull(index)?.takeIf { it.isNotEmpty() }?.let(model::updateInput)
                        } else {
                            try {
                                val id = listOf("clean_markers", "unload_module", "cleanup")[index - commands.size]
                                model.updateInput(df.root.script.ScriptPresets.command(context, id).replace("\n", "; "))
                            } catch (failure: Exception) {
                                android.widget.Toast.makeText(context, pickManagerLabel, android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextField(
                    value = model.input,
                    onValueChange = model::updateInput,
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.terminal_hint),
                    useLabelAsPlaceholder = true,
                    enabled = !model.busy,
                    singleLine = true,
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { if (active && !inspection) model.submit() }),
                )
                Button(
                    onClick = { if (active && !inspection) model.submit() },
                    enabled = !model.busy && model.input.isNotBlank(),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) { Text(stringResource(R.string.terminal_run)) }
            }
        }
    }
}

@Composable
private fun AuxiliaryKeys(
    enabled: Boolean,
    onKey: (String) -> Unit,
    onPaste: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    val keys = listOf("Ctrl-C" to "\u0003", "Tab" to "\t", "Enter" to "\n", "Esc" to "\u001B",
        "↑" to "\u001B[A", "↓" to "\u001B[B", "←" to "\u001B[D", "→" to "\u001B[C")
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        AuxiliaryButton(stringResource(R.string.terminal_paste), enabled, onPaste)
        AuxiliaryButton(stringResource(R.string.terminal_history_previous), enabled, onPrevious)
        AuxiliaryButton(stringResource(R.string.terminal_history_next), enabled, onNext)
        keys.forEach { (label, sequence) -> AuxiliaryButton(label, enabled) { onKey(sequence) } }
    }
}

@Composable
private fun AuxiliaryButton(label: String, enabled: Boolean, action: () -> Unit) {
    Button(onClick = action, enabled = enabled, insideMargin = PaddingValues(horizontal = 10.dp, vertical = 8.dp)) {
        Text(label, fontSize = 12.sp)
    }
}

@Composable
private fun BlockView(block: Block) {
    val colors = MiuixTheme.colorScheme
    when (block) {
        is Block.Command -> Text("$ ${block.text}", color = colors.primary,
            fontFamily = FontFamily.Monospace, fontSize = 13.sp, softWrap = false)
        is Block.Output -> Column {
            block.lines.forEach { line ->
                // Ansi's implicit foreground was written for a dark terminal. Adapt
                // that neutral fallback to the themed card while retaining SGR spans.
                val themed = AnnotatedString(
                    text = line.text.ifEmpty { " " },
                    spanStyles = line.spanStyles.map { span ->
                        if (span.item.color == Color(0xFFE6E6E6) && span.item.background == Color.Unspecified)
                            AnnotatedString.Range(span.item.copy(color = colors.onSurface), span.start, span.end, span.tag)
                        else span
                    },
                    paragraphStyles = line.paragraphStyles,
                )
                Text(themed, color = colors.onSurface, fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp, softWrap = false)
            }
        }
        is Block.Notice -> Text(block.text,
            color = if (block.isError) colors.error else colors.onSurfaceVariantSummary,
            fontFamily = FontFamily.Monospace, fontSize = 13.sp, softWrap = false)
    }
}

@Preview(name = "Terminal light", widthDp = 393, heightDp = 760, showBackground = true)
@Composable
private fun TerminalLightPreview() { TerminalPreview(colorMode = 1) }

@Preview(name = "Terminal dark", widthDp = 393, heightDp = 760, showBackground = true)
@Composable
private fun TerminalDarkPreview() { TerminalPreview(colorMode = 2) }

@Preview(name = "Terminal narrow keyboard space", widthDp = 320, heightDp = 440, showBackground = true)
@Composable
private fun TerminalNarrowPreview() { TerminalPreview(colorMode = 1) }

@Composable
private fun TerminalPreview(colorMode: Int) {
    // A fixture Application needs no attached context: no model operation that
    // reads Android resources or starts a shell is invoked from the preview.
    val fixture = remember {
        TerminalViewModel(Application()).apply {
            buffer.notice("DirtyFrag terminal")
            buffer.prompt("id")
            buffer.output("uid=10234(app) gid=10234(app) groups=10234(app)\n" +
                "\u001B[32mdaemon status: ready\u001B[0m")
            updateInput("getenforce")
        }
    }
    DfTheme(colorMode = colorMode) { TerminalContent(model = fixture, active = false) }
}
