package df.root.script

import android.content.Context
import java.io.File

/**
 * Run markers are bound to the kernel boot id, not wiped.
 *
 * A leftover `daemon-ready` from last boot is ignored because its contents
 * (the previous boot id) no longer match `/proc/sys/kernel/random/boot_id`.
 * That is what prevents a second exploit on the same boot and what lets the
 * home screen stay "rooted" after the daemon exits under enforcing SELinux.
 */
internal object BootMarkers {
    const val PROC = "/proc/sys/kernel/random/boot_id"
    const val FILE = "boot_id"
    const val DAEMON_READY = "daemon-ready"
    const val PLAN_DONE = "plan-done"
    const val PLAN_ACCEPTED = "plan-accepted"
    const val PLAN_FAILED = "plan-failed"
    const val KERNEL_MODULE = "kernel-module"
    const val BOOT_RUN = "boot-run"

    fun liveId(): String = runCatching { File(PROC).readText().trim() }.getOrDefault("")

    /** First line is the boot id; remaining lines are optional payload. */
    fun storedId(text: String): String = text.lineSequence().firstOrNull()?.trim().orEmpty()

    fun body(bootId: String, extra: String = ""): String {
        val id = bootId.trim()
        val payload = extra.trimEnd()
        return if (payload.isEmpty()) id else "$id\n$payload"
    }

    fun sameBoot(stored: String, live: String): Boolean {
        val id = storedId(stored)
        val right = live.trim()
        return id.isNotEmpty() && id == right
    }

    fun has(dir: File, name: String, live: String): Boolean {
        val file = File(dir, name)
        if (!file.isFile) return false
        val stored = runCatching { file.readText() }.getOrDefault("")
        return sameBoot(stored, live)
    }

    fun has(context: Context, name: String): Boolean {
        val live = liveId()
        if (live.isEmpty()) return false
        return has(RuntimeFiles.markers(context), name, live)
    }

    /** This boot already finished the plan or started the daemon. */
    fun thisBoot(context: Context): Boolean =
        has(context, PLAN_DONE) || has(context, DAEMON_READY) || has(context, PLAN_ACCEPTED)

    /**
     * Automatic boot/app-open already claimed this kernel boot id.
     * Survives ksud soft-reboot because `/proc/sys/kernel/random/boot_id` does not change.
     */
    fun autoConsumed(context: Context): Boolean =
        has(context, BOOT_RUN) || has(context, PLAN_DONE) || has(context, PLAN_ACCEPTED)

    fun write(context: Context, name: String, extra: String = ""): File {
        val id = liveId()
        val file = RuntimeFiles.marker(context, name, body(id, extra))
        if (name != FILE) RuntimeFiles.marker(context, FILE, id)
        return file
    }
}
