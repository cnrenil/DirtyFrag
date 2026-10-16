package df.root.shell

import android.content.Context
import df.root.daemon.Dfctl
import df.root.daemon.Verb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Which privilege level the terminal is currently driving.
 *
 * Probed by actually running something, never inferred from a file existing:
 * `su` can be present in PATH and still refuse, and the daemon fifo can exist
 * while the SELinux label denies writes.
 */
enum class Privilege(val label: String) {
    /** Root via the daemon's control channel. */
    Daemon("Daemon"),

    /** Root via a real `su` binary. */
    Su("SU"),

    /** No root: commands run as this app's own uid. */
    App("应用"),
}

/** A command's outcome, uniform across all three transports. */
data class ShellResult(
    val exit: Int,
    val output: String,
) {
    val ok: Boolean get() = exit == 0
}

/**
 * Runs shell commands at the highest privilege currently available, re-probing
 * on demand so the level follows the device rather than a stale assumption.
 *
 * Order is daemon > su > app:
 *  - the daemon is the exploit's own channel and needs no su binary at all;
 *  - `su` is next because it is a normal root shell;
 *  - the app's own uid is the fallback, which still lets the terminal be used
 *    for non-root inspection (getprop, ls of readable paths, and so on).
 */
object Shell {

    /** Where `su` is looked for. Vendor images vary, and a non-root app often
     * inherits a PATH without /system/bin, so PATH is not consulted first. */
    private val SU_CANDIDATES = listOf(
        "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su",
        "/product/bin/su", "/vendor/bin/su", "/debug_ramdisk/su",
        "/data/adb/ksu/bin/su", "/data/adb/magisk/su",
    )

    /** Absolute path: ProcessBuilder does not search PATH for the program. */
    private const val SH = "/system/bin/sh"
    private const val PATH = "/system/bin:/system/xbin:/vendor/bin:/product/bin"

    @Volatile
    var current: Privilege = Privilege.App
        private set

    /** Probing su forks a process, which on a real device can raise an
     * authorization dialog, so a known-good result is cached. */
    @Volatile
    private var suPath: String? = null

    @Volatile
    private var suVerified = false

    /**
     * Detects the best available privilege.
     *
     * The daemon check is a single fifo round trip and is safe to repeat. The
     * su check is not, so it runs once and is then trusted; [forceSu] re-checks
     * it, and a failed su is retried because the user may have just granted it.
     */
    suspend fun detect(ctx: Context, forceSu: Boolean = false): Privilege = withContext(Dispatchers.IO) {
        val p = when {
            daemonUsable(ctx) -> Privilege.Daemon
            suAvailable(forceSu) -> Privilege.Su
            else -> Privilege.App
        }
        current = p
        p
    }

    private fun suAvailable(force: Boolean): Boolean {
        if (suPath == null) suPath = findSu()
        val su = suPath ?: return false
        if (suVerified && !force) return true
        suVerified = probeSu(su)
        return suVerified
    }

    /** Runs su once to confirm it really grants root. */
    private fun probeSu(su: String): Boolean = runCatching {
        val result = ProcessExecutor.run(ProcessBuilder(su, "-c", "id -u"), 8000)
        result.exit == 0 && result.output.trim() == "0"
    }.getOrDefault(false)

    /** A fifo that exists is not enough: under enforcing SELinux the write itself
     *  is denied, so the only valid probe is a real round trip. Shared with the
     *  home screen so both agree on what "ready" means. */
    private suspend fun daemonUsable(ctx: Context): Boolean = Dfctl.ping(ctx)

    fun findSu(): String? = SU_CANDIDATES.firstOrNull { File(it).canExecute() }

    /** A one-line description of the transport, for the terminal's self-check. */
    fun channelNote(ctx: Context): String {
        val d = Dfctl.describeChannel(ctx)
        val su = suPath ?: findSu()
        return when {
            current == Privilege.Daemon -> "dfctl " + d
            su != null -> "su=" + su + " (dfctl " + d + ")"
            else -> "无 su;dfctl " + d
        }
    }

    /**
     * Runs [command], routing it to the current privilege. Output is returned
     * whole; use [stream] when it needs to be shown live.
     */
    suspend fun run(ctx: Context, command: String): ShellResult = withContext(Dispatchers.IO) {
        val result = when (current) {
            Privilege.Daemon -> viaDaemon(ctx, command)
            Privilege.Su -> viaSu(findSu(), command)
            Privilege.App -> viaApp(command)
        }
        // A cached su can be revoked while the app is running, and a daemon
        // that answered a ping can still be refused by SELinux a moment later.
        // Re-probe after a failure so the next command uses a level that works
        // rather than repeating one that does not.
        if (result.exit != 0) detect(ctx, forceSu = true)
        result
    }

    /**
     * Runs a daemon verb directly, for things that are not shell commands.
     *
     * The daemon console used to be its own screen with its own buttons; the
     * verbs it exposed (status, env, script) have no shell equivalent, so the
     * terminal needs a way to reach them. Returns null when the daemon is not
     * reachable, so the caller can fall back rather than show a dead result.
     */
    suspend fun daemonVerb(ctx: Context, verb: String, arg: String? = null): ShellResult? =
        withContext(Dispatchers.IO) {
            when (val r = Dfctl.request(ctx, verb, arg)) {
                is Dfctl.Outcome.Success -> daemonResult(r.result)
                is Dfctl.Outcome.Unreachable -> null
            }
        }

    private suspend fun viaDaemon(ctx: Context, command: String): ShellResult =
        when (val r = Dfctl.request(ctx, Verb.EXEC, command)) {
            is Dfctl.Outcome.Success -> daemonResult(r.result)
            is Dfctl.Outcome.Unreachable -> ShellResult(1, "daemon 不可用:${r.why}")
        }

    internal fun daemonResult(result: Dfctl.Result): ShellResult {
        val exit = result.exit ?: if (result.ok) 0 else 1
        val diagnostic = result.error?.takeIf { it.isNotBlank() }
        return ShellResult(if (!result.ok && exit == 0) 1 else exit,
            listOfNotNull(result.text.takeIf { it.isNotEmpty() }, diagnostic).joinToString("\n"))
    }

    private fun viaSu(su: String?, command: String): ShellResult {
        if (su == null) return ShellResult(127, "找不到 su")
        return exec(listOf(su, "-c", command), "su 执行失败")
    }

    private fun viaApp(command: String): ShellResult =
        exec(listOf(SH, "-c", command), "执行失败")

    /**
     * Runs a command and collects merged stdout/stderr.
     *
     * PATH is set explicitly: an app process can inherit a PATH that lacks
     * /system/bin, and a child that then cannot find `ls` fails in a way that
     * looks like the command itself failed.
     */
    private fun exec(cmd: List<String>, failLabel: String): ShellResult = runCatching {
        val pb = ProcessBuilder(cmd).redirectErrorStream(true)
        pb.environment()["PATH"] = PATH
        ProcessExecutor.run(pb, 120000)
    }.getOrElse { ShellResult(1, "$failLabel:${it.message}") }
}
