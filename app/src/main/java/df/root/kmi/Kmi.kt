package df.root.kmi

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.system.Os
import android.system.StructUtsname
import df.root.KmiPrefs
import java.util.regex.Pattern

/** GKI release format: kernel.patch-androidRelease-generation-suffix. */
private val KMI_PATTERN: Pattern = Pattern.compile(
    "^([0-9]+\\.[0-9]+)\\.[0-9]+-(android[0-9]+)(?:-([0-9]+))?(?:-|$)"
)

/** Immutable snapshot of the device's kernel metadata. */
data class KmiInfo(
    val manufacturerModel: String,
    val androidRelease: String,
    val apiLevel: Int,
    val securityPatch: String,
    val release: String?,
    val machine: String?,
    val detectedBranch: String?,
    val fullKmi: String?,
)

/** The branch and vermagic the next run will use. */
data class KmiSelection(
    /** null means "let the exploit detect it". */
    val branch: String?,
    val vermagicMode: String,
    val vermagicCustom: String,
)

object Kmi {

    /**
     * Reads uname once.
     *
     * A failure here must not stop the screen from opening, exactly as in the
     * original: an unreadable kernel string degrades to "unknown", it is not
     * an error worth blocking on.
     */
    fun readInfo(): KmiInfo {
        // Deliberately the same source ExploitRunner uses for the vermagic it
        // passes to the binary. Reading uname separately here could show one
        // string while the exploit used another, and a custom vermagic is
        // passed through verbatim - so a mismatch would fail the module load
        // with nothing on screen to explain why.
        var release: String? = System.getProperty("os.version")?.trim()
        var machine: String? = System.getProperty("os.arch")?.trim()
        if (release.isNullOrEmpty() || machine.isNullOrEmpty()) {
            try {
                val uname: StructUtsname = Os.uname()
                if (release.isNullOrEmpty()) release = uname.release
                if (machine.isNullOrEmpty()) machine = uname.machine
            } catch (_: RuntimeException) {
                // Unknown metadata must not prevent showing the rest.
            }
        }

        var detectedBranch: String? = null
        var fullKmi: String? = null
        val match = KMI_PATTERN.matcher(release ?: "")
        if (match.find()) {
            // Groups 1 and 2 are mandatory in KMI_PATTERN, so a successful
            // find() guarantees they matched; Kotlin still sees them nullable.
            val kernel = match.group(1)
            val branch = match.group(2)
            val generation = match.group(3)
            if (kernel != null && branch != null) {
                detectedBranch = "$branch-$kernel"
                if (generation != null) fullKmi = "$kernel-$branch-$generation"
            }
        }
        if (detectedBranch == null) {
            detectedBranch = branchFromProps(
                systemProperty("ro.kernel.version"),
                systemProperty("ro.vendor.build.version.release"),
            )
        }

        return KmiInfo(
            manufacturerModel = Build.MANUFACTURER + " " + Build.MODEL,
            androidRelease = Build.VERSION.RELEASE ?: "",
            apiLevel = Build.VERSION.SDK_INT,
            securityPatch = Build.VERSION.SECURITY_PATCH ?: "",
            release = release,
            machine = machine,
            detectedBranch = detectedBranch,
            fullKmi = fullKmi,
        )
    }

    fun prefs(ctx: Context): SharedPreferences =
        ctx.createDeviceProtectedStorageContext()
            .getSharedPreferences(KmiPrefs.PREFS_NAME, Context.MODE_PRIVATE)

    fun readSelection(ctx: Context): KmiSelection {
        val p = prefs(ctx)
        return KmiSelection(
            branch = p.getString(KmiPrefs.SELECTION, null),
            vermagicMode = p.getString(KmiPrefs.VERMAGIC_MODE, KmiPrefs.MODE_BUILTIN)
                ?: KmiPrefs.MODE_BUILTIN,
            vermagicCustom = p.getString(KmiPrefs.VERMAGIC_CUSTOM, "") ?: "",
        )
    }

    /** Passing null for [branch] clears the override so detection is used. */
    fun setBranch(ctx: Context, branch: String?) {
        val e = prefs(ctx).edit()
        if (branch == null) e.remove(KmiPrefs.SELECTION) else e.putString(KmiPrefs.SELECTION, branch)
        e.apply()
    }

    fun setVermagic(ctx: Context, mode: String, custom: String? = null) {
        val e = prefs(ctx).edit().putString(KmiPrefs.VERMAGIC_MODE, mode)
        if (mode == KmiPrefs.MODE_CUSTOM && custom != null) {
            e.putString(KmiPrefs.VERMAGIC_CUSTOM, custom)
        }
        e.apply()
    }

    private fun releaseValue(release: String?): String =
        if (release.isNullOrBlank()) "unknown" else release.trim()

    private fun archValue(machine: String?): String {
        val arch = if (machine.isNullOrBlank()) "aarch64" else machine.trim()
        return if (arch == "arm64") "aarch64" else arch
    }

    /** The vermagic a vendor kernel reports, with the extra `vivo` token. */
    fun vivoVermagic(release: String?, machine: String?): String =
        releaseValue(release) + " SMP preempt mod_unload modversions vivo " + archValue(machine)

    /** The vermagic a stock GKI kernel reports. */
    fun standardVermagic(release: String?, machine: String?): String =
        releaseValue(release) + " SMP preempt mod_unload modversions " + archValue(machine)

    /** Some vendor kernels omit `androidN` from uname; GKI still publishes it in props. */
    fun branchFromProps(kernelVersion: String?, vendorRelease: String?): String? {
        val kver = kernelVersion?.trim().orEmpty()
        val aver = vendorRelease?.trim().orEmpty()
        if (kver.isEmpty() || aver.isEmpty()) return null
        val parts = kver.split('.')
        if (parts.size < 2 || parts[0].any { !it.isDigit() } || parts[1].any { !it.isDigit() }) return null
        val android = aver.takeWhile { it.isDigit() }
        if (android.isEmpty()) return null
        return "android$android-${parts[0]}.${parts[1]}"
    }

    private fun systemProperty(key: String): String = try {
        Class.forName("android.os.SystemProperties")
            .getMethod("get", String::class.java, String::class.java)
            .invoke(null, key, "") as? String ?: ""
    } catch (_: Throwable) {
        ""
    }

    /** The vermagic the current selection resolves to, for display. */
    fun resolvedVermagic(info: KmiInfo, sel: KmiSelection): String = when (sel.vermagicMode) {
        KmiPrefs.MODE_VIVO -> vivoVermagic(info.release, info.machine)
        KmiPrefs.MODE_ANDROID -> standardVermagic(info.release, info.machine)
        KmiPrefs.MODE_CUSTOM -> sel.vermagicCustom
        else -> ""
    }
}
