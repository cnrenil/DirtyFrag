package df.root.script

import android.content.Context
import df.root.ExploitRunner
import df.root.IReporter
import df.root.daemon.Dfctl
import df.root.shell.Privilege
import df.root.shell.Shell
import df.root.shell.ShellResult
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import java.io.File
import java.util.UUID

/**
 * One sequential executor shared by manual, app-launch and boot entry points.
 *
 * [issues] carries the result of the last precondition check. The UI shows them
 * as a blocking dialog; a run never starts while any is outstanding, which is
 * what stops an unusable plan from failing halfway through with a raw exit code.
 */
data class ModuleExecutionState(
    val running: Boolean = false,
    val currentId: String? = null,
    val output: String = "",
    val progress: Float = 0f,
    val issues: List<ModuleIssue> = emptyList(),
    val logPath: String = "",
)

object ModuleEngine {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(ModuleExecutionState())
    val state: StateFlow<ModuleExecutionState> = mutableState
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The most recent run log; kept for callers that predate per-run files. */
    fun logFile(context: Context): File = RunLog.latest(context)

    fun dismissIssues() { mutableState.value = mutableState.value.copy(issues = emptyList()) }

    fun launchManual(context: Context) {
        val app = context.applicationContext
        scope.launch { run(app, "manual") }
    }

    fun onAppOpen(context: Context) {
        val app = context.applicationContext
        scope.launch { run(app, "app") }
    }

    suspend fun run(context: Context): ShellResult = scope.async { run(context.applicationContext, "manual") }.await()

    /**
     * Checks the plan, then executes it. Automatic triggers (app launch, boot)
     * are checked too: a boot run whose manager was uninstalled must not fire.
     */
    internal suspend fun run(context: Context, trigger: String): ShellResult = withContext(Dispatchers.IO) {
        if (!mutex.tryLock()) return@withContext ShellResult(16, "A module plan is already running")
        var original: ModulePlan? = null
        var armed = false
        val output = StringBuilder()
        val startedAt = System.currentTimeMillis()
        val log = RunLog.fileFor(context, trigger, startedAt)

        fun publish(line: String) {
            output.append(line)
            if (output.length > 65536) output.delete(0, output.length - 65536)
            mutableState.value = mutableState.value.copy(output = output.toString())
            RunLog.append(context, log, line)
        }

        try {
            val plan = synchronized(ModuleStore) {
                val saved = ModuleStore.read(context)
                if (trigger == "app" && !saved.onAppOpen || trigger == "boot" && !saved.onBoot)
                    return@withContext ShellResult(0, "Automatic execution disabled")
                original = saved
                val token = UUID.randomUUID().toString()
                ModuleStore.activeToken = token
                ModuleStore.persist(context, ModulePolicy.begin(saved, token))
                armed = true
                saved
            }
            mutableState.value = ModuleExecutionState(running = true, logPath = log.absolutePath)
            val issues = ModulePreflight.check(context, plan)
            if (issues.isNotEmpty()) {
                val summary = issues.joinToString("\n") { "#${it.position} ${it.name.ifBlank { it.kind.name }}: ${it.reason}" }
                ModuleStore.persist(context, ModulePolicy.disarmed(plan, summary))
                mutableState.value = mutableState.value.copy(issues = issues)
                publish("Preconditions not met:\n$summary\nAutomatic execution disabled.\n")
                return@withContext ShellResult(2, summary)
            }
            RunLog.append(context, log, RunLog.header(context, trigger, startedAt, Shell.detect(context)))
            publish("trigger=$trigger log=${log.absolutePath}\n")

            val snapshot = checkNotNull(original)
            val total = snapshot.steps.count { it.enabled }.coerceAtLeast(1)
            var completed = 0
            var currentKind: ModuleKind? = null
            fun note(step: ModuleStep) {
                currentKind = step.kind
                mutableState.value = mutableState.value.copy(currentId = step.id)
                publish("\n[${step.kind}] ${step.name.ifBlank { step.id }}\n")
            }
            fun advance() {
                mutableState.value = mutableState.value.copy(progress = ++completed / total.toFloat())
            }
            val enabled = snapshot.steps.filter { it.enabled }
            val daemonAt = enabled.indexOfFirst { it.kind == ModuleKind.DAEMON }
            val prefix = if (daemonAt >= 0) enabled.take(daemonAt) else emptyList()
            val rest = if (daemonAt >= 0) enabled.drop(daemonAt + 1) else enabled
            val startDaemon = daemonAt >= 0
            val daemonSoon = startDaemon || daemonProcessAlive(context)

            val outcome = run {
                ModuleSequence.execute(prefix) { step ->
                    ensureActive()
                    note(step)
                    val result = executeLocal(context, step)
                    publish(result.output.trimEnd() + "\nexit=${result.exit}\n")
                    if (!result.ok) publish("continuing after non-zero exit\n")
                    advance()
                    result
                }
                if (startDaemon || (daemonSoon && rest.isNotEmpty())) {
                    ModuleHandoff.clear(context)
                    if (rest.isNotEmpty()) {
                        ModuleHandoff.write(context, rest)
                        publish("handoff ${rest.size} step(s) to daemon via ${ModuleHandoff.planFile(context).absolutePath}\n")
                    }
                    val boot = if (!startDaemon) ShellResult(0, "") else {
                        note(enabled[daemonAt])
                        val result = bootstrapDaemon(context, ::publish)
                        publish(result.output.trimEnd() + "\nexit=${result.exit}\n")
                        if (result.ok) advance()
                        result
                    }
                    if (!boot.ok) {
                        ModuleHandoff.clear(context)
                        boot
                    } else if (rest.isEmpty()) ShellResult(0, "")
                    else awaitPlan(context, rest.associateBy { it.id }, ::publish, ::note, ::advance)
                } else ModuleSequence.execute(rest) { step ->
                    ensureActive()
                    note(step)
                    val result = executeLocal(context, step)
                    publish(result.output.trimEnd() + "\nexit=${result.exit}\n")
                    if (!result.ok) publish("continuing after non-zero exit\n")
                    advance()
                    result
                }
            }
            if (!outcome.ok) {
                ModuleStore.persist(context, ModulePolicy.failed(snapshot, "$currentKind: exit=${outcome.exit}"))
                publish("Finished with errors; automatic execution remains enabled.\n")
                return@withContext ShellResult(outcome.exit, output.toString())
            }
            mutableState.value = mutableState.value.copy(progress = 1f)
            ModuleStore.persist(context, ModulePolicy.completed(snapshot))
            publish(if (snapshot.steps.any { it.enabled && it.kind == ModuleKind.MANAGER && it.softReboot })
                "\nDaemon accepted cleanup → late-load → soft-reboot. Automatic execution remains enabled after reboot.\n"
                else "\nAll enabled modules completed.\n")
            ShellResult(0, output.toString())
        } catch (cancelled: CancellationException) {
            if (armed) runCatching { ModuleStore.persist(context, ModulePolicy.failed(original!!, "Execution cancelled")) }
            throw cancelled
        } catch (failure: Exception) {
            if (armed) runCatching { ModuleStore.persist(context, ModulePolicy.failed(original!!, failure.message ?: "Execution failed")) }
            publish("Error: ${failure.message}\n")
            ShellResult(1, output.toString())
        } finally {
            ModuleStore.activeToken = ""
            mutableState.value = mutableState.value.copy(running = false, currentId = null)
            mutex.unlock()
        }
    }

    /**
     * Starts the exploit bootstrap only. Remaining modules are a plan file the
     * daemon consumes without a FIFO round trip.
     */
    private suspend fun bootstrapDaemon(context: Context, report: (String) -> Unit): ShellResult {
        if (daemonProcessAlive(context)) return ShellResult(0, "Daemon already ready")
        val rc = ExploitRunner.run(context.applicationContext, IReporter { report(it) })
        val ready = File(Dfctl.dir(context), "dfctl.ready").isFile
        val status = ModuleHandoff.statusFile(context).takeIf { it.isFile }?.let { ModuleHandoff.parseStatus(it.readText()) }
        return if (ModuleHandoff.bootstrapSucceeded(rc, daemonProcessAlive(context), ready, status))
            ShellResult(0, "Daemon ready")
        else ShellResult(if (rc == 0) 1 else rc, "Daemon initialization failed")
    }

    private suspend fun awaitPlan(
        context: Context,
        byId: Map<String, ModuleStep>,
        report: (String) -> Unit,
        note: (ModuleStep) -> Unit,
        advance: () -> Unit,
    ): ShellResult {
        val deadline = System.currentTimeMillis() + 600_000
        var seenLog = ""
        var lastId: String? = null
        var lastState = ""
        val finished = mutableSetOf<String>()
        val statusFile = ModuleHandoff.statusFile(context)
        val logFile = ModuleHandoff.logFile(context)
        val done = File(RuntimeFiles.markers(context), ModuleHandoff.MARKER_DONE)
        val failed = File(RuntimeFiles.markers(context), ModuleHandoff.MARKER_FAILED)
        while (System.currentTimeMillis() < deadline) {
            currentCoroutineContext().ensureActive()
            val logText = logFile.takeIf { it.isFile }?.readText().orEmpty()
            if (logText.length > seenLog.length && logText.startsWith(seenLog)) {
                report(logText.substring(seenLog.length))
                seenLog = logText
            } else if (logText != seenLog) {
                report(logText)
                seenLog = logText
            }
            val status = statusFile.takeIf { it.isFile }?.let { ModuleHandoff.parseStatus(it.readText()) }
            if (status != null && (status.id != lastId || status.state != lastState)) {
                lastId = status.id
                lastState = status.state
                val step = byId[status.id]
                if (step != null && status.state == "running") note(step)
                if (step != null && status.state in setOf("ok", "accepted") && finished.add(status.id)) advance()
                if (status.line.isNotBlank()) report(status.line + "\n")
            }
            when {
                status?.state == "accepted" -> {
                    creditRemaining(byId.keys, finished, advance)
                    return ShellResult(0, status.line)
                }
                status?.state == "done" || done.isFile -> {
                    creditRemaining(byId.keys, finished, advance)
                    return ShellResult(0, "plan done")
                }
                status?.state == "fail" || failed.isFile ->
                    return ShellResult(status?.exit?.takeIf { it != 0 } ?: 1, status?.line ?: "plan failed")
            }
            delay(150)
        }
        return ShellResult(1, "Timed out waiting for daemon plan")
    }

    /** plan.status is a single slot; fast execs are overwritten before the waiter sees them. */
    private fun creditRemaining(ids: Collection<String>, finished: MutableSet<String>, advance: () -> Unit) {
        ids.forEach { id -> if (id.isNotEmpty() && finished.add(id)) advance() }
    }

    /** Pid file plus /proc: never a FIFO round trip. */
    internal fun daemonProcessAlive(context: Context): Boolean {
        val pid = Dfctl.pid(context) ?: return false
        return File("/proc/$pid").isDirectory
    }

    private suspend fun executeLocal(context: Context, step: ModuleStep): ShellResult {
        require(step.kind != ModuleKind.DAEMON) { "Daemon bootstrap is not a local shell step" }
        require(step.kind != ModuleKind.MANAGER) { "Manager late-load is handed to the daemon" }
        if (step.kind != ModuleKind.SHELL) require(Shell.detect(context) != Privilege.App) { "This module requires root or daemon" }
        val text = command(context, step)
        require(text.isNotBlank()) { "Shell module is empty" }
        val file = File(ModuleStore.scripts(context), "${step.id}.sh")
        RuntimeFiles.adopt(context, file)
        file.writeText(text.replace("\r\n", "\n"))
        RuntimeFiles.adopt(context, file)
        return Shell.run(context, "/system/bin/sh " + ScriptPresets.quote(file.absolutePath))
    }

    /**
     * The binary a soft reboot should use.
     *
     * Never the staged copy in the data dir: after a reboot that path is
     * re-created by us, and a soft reboot is exactly the moment the running
     * daemon's own files may already be gone. /data/adb/ksud and /data/adb/apd
     * are the manager's installed binaries and survive everything.
     */
    internal fun softRebootBinary(context: Context, step: ModuleStep): String {
        val prefs = context.createDeviceProtectedStorageContext().getSharedPreferences("dfroot", Context.MODE_PRIVATE)
        val manager = prefs.getString("su_manager", null)
        require(!manager.isNullOrBlank()) { "Load a manager before requesting soft reboot" }
        return softRebootPath(prefs.getString("su_backend", "ksud") ?: "ksud")
    }

    /** Pure mapping from backend to the installed binary, for tests. */
    internal fun softRebootPath(backend: String): String = if (backend == "apd") "/data/adb/apd" else "/data/adb/ksud"

    internal fun command(context: Context, step: ModuleStep): String =
        ScriptPresets.moduleScript(step.kind, step.script, RuntimeFiles.markers(context).absolutePath)
}
