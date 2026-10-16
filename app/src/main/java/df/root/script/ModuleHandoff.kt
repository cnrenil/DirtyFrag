package df.root.script

import android.content.Context
import df.root.ExploitRunner
import java.io.File

/**
 * The module plan handed to dfdaemon as files, not FIFO verbs.
 *
 * Daemon bootstrap and SU-manager late-load stay native. Everything else is a
 * shell file. The daemon reads [PLAN] at startup (and again while serving) so
 * the app never waits for the control channel before the rest of the plan runs.
 */
internal data class HandoffAction(
    val verb: String,
    val id: String,
    val path: String = "",
    val softReboot: Boolean = false,
)

internal data class PlanStatus(
    val id: String = "",
    val state: String = "",
    val exit: Int = -1,
    val line: String = "",
) {
    val finished: Boolean get() = state == "done" || state == "fail" || state == "accepted"
    val ok: Boolean get() = state == "done" || state == "accepted"
}

internal object ModuleHandoff {
    const val HEADER = "# dfplan 1"
    const val PLAN = "plan"
    const val PLAN_TMP = "plan.tmp"
    const val STATUS = "plan.status"
    const val LOG = "plan.log"
    const val MARKER_DONE = "plan-done"
    const val MARKER_FAILED = "plan-failed"
    const val MARKER_ACCEPTED = "plan-accepted"

    private val progressStates = setOf("running", "ok", "accepted", "done")

    /**
     * Soft-reboot cleanup deletes pid/ready before the exploit waiter returns.
     * A 0 from the native waiter, a live daemon, or a plan that already moved
     * is success — not "DAEMON exit=1".
     */
    fun bootstrapSucceeded(rc: Int, daemonAlive: Boolean, ready: Boolean, status: PlanStatus?): Boolean {
        if (rc == 0 || daemonAlive || ready) return true
        return status != null && status.state in progressStates
    }

    fun dir(context: Context): File = ModuleStore.scripts(context)

    fun planFile(context: Context) = File(dir(context), PLAN)
    fun statusFile(context: Context) = File(dir(context), STATUS)
    fun logFile(context: Context) = File(dir(context), LOG)

    fun encode(actions: List<HandoffAction>): String = buildString {
        appendLine(HEADER)
        actions.forEach { action ->
            appendLine(when (action.verb) {
                "exec" -> "exec ${action.id} ${action.path}"
                "lateload" -> if (action.softReboot) "lateload ${action.id} soft-reboot" else "lateload ${action.id}"
                "soft-reboot" -> "soft-reboot ${action.id}"
                else -> error("Unknown handoff verb: ${action.verb}")
            })
        }
    }

    fun parse(text: String): List<HandoffAction> = text.lineSequence().map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }.map { line ->
            val parts = line.split(' ')
            require(parts.size >= 2) { "Malformed plan line: $line" }
            when (parts[0]) {
                "exec" -> {
                    require(parts.size == 3) { "Malformed exec line: $line" }
                    HandoffAction("exec", parts[1], path = parts[2])
                }
                "lateload" -> {
                    require(parts.size == 2 || (parts.size == 3 && parts[2] == "soft-reboot")) { "Malformed lateload line: $line" }
                    HandoffAction("lateload", parts[1], softReboot = parts.size == 3)
                }
                "soft-reboot" -> {
                    require(parts.size == 2) { "Malformed soft-reboot line: $line" }
                    HandoffAction("soft-reboot", parts[1])
                }
                else -> error("Unknown plan verb: ${parts[0]}")
            }
        }.toList()

    fun parseStatus(text: String): PlanStatus {
        var id = ""
        var state = ""
        var exit = -1
        var line = ""
        text.lineSequence().forEach { raw ->
            when {
                raw.startsWith("id=") -> id = raw.removePrefix("id=")
                raw.startsWith("state=") -> state = raw.removePrefix("state=")
                raw.startsWith("exit=") -> exit = raw.removePrefix("exit=").toIntOrNull() ?: -1
                raw.startsWith("line=") -> line = raw.removePrefix("line=")
            }
        }
        return PlanStatus(id, state, exit, line)
    }

    fun managerText(packageName: String, backend: String, library: String, kmi: String, softReboot: Boolean) =
        "package=$packageName\nbackend=$backend\nlibrary=$library\nkmi=$kmi\nsoft_reboot=${if (softReboot) 1 else 0}\n"

    fun clear(context: Context) {
        val folder = dir(context)
        listOf(PLAN, PLAN_TMP, "plan.running", STATUS, LOG).forEach { name ->
            File(folder, name).takeIf { it.exists() }?.delete()
        }
        File(RuntimeFiles.markers(context), MARKER_DONE).takeIf { it.exists() }?.delete()
        File(RuntimeFiles.markers(context), MARKER_FAILED).takeIf { it.exists() }?.delete()
        File(RuntimeFiles.markers(context), MARKER_ACCEPTED).takeIf { it.exists() }?.delete()
    }

    /**
     * Writes shell files, late-load sidecars and the plan. The plan file is the
     * last rename, so a live daemon never observes a partial sequence.
     */
    fun write(context: Context, steps: List<ModuleStep>): List<HandoffAction> {
        val folder = dir(context)
        if (!folder.isDirectory && !folder.mkdirs()) error("Cannot create ${folder.absolutePath}")
        RuntimeFiles.adopt(context, File(folder, ".keep"))
        val markers = RuntimeFiles.markers(context).absolutePath
        val actions = steps.filter { it.enabled && it.kind != ModuleKind.DAEMON }.map { step ->
            when (step.kind) {
                ModuleKind.MANAGER -> {
                    require(step.managerPackage.isNotBlank()) { "Select an SU manager in this module" }
                    val manager = ManagerCatalog.scan(context).firstOrNull { it.packageName == step.managerPackage }
                        ?: error("Manager is unavailable: ${step.managerPackage}")
                    val backend = manager.backend
                    val library = ManagerCatalog.libraryPath(context, backend, step.managerPackage)
                    val kmi = ModuleKmi.resolve(context, step.kmi)
                        ?: error("Manager KMI cannot be resolved; select a valid KMI branch in Settings")
                    ExploitRunner.selectManager(context, step.managerPackage, backend)
                    check(context.createDeviceProtectedStorageContext().getSharedPreferences("dfroot", Context.MODE_PRIVATE)
                        .edit().putString("kmi", kmi).commit())
                    val sidecar = File(folder, "${step.id}.manager")
                    RuntimeFiles.adopt(context, sidecar)
                    sidecar.writeText(managerText(step.managerPackage, backend, library, kmi, step.softReboot))
                    RuntimeFiles.adopt(context, sidecar)
                    HandoffAction("lateload", step.id, softReboot = step.softReboot)
                }
                ModuleKind.SOFT_REBOOT -> HandoffAction("soft-reboot", step.id)
                else -> {
                    val text = ScriptPresets.moduleScript(step.kind, step.script, markers)
                    require(text.isNotBlank()) { "Shell module is empty" }
                    val file = File(folder, "${step.id}.sh")
                    RuntimeFiles.adopt(context, file)
                    file.writeText(text.replace("\r\n", "\n"))
                    RuntimeFiles.adopt(context, file)
                    HandoffAction("exec", step.id, path = file.absolutePath)
                }
            }
        }
        val tmp = File(folder, PLAN_TMP)
        val dest = File(folder, PLAN)
        RuntimeFiles.adopt(context, tmp)
        tmp.writeText(encode(actions))
        RuntimeFiles.adopt(context, tmp)
        if (!tmp.renameTo(dest)) {
            RuntimeFiles.adopt(context, dest)
            dest.writeText(encode(actions))
            tmp.delete()
        }
        return actions
    }
}
