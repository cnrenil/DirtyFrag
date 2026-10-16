package df.root.script

import android.content.Context
import android.system.Os
import java.io.File

/**
 * Everything a run leaves behind lives under the app's device-protected data
 * directory, never under /dev.
 *
 * Two reasons. /dev is not ours: it is a tmpfs the vendor owns, a stray
 * /dev/df* from a previous run is indistinguishable from this one, and nothing
 * in the app can read it back. A file in the app's own tree can be read
 * afterwards, so a marker's presence and mtime become a usable timeline.
 *
 * Ownership is the trap. These paths are created by uid 0 - the LKM helper or
 * the daemon - but must be readable by the app. Every path is therefore chowned
 * with *both* uid and gid to the data directory's owner before it is used; a
 * root:root file is invisible to the app and the run silently loses its own
 * diagnostics. The owner is always read from the data directory and never
 * inherited, because an inherited owner would be root every time.
 */
object RuntimeFiles {
    /** /data/user_de/0/df.root - the app's device-protected base directory. */
    fun root(context: Context): File = checkNotNull(
        context.createDeviceProtectedStorageContext().filesDir.parentFile
    ) { "device-protected data directory unavailable" }

    /** Every log written by the app, the daemon and the LKM helper. */
    fun logs(context: Context): File = File(root(context), "daemon-control")

    fun markers(context: Context): File = File(root(context), "markers")

    @JvmStatic
    fun prepare(context: Context) {
        val owner = ownerOf(context)
        ensureDir(logs(context), owner)
        ensureDir(markers(context), owner)
    }

    /**
     * Creates [file] owned by the app, ready to be written.
     *
     * The file is created first and chowned immediately, so there is no window
     * in which root owns it and the app cannot open it.
     */
    @JvmStatic
    fun adopt(context: Context, file: File) {
        val owner = ownerOf(context)
        file.parentFile?.let { ensureDir(it, owner) }
        if (!file.exists() && !file.createNewFile()) error("Cannot create ${file.absolutePath}")
        Os.chown(file.absolutePath, owner.uid, owner.gid)
    }

    /** Appends a diagnostics block as the app's own uid. */
    @JvmStatic
    fun append(context: Context, name: String, text: String) {
        val file = File(logs(context), checkName(name, ".log"))
        adopt(context, file)
        Os.chmod(file.absolutePath, 0x180) // 0600
        file.appendText(text)
    }

    /** Records a marker file, the coarse "which step did we reach" timeline. */
    @JvmStatic
    fun marker(context: Context, name: String, text: String = ""): File {
        val file = File(markers(context), checkName(name))
        adopt(context, file)
        Os.chmod(file.absolutePath, 0x180) // 0600
        if (text.isNotEmpty()) file.writeText(text)
        return file
    }

    @JvmStatic
    fun clearMarkers(context: Context) {
        markers(context).listFiles()?.forEach { runCatching { it.delete() } }
    }

    private class Owner(val uid: Int, val gid: Int)

    private fun ownerOf(context: Context): Owner = runCatching {
        val st = Os.stat(root(context).absolutePath)
        Owner(st.st_uid, st.st_gid)
    }.getOrElse { Owner(Os.getuid(), Os.getgid()) }

    private fun checkName(name: String, suffix: String = ""): String {
        require(name.matches(Regex("[A-Za-z0-9_.-]+$suffix"))) { "Unsafe runtime file name: $name" }
        return name
    }

    private fun ensureDir(dir: File, owner: Owner) {
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory)
            error("Cannot create ${dir.absolutePath}")
        // Both ids: a uid-only chown leaves the group wrong for group checks.
        Os.chown(dir.absolutePath, owner.uid, owner.gid)
        Os.chmod(dir.absolutePath, 0x1C0) // 0700
    }
}
