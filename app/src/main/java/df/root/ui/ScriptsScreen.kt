package df.root.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontFamily
import df.root.R
import df.root.kmi.KmiInfo
import df.root.kmi.KmiSelection
import df.root.script.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.preference.*
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun ScriptsScreen(
    kernelInfo: KmiInfo,
    kernelSelection: KmiSelection,
    onKernelSelectionChange: (KmiSelection) -> Unit,
    contentPadding: PaddingValues = PaddingValues(),
    modifier: Modifier = Modifier,
    active: Boolean = true,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val execution by ModuleEngine.state.collectAsState()
    var plan by remember { mutableStateOf(ModulePlan()) }
    var managers by remember { mutableStateOf<List<ManagerApp>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var launching by remember { mutableStateOf(false) }
    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberPageBackdrop()
    var showProviders by remember { mutableStateOf(false) }
    var warnRerun by remember { mutableStateOf(false) }
    val overviewScroll = rememberLazyListState()
    var editingId by remember { mutableStateOf<String?>(null) }
    var sheetVisible by remember { mutableStateOf(false) }
    val drafts = remember { mutableStateMapOf<String, ModuleStep>() }
    val dirtyIds = drafts.keys.filterTo(mutableSetOf()) { id -> drafts[id] != plan.steps.firstOrNull { it.id == id } }
    var output by remember { mutableStateOf<String?>(null) }
    var lastLog by remember { mutableStateOf<String?>(null) }
    var viewingLast by remember { mutableStateOf(false) }
    val labels = stringArrayResource(R.array.ui_modules_kinds).toList()
    val descriptions = stringArrayResource(R.array.ui_modules_descriptions).toList()
    val errorLabel = stringResource(R.string.ui_modules_error)
    val exitLabel = stringResource(R.string.ui_modules_exit)
    val idle = active && loaded && !saving && !launching && !execution.running
    suspend fun reload() {
        loaded = false
        plan = withContext(Dispatchers.IO) { ModuleStore.read(context) }
        loaded = true
    }
    fun report(failure: Exception) {
        if (failure is CancellationException) throw failure
        output = "$errorLabel: ${failure.message ?: failure.javaClass.simpleName}"
    }
    fun runSavedPlan() {
        launching = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) { ModuleStore.save(context, plan) }
                val result = ModuleEngine.run(context.applicationContext)
                if (ModuleEngine.state.value.issues.isEmpty())
                    output = "$exitLabel: ${result.exit}\n${result.output}"
            } catch (failure: Exception) { report(failure) }
            finally {
                try { reload() } catch (failure: Exception) { report(failure) }
                launching = false
            }
        }
    }
    fun persist(next: ModulePlan, openId: String? = null) {
        if (!idle) return
        val changedIds = next.steps.filter { step -> plan.steps.firstOrNull { it.id == step.id } != step }.mapTo(mutableSetOf()) { it.id }
        saving = true
        scope.launch {
            try {
                val saved = withContext(Dispatchers.IO) {
                    ModuleStore.save(context, next.copy(lastFailure = if (changedIds.isNotEmpty()) "" else next.lastFailure))
                    ModuleStore.read(context)
                }
                plan = saved
                if (openId != null) {
                    editingId = openId
                    sheetVisible = true
                }
                ModuleEngine.dismissIssues()
                output = null
                saved.steps.forEach { step -> if (step.id in changedIds || drafts[step.id] == step) drafts.remove(step.id) }
                drafts.keys.toList().filter { id -> saved.steps.none { it.id == id } }.forEach(drafts::remove)
            } catch (failure: Exception) {
                report(failure)
                try { reload() } catch (reloadFailure: Exception) { report(reloadFailure) }
            }
            finally { saving = false }
        }
    }
    LaunchedEffect(execution.running) { if (execution.running) viewingLast = false }
    LaunchedEffect(active, execution.running) {
        loaded = false
        if (!active) return@LaunchedEffect
        try {
            reload()
            managers = withContext(Dispatchers.IO) { ManagerCatalog.scan(context) }
        } catch (failure: Exception) { report(failure) }
    }
    Scaffold(modifier = modifier.fillMaxSize(), contentWindowInsets = WindowInsets(0, 0, 0, 0), topBar = {
        PageTopBar(title = stringResource(R.string.ui_modules_title), backdrop = backdrop,
            scrollBehavior = scrollBehavior, actions = {
                IconButton(enabled = idle && dirtyIds.isEmpty(), onClick = { showProviders = true }) {
                    Icon(MiuixIcons.Add, contentDescription = stringResource(R.string.ui_modules_add))
                }
            })
    }) { inner ->
        LazyColumn(state = overviewScroll, modifier = Modifier.fillMaxSize().pageNestedScroll(scrollBehavior)
            .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier), contentPadding = PaddingValues(start = 12.dp, end = 12.dp,
            top = inner.calculateTopPadding() + contentPadding.calculateTopPadding() + 12.dp,
            bottom = inner.calculateBottomPadding() + contentPadding.calculateBottomPadding() + 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(key = "kernel") {
                KernelConfiguration(kernelInfo = kernelInfo, kernelSelection = kernelSelection,
                    onKernelSelectionChange = onKernelSelectionChange,
                    editable = idle && dirtyIds.isEmpty(), active = active)
            }
            item(key = "automation") {
                Card {
                    SwitchPreference(title = stringResource(R.string.ui_modules_app), checked = plan.onAppOpen,
                        enabled = idle && dirtyIds.isEmpty(), onCheckedChange = { persist(plan.copy(onAppOpen = it)) })
                    SwitchPreference(title = stringResource(R.string.ui_modules_boot), checked = plan.onBoot,
                        enabled = idle && dirtyIds.isEmpty(), onCheckedChange = { persist(plan.copy(onBoot = it)) })
                    BasicComponent(title = stringResource(R.string.ui_modules_failure_policy),
                        summary = stringResource(R.string.ui_modules_failure_note), onClick = null)
                    if (plan.lastFailure.isNotBlank()) Text(plan.lastFailure, color = MiuixTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
                    if (plan.inFlight.isNotBlank() && !execution.running) Text(stringResource(R.string.ui_modules_interrupted, plan.inFlight), modifier = Modifier.padding(16.dp))
                }
            }
            item {
                SmallTitle(stringResource(R.string.ui_modules_order))
                Card {
                    ArrowPreference(title = stringResource(R.string.ui_modules_add),
                        enabled = idle && dirtyIds.isEmpty(), onClick = { showProviders = true })
                    TextButton(text = stringResource(R.string.ui_modules_legacy), enabled = idle && dirtyIds.isEmpty(),
                        onClick = { persist(ModuleDefaults.reset(plan, shellPresetTitles(labels))) }, modifier = Modifier.fillMaxWidth().padding(8.dp))
                }
                if (loaded && plan.steps.isEmpty()) Text(stringResource(R.string.ui_modules_empty), modifier = Modifier.padding(12.dp))
            }
            itemsIndexed(plan.steps, key = { _, step -> step.id }) { index, step ->
                Card {
                    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                        if (execution.currentId == step.id) Text(stringResource(R.string.ui_modules_current), color = MiuixTheme.colorScheme.primary, modifier = Modifier.padding(16.dp))
                        ArrowPreference(title = "${index + 1}. ${step.name.ifBlank { labels[step.kind.ordinal] }}", summary = descriptions[step.kind.ordinal],
                            onClick = { if (!saving) { output = null; editingId = step.id; sheetVisible = true } })
                        SwitchPreference(title = stringResource(R.string.ui_modules_enabled), checked = step.enabled, enabled = idle && dirtyIds.isEmpty(),
                            onCheckedChange = { value -> persist(plan.copy(steps = plan.steps.map { if (it.id == step.id) it.copy(enabled = value) else it })) })
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(text = stringResource(R.string.ui_modules_up), enabled = idle && dirtyIds.isEmpty() && index > 0,
                                onClick = { val steps = plan.steps.toMutableList(); steps[index] = steps[index - 1].also { steps[index - 1] = step }; persist(plan.copy(steps = steps)) }, modifier = Modifier.weight(1f))
                            TextButton(text = stringResource(R.string.ui_modules_down), enabled = idle && dirtyIds.isEmpty() && index < plan.steps.lastIndex,
                                onClick = { val steps = plan.steps.toMutableList(); steps[index] = steps[index + 1].also { steps[index + 1] = step }; persist(plan.copy(steps = steps)) }, modifier = Modifier.weight(1f))
                            TextButton(text = stringResource(R.string.ui_modules_remove), enabled = idle && dirtyIds.isEmpty(),
                                onClick = { persist(plan.copy(steps = plan.steps.filterNot { it.id == step.id })) }, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
            item {
                Card {
                    if (dirtyIds.isNotEmpty()) Text(stringResource(R.string.ui_modules_unsaved), modifier = Modifier.padding(16.dp))
                    TextButton(text = stringResource(R.string.ui_modules_run), enabled = idle && dirtyIds.isEmpty() && plan.steps.any { it.enabled }, colors = ButtonDefaults.textButtonColorsPrimary(), modifier = Modifier.fillMaxWidth().padding(8.dp), onClick = {
                        if (BootMarkers.thisBoot(context)) warnRerun = true
                        else runSavedPlan()
                    })
                }
            }
            item {
                val logKind = when {
                    execution.running -> R.string.ui_modules_log_running
                    viewingLast -> R.string.ui_modules_log_last
                    execution.output.isNotBlank() || output != null -> R.string.ui_modules_log_this
                    else -> R.string.ui_modules_output
                }
                val body = when {
                    execution.running -> execution.output
                    viewingLast -> lastLog.orEmpty()
                    output != null -> output.orEmpty()
                    execution.output.isNotBlank() -> execution.output
                    else -> ""
                }
                SmallTitle(stringResource(logKind))
                Card {
                    SelectionContainer {
                        Text(body.ifBlank { stringResource(R.string.ui_modules_no_output) },
                            fontFamily = FontFamily.Monospace, style = MiuixTheme.textStyles.footnote1,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp, max = 280.dp).verticalScroll(rememberScrollState()).padding(16.dp))
                    }
                    val latestPath = ModuleEngine.logFile(context).absolutePath
                    val logPath = if (execution.running && execution.logPath.isNotBlank()) execution.logPath else latestPath
                    Text(logPath, fontFamily = FontFamily.Monospace, style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(horizontal = 16.dp))
                    TextButton(text = stringResource(R.string.ui_modules_log), enabled = !execution.running, modifier = Modifier.fillMaxWidth().padding(8.dp), onClick = {
                        scope.launch {
                            try {
                                val text = withContext(Dispatchers.IO) {
                                    ModuleEngine.logFile(context).takeIf { it.isFile }?.readText()?.takeLast(32768)
                                }
                                lastLog = text ?: context.getString(R.string.ui_modules_log_missing)
                                viewingLast = true
                            } catch (failure: Exception) { report(failure) }
                        }
                    })
                }
            }
        }
    }
    RerunWarningDialog(show = warnRerun, onDismiss = { warnRerun = false }, onConfirm = {
        warnRerun = false
        runSavedPlan()
    })
    OverlayBottomSheet(show = active && showProviders,
        title = stringResource(R.string.ui_modules_choose),
        onDismissRequest = { showProviders = false }) {
        LazyColumn(Modifier.heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.65f).dp),
            contentPadding = PaddingValues(bottom = 16.dp)) {
            items(ModuleDefaults.providers.size) { index ->
                val kind = ModuleDefaults.providers[index]
                ArrowPreference(title = labels[kind.ordinal], summary = descriptions[kind.ordinal],
                    enabled = idle && dirtyIds.isEmpty(), onClick = {
                        val step = ModuleDefaults.step(kind, labels[kind.ordinal])
                        showProviders = false
                        persist(plan.copy(steps = plan.steps + step), openId = step.id)
                    })
            }
        }
    }
    val editedStep = plan.steps.firstOrNull { it.id == editingId }
    if (editedStep != null) {
        OverlayBottomSheet(
            show = active && sheetVisible,
            title = editedStep.name.ifBlank { labels[editedStep.kind.ordinal] },
            allowDismiss = !saving,
            onDismissRequest = { if (!saving) sheetVisible = false },
        ) {
            key(editedStep.id) {
                ModuleEditor(editedStep, managers, drafts, visible = active && sheetVisible,
                    enabled = idle, error = output,
                    onClose = { if (!saving) sheetVisible = false }, closeEnabled = !saving,
                    onError = { output = it },
                    onSave = { changed -> persist(plan.copy(steps = plan.steps.map {
                        if (it.id == editedStep.id) changed else it
                    })) })
            }
        }
    }
}

@Composable
private fun ModuleEditor(step: ModuleStep, managers: List<ManagerApp>, drafts: androidx.compose.runtime.snapshots.SnapshotStateMap<String, ModuleStep>, visible: Boolean, enabled: Boolean,
    error: String?, onClose: () -> Unit, closeEnabled: Boolean, onError: (String) -> Unit, onSave: (ModuleStep) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var draft by remember(step, drafts) {
        object : MutableState<ModuleStep> {
            override var value: ModuleStep
                get() = drafts[step.id] ?: step
                set(value) { if (value == step) drafts.remove(step.id) else drafts[step.id] = value }
            override fun component1() = value
            override fun component2(): (ModuleStep) -> Unit = { value = it }
        }
    }
    var importing by remember { mutableStateOf(false) }
    var importGeneration by remember { mutableIntStateOf(0) }
    val currentVisible by rememberUpdatedState(visible)
    val errorLabel = stringResource(R.string.ui_modules_error)
    val invalidLabel = stringResource(R.string.ui_modules_invalid)
    val dirty = draft != step
    val invalid = draft.script.toByteArray(Charsets.UTF_8).size > 32768 || '\u0000' in draft.script || '\u0000' in draft.name

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && enabled && currentVisible) {
            val generation = importGeneration
            importing = true
            scope.launch {
                try {
                    val text = withContext(Dispatchers.IO) {
                        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readNBytes(32769) } ?: error("Cannot open document")
                        require(bytes.size <= 32768) { invalidLabel }
                        val decoded = Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                            .decode(java.nio.ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
                        require('\u0000' !in decoded) { invalidLabel }; decoded
                    }
                    if (currentVisible && generation == importGeneration) draft = draft.copy(script = text)
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    if (currentVisible && generation == importGeneration)
                        onError("$errorLabel: ${failure.message ?: failure.javaClass.simpleName}")
                } finally { importing = false }
            }
        }
    }
    val editable = enabled && !importing
    Column(Modifier.fillMaxWidth().height((LocalConfiguration.current.screenHeightDp * 0.7f).dp).imePadding().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SmallTitle(stringResource(R.string.ui_modules_configuration))
            Text(stringArrayResource(R.array.ui_modules_descriptions)[step.kind.ordinal],
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = 16.dp))
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    SwitchPreference(title = stringResource(R.string.ui_modules_enabled), checked = draft.enabled,
                        enabled = editable, onCheckedChange = { draft = draft.copy(enabled = it) })
                    TextField(value = draft.name, onValueChange = { draft = draft.copy(name = it) }, label = stringResource(R.string.ui_modules_name), singleLine = true, enabled = editable, modifier = Modifier.fillMaxWidth())
                    if (step.kind == ModuleKind.SHELL) {
                        TextField(value = draft.script, onValueChange = { draft = draft.copy(script = it) }, label = stringResource(R.string.ui_modules_script), minLines = 8, maxLines = 18, singleLine = false,
                            textStyle = MiuixTheme.textStyles.body2.copy(fontFamily = FontFamily.Monospace), enabled = editable, modifier = Modifier.fillMaxWidth())
                        TextButton(text = stringResource(R.string.ui_modules_import), enabled = editable,
                            onClick = { importer.launch(arrayOf("text/*", "application/x-sh", "application/x-shellscript")) })
                    }
                    if (step.kind == ModuleKind.MANAGER) {
                        val selectedApp = managers.firstOrNull { it.packageName == draft.managerPackage }
                        val missing = draft.managerPackage.isNotBlank() && selectedApp == null
                        // The backend is shown, not chosen: ksud and apd belong to the
                        // manager package that ships them, so the manager picker is the
                        // only selection the user makes.
                        val choices = listOf(stringResource(R.string.ui_modules_manager_choose)) +
                            managers.map { "${it.label} (${it.backend}) · ${it.packageName}" } +
                            if (missing) listOf(draft.managerPackage) else emptyList()
                        val selectedIndex = if (missing) choices.lastIndex else managers.indexOfFirst { it.packageName == draft.managerPackage } + 1
                        OverlayDropdownPreference(title = stringResource(R.string.ui_modules_manager), items = choices, selectedIndex = selectedIndex, enabled = editable,
                            onSelectedIndexChange = { index ->
                                val changed = if (index == 0) draft.copy(managerPackage = "", backend = "")
                                else managers.getOrNull(index - 1)?.let { app ->
                                    draft.copy(managerPackage = app.packageName, backend = app.backend)
                                } ?: return@OverlayDropdownPreference
                                onSave(changed)
                            })
                        if (managers.isEmpty()) Text(stringResource(R.string.ui_modules_no_managers), style = MiuixTheme.textStyles.body2)
                        SwitchPreference(title = stringResource(R.string.ui_modules_soft_reboot),
                            summary = stringResource(R.string.ui_modules_soft_reboot_note), checked = draft.softReboot,
                            enabled = editable, onCheckedChange = { draft = draft.copy(softReboot = it) })
                    }
                    if (invalid) Text(invalidLabel, color = MiuixTheme.colorScheme.error)
                    if (!error.isNullOrBlank()) Text(error, color = MiuixTheme.colorScheme.error, style = MiuixTheme.textStyles.body2)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(text = stringResource(R.string.ui_modules_requirements_dismiss), enabled = closeEnabled,
                onClick = onClose, modifier = Modifier.weight(1f))
            TextButton(text = stringResource(R.string.ui_modules_cancel), enabled = editable && dirty,
                onClick = { drafts.remove(step.id) }, modifier = Modifier.weight(1f))
            TextButton(text = stringResource(R.string.ui_modules_save), enabled = editable && dirty && !invalid, onClick = { onSave(draft) }, colors = ButtonDefaults.textButtonColorsPrimary(), modifier = Modifier.weight(1f))
        }
    }
}

private fun shellPresetTitles(labels: List<String>) = ModuleDefaults.shellPresetKinds.associateWith { kind ->
    labels.getOrElse(kind.ordinal) { ModuleDefaults.fallbackName(kind) }
}
