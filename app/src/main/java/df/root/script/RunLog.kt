package df.root.script

import android.content.Context
import df.root.shell.Privilege
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Per-run logging, one file per run under `<base>/daemon-control/`.
 *
 * A single rolling log is useless for triage: by the time a run fails the log
 * has been overwritten by the next attempt, and the one thing needed is the
 * exact step, command and exit code of the failure. Each run therefore gets its
 * own timestamped file, plus a stable `last-run.log` symlink-free copy so the
 * app always has one obvious place to look.
 *
 * Detail is deliberate and includes the *LKM* side, not just what the app did:
 * the daemon's own log is folded in so one file answers "which step failed".
 */
object RunLog {
    private const val STAMP = "yyyyMMdd-HHmmss"
    const val LATEST = "last-run.log"

    fun directory(context: Context): File = RuntimeFiles.logs(context)

    fun fileFor(context: Context, trigger: String, startedAt: Long): File =
        File(directory(context), "run-$trigger-${stamp(startedAt)}-${java.util.UUID.randomUUID()}.log")

    /** The copy the UI and `logFile()` have always pointed at. */
    fun latest(context: Context): File = File(directory(context), LATEST)

    /** Every run this device has kept, newest first. */
    fun history(context: Context): List<File> = directory(context)
        .listFiles { f -> f.isFile && f.name.startsWith("run-") && f.name.endsWith(".log") }
        ?.sortedByDescending { it.name } ?: emptyList()

    fun append(context: Context, file: File, line: String) = runCatching {
        RuntimeFiles.adopt(context, file)
        file.appendText(line)
        // Keep the UI's stable handle current without a second write path.
        val latest = latest(context)
        RuntimeFiles.adopt(context, latest)
        latest.writeText(file.readText().takeLast(65536))
    }.getOrElse { /* Logging must never abort a run. */ }

    fun stamp(at: Long = System.currentTimeMillis()): String =
        SimpleDateFormat(STAMP, Locale.US).format(Date(at))

    fun header(context: Context, trigger: String, startedAt: Long, privilege: Privilege): String = buildString {
        append("=== DirtyFrag module run ===\n")
        append("trigger: $trigger\n")
        append("started: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(startedAt))}\n")
        append("base directory: ${RuntimeFiles.root(context).absolutePath}\n")
        append("logs: ${directory(context).absolutePath}\n")
        append("markers: ${RuntimeFiles.markers(context).absolutePath}\n")
        append("privilege: ${privilege.label}\n")
    }
}
