package df.root.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import df.root.KmiPrefs
import df.root.R
import df.root.kmi.Kmi
import df.root.kmi.KmiInfo
import df.root.kmi.KmiSelection
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.*

/** Defaults used by the module plan; keeps the existing device-protected preferences. */
@Composable
fun KernelConfiguration(
    kernelInfo: KmiInfo,
    kernelSelection: KmiSelection,
    onKernelSelectionChange: (KmiSelection) -> Unit,
    editable: Boolean,
    active: Boolean,
) {
    val context = LocalContext.current
    var showCustom by rememberSaveable { mutableStateOf(false) }
    var customDraft by rememberSaveable { mutableStateOf("") }
    val customValid = customDraft.isNotBlank() && customDraft.none { it == '\n' || it == '\r' || it == '\u0000' }
    val branches = stringArrayResource(R.array.kmi_branches).toList()
    val unknown = stringResource(R.string.ui_settings_unknown)
    val automatic = stringResource(R.string.ui_settings_kernel_auto, kernelInfo.detectedBranch ?: unknown)
    val branchItems = buildList {
        add(automatic)
        addAll(branches)
        kernelSelection.branch?.let { if (it !in branches) add(it) }
    }
    val modeLabels = stringArrayResource(R.array.ui_settings_vermagic_modes).toList()
    fun openCustom() {
        customDraft = kernelSelection.vermagicCustom.ifBlank { Kmi.vivoVermagic(kernelInfo.release, kernelInfo.machine) }
        showCustom = true
    }
    Column {
        SmallTitle(stringResource(R.string.ui_settings_kernel))
        Card {
            OverlayDropdownPreference(
                title = stringResource(R.string.ui_settings_branch), items = branchItems,
                selectedIndex = kernelSelection.branch?.let { branchItems.indexOf(it) }?.coerceAtLeast(0) ?: 0,
                enabled = editable,
                onSelectedIndexChange = { index ->
                    val branch = if (index == 0) null else branchItems.getOrNull(index)
                    Kmi.setBranch(context, branch)
                    onKernelSelectionChange(kernelSelection.copy(branch = branch))
                },
            )
            OverlayDropdownPreference(
                title = stringResource(R.string.ui_settings_vermagic), items = modeLabels,
                selectedIndex = VERMAGIC_MODES.indexOf(kernelSelection.vermagicMode).coerceAtLeast(0), enabled = editable,
                onSelectedIndexChange = { index ->
                    val mode = VERMAGIC_MODES.getOrNull(index) ?: KmiPrefs.MODE_BUILTIN
                    if (mode == KmiPrefs.MODE_CUSTOM) openCustom() else {
                        Kmi.setVermagic(context, mode)
                        onKernelSelectionChange(kernelSelection.copy(vermagicMode = mode))
                    }
                },
            )
            if (kernelSelection.vermagicMode == KmiPrefs.MODE_CUSTOM) {
                ArrowPreference(title = stringResource(R.string.ui_settings_edit_custom),
                    summary = kernelSelection.vermagicCustom.ifBlank { unknown }, enabled = editable, onClick = { openCustom() })
            }
            BasicComponent(title = stringResource(R.string.ui_settings_resolved),
                summary = Kmi.resolvedVermagic(kernelInfo, kernelSelection).ifEmpty {
                    stringResource(R.string.ui_settings_builtin_summary)
                }, onClick = null)
        }
    }
    OverlayDialog(show = active && showCustom, title = stringResource(R.string.ui_settings_custom_title),
        summary = stringResource(R.string.ui_settings_custom_summary), onDismissRequest = { showCustom = false }) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextField(value = customDraft, onValueChange = { customDraft = it },
                label = stringResource(R.string.ui_settings_custom_hint), modifier = Modifier.fillMaxWidth())
            if (customDraft.isNotEmpty() && !customValid) Text(stringResource(R.string.ui_settings_custom_invalid))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(text = stringResource(R.string.ui_settings_cancel), modifier = Modifier.weight(1f), onClick = { showCustom = false })
                TextButton(text = stringResource(R.string.ui_settings_confirm), modifier = Modifier.weight(1f), enabled = customValid && editable,
                    onClick = {
                        Kmi.setVermagic(context, KmiPrefs.MODE_CUSTOM, customDraft)
                        onKernelSelectionChange(kernelSelection.copy(vermagicMode = KmiPrefs.MODE_CUSTOM, vermagicCustom = customDraft))
                        showCustom = false
                    })
            }
        }
    }
}

private val VERMAGIC_MODES = listOf(KmiPrefs.MODE_BUILTIN, KmiPrefs.MODE_VIVO, KmiPrefs.MODE_ANDROID, KmiPrefs.MODE_CUSTOM)
