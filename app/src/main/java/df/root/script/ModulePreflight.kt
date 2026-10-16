package df.root.script

import android.content.Context
import df.root.R
import df.root.shell.Privilege
import df.root.shell.Shell
import df.root.kmi.Kmi
import java.io.File

/** One unmet precondition, reported to the user before anything is executed. */
data class ModuleIssue(
    val stepId: String,
    val position: Int,
    val kind: ModuleKind,
    val name: String,
    val reason: String,
)

internal enum class RequirementReason {
    EMPTY_PLAN, EMPTY_SCRIPT, MANAGER_REQUIRED, MANAGER_MISSING, MANAGER_LIBRARY_MISSING,
    NATIVE_MISSING, ROOT_REQUIRED, REBOOT_MANAGER_REQUIRED, REBOOT_BINARY_MISSING,
    REBOOT_FINAL_REQUIRED, DAEMON_REQUIRED, KMI_UNRESOLVED,
}

internal data class RequirementIssue(val step: ModuleStep, val position: Int, val reason: RequirementReason)

internal data class RequirementEnvironment(
    val rootAvailable: Boolean,
    val nativeAvailable: Boolean,
    val managers: Set<String>,
    val rebootBinaries: Set<String> = emptySet(),
    val managerBackends: Map<String, String> = emptyMap(),
    val backendLibraryPresent: Boolean = true,
    val daemonAvailable: Boolean = false,
    val invalidKmiIds: Set<String> = emptySet(),
)

/**
 * Checks a plan before it runs.
 *
 * The plan is validated as a *sequence*, because requirements are produced by
 * earlier steps: a daemon bootstrap grants root to everything after it, and a
 * late-load step is what makes a soft reboot meaningful. Checking each step in
 * isolation would reject a perfectly good plan that loads the manager first.
 */
object ModulePreflight {
    internal fun inspect(plan: ModulePlan, env: RequirementEnvironment): List<RequirementIssue> {
        val issues = mutableListOf<RequirementIssue>()
        var root = env.rootAvailable
        var daemon = env.daemonAvailable
        val finalEnabled = plan.steps.indexOfLast { it.enabled }
        var backend: String? = null
        var managerWillLoad = false
        if (plan.steps.none { it.enabled })
            return listOf(RequirementIssue(ModuleStep(id = "plan", name = ""), 0, RequirementReason.EMPTY_PLAN))

        plan.steps.forEachIndexed { index, step ->
            if (!step.enabled) return@forEachIndexed
            fun issue(reason: RequirementReason) { issues += RequirementIssue(step, index + 1, reason) }
            when (step.kind) {
                ModuleKind.DAEMON -> {
                    if (step.id in env.invalidKmiIds) issue(RequirementReason.KMI_UNRESOLVED)
                    if (!env.nativeAvailable && !env.daemonAvailable) issue(RequirementReason.NATIVE_MISSING)
                    else { root = true; daemon = true }
                }
                ModuleKind.SHELL -> if (step.script.isBlank()) issue(RequirementReason.EMPTY_SCRIPT)
                ModuleKind.MANAGER -> {
                    if (step.managerPackage.isBlank()) issue(RequirementReason.MANAGER_REQUIRED)
                    else if (step.managerPackage !in env.managers) issue(RequirementReason.MANAGER_MISSING)
                    else if (!env.backendLibraryPresent) issue(RequirementReason.MANAGER_LIBRARY_MISSING)
                    else { backend = env.managerBackends[step.managerPackage]; managerWillLoad = true }
                    if (step.id in env.invalidKmiIds) issue(RequirementReason.KMI_UNRESOLVED)
                    if (!root) issue(RequirementReason.ROOT_REQUIRED)
                    if (step.softReboot) {
                        if (index != finalEnabled) issue(RequirementReason.REBOOT_FINAL_REQUIRED)
                        if (!daemon) issue(RequirementReason.DAEMON_REQUIRED)
                    }
                }
                ModuleKind.SOFT_REBOOT -> {
                    if (!root) issue(RequirementReason.ROOT_REQUIRED)
                    if (backend !in setOf("apd", "ksud")) issue(RequirementReason.REBOOT_MANAGER_REQUIRED)
                    else if (!managerWillLoad && backend !in env.rebootBinaries) issue(RequirementReason.REBOOT_BINARY_MISSING)
                }
                ModuleKind.CLEAN_MARKERS -> if (!root) issue(RequirementReason.ROOT_REQUIRED)
                else -> if (!root) issue(RequirementReason.ROOT_REQUIRED)
            }
        }
        return issues
    }

    /**
     * Runs [inspect] against the live device.
     *
     * Every probe is a real check - is the manager still installed, does its
     * native library still exist, does /data/adb still hold the reboot binary -
     * rather than trusting the saved plan, which may predate an uninstall.
     */
    suspend fun check(context: Context, plan: ModulePlan): List<ModuleIssue> {
        ModuleStore.validate(plan)
        val kmiSelection = Kmi.readSelection(context)
        val detectedBranch = Kmi.readInfo().detectedBranch
        val invalidKmiIds = plan.steps.asSequence().filter { it.enabled && it.kind in setOf(ModuleKind.DAEMON, ModuleKind.MANAGER) }
            .filter { ModuleKmi.resolve(it.kmi, kmiSelection.branch, detectedBranch) == null }
            .map { it.id }.toSet()
        val managers = runCatching { ManagerCatalog.scan(context) }.getOrDefault(emptyList())
        val privilege = Shell.detect(context)
        val root = privilege != Privilege.App
        val needsReboot = plan.steps.any { it.enabled && it.kind == ModuleKind.SOFT_REBOOT }
        val binaries = if (root && needsReboot) {
            val result = Shell.run(context, "for name in ksud apd; do [ -x /data/adb/\"\$name\" ] && echo \"\$name\"; done")
            result.output.lineSequence().map { it.trim() }.filter { it in setOf("apd", "ksud") }.toSet()
        } else emptySet()
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val nativeAvailable = listOf("libbootstrap.so", "libdfroot.so").all { File(nativeDir, it).canExecute() }
        val managerSteps = plan.steps.filter { it.enabled && it.kind == ModuleKind.MANAGER && it.managerPackage.isNotBlank() }
        val libraryPresent = managerSteps.all { step ->
            val backend = managers.firstOrNull { it.packageName == step.managerPackage }?.backend
                ?: step.backend.takeIf { it.isNotBlank() } ?: "ksud"
            runCatching { ManagerCatalog.libraryPath(context, backend, step.managerPackage) }.isSuccess
        }
        val env = RequirementEnvironment(
            rootAvailable = root,
            daemonAvailable = privilege == Privilege.Daemon,
            nativeAvailable = nativeAvailable,
            managers = managers.map { it.packageName }.toSet(),
            rebootBinaries = binaries,
            managerBackends = managers.associate { it.packageName to it.backend },
            invalidKmiIds = invalidKmiIds,
            backendLibraryPresent = libraryPresent,
        )
        return inspect(plan, env).map { issue ->
            ModuleIssue(issue.step.id, issue.position, issue.step.kind, issue.step.name,
                context.getString(when (issue.reason) {
                    RequirementReason.EMPTY_PLAN -> R.string.module_condition_empty_plan
                    RequirementReason.EMPTY_SCRIPT -> R.string.module_condition_empty_script
                    RequirementReason.MANAGER_REQUIRED -> R.string.module_condition_manager_required
                    RequirementReason.MANAGER_MISSING -> R.string.module_condition_manager_missing
                    RequirementReason.MANAGER_LIBRARY_MISSING -> R.string.module_condition_manager_library
                    RequirementReason.NATIVE_MISSING -> R.string.module_condition_native_missing
                    RequirementReason.ROOT_REQUIRED -> R.string.module_condition_root_required
                    RequirementReason.REBOOT_MANAGER_REQUIRED -> R.string.module_condition_reboot_manager
                    RequirementReason.REBOOT_BINARY_MISSING -> R.string.module_condition_reboot_binary
                    RequirementReason.REBOOT_FINAL_REQUIRED -> R.string.module_condition_reboot_final
                    RequirementReason.DAEMON_REQUIRED -> R.string.module_condition_daemon_required
                    RequirementReason.KMI_UNRESOLVED -> R.string.module_condition_kmi_unresolved
                }))
        }
    }
}
