package df.root.script

import android.content.Context

enum class ScriptTrigger(val key: String) { APP_OPEN("app_open"), BOOT("boot") }
data class ScriptConfig(val enabled: Boolean = false, val text: String = "")

object ScriptStore {
    const val MAX_BYTES = 32768
}

object ScriptPresets {
    val ids = listOf("diagnostics", "clean_markers", "unload_module", "cleanup")

    /** Markers now live in the app's data directory, so cleanup targets it. */
    fun cleanMarkers(context: Context) = cleanMarkers(RuntimeFiles.markers(context).absolutePath)

    fun cleanMarkers(markersDir: String) = "rm -f /dev/df* " + quote(markersDir) + "/*"

    const val CLEAN_MARKERS = "rm -f /dev/df* '/data/user_de/0/df.root/markers'/*"

    const val UNLOAD_MODULE = "rmmod dfroot 2>/dev/null || true"

    const val DISABLE_KSU_MODULES =
        "for module in /data/adb/modules/*; do\n  [ -d \"\$module\" ] || continue\n  touch \"\$module/disable\" || true\ndone"

    const val PARTITIONS_RO =
        "for partition in /dev/block/by-name/system /dev/block/by-name/vendor /dev/block/by-name/product; do\n  [ -e \"\$partition\" ] || continue\n  blockdev --setro \"\$partition\" || true\ndone"

    /** Built-in module kinds become shell; daemon bootstrap and late-load stay native. */
    fun moduleScript(kind: ModuleKind, script: String, markersDir: String): String = when (kind) {
        ModuleKind.SHELL -> script
        ModuleKind.CLEAN_MARKERS -> cleanMarkers(markersDir)
        ModuleKind.UNLOAD_MODULE -> UNLOAD_MODULE
        ModuleKind.DISABLE_KSU_MODULES -> DISABLE_KSU_MODULES
        ModuleKind.PARTITIONS_RO -> PARTITIONS_RO
        else -> error("Module does not use a shell script")
    }
    fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"

    /** A real command (not a preset) used only by the diagnostics shortcut. */
    fun diagnostics(context: Context) = buildString {
        append("id\n")
        append("uname -a\n")
        append("getenforce\n")
        append("ls -l ").append(quote(RuntimeFiles.markers(context).absolutePath)).append(" 2>/dev/null\n")
        append("ls -l ").append(quote(RuntimeFiles.logs(context).absolutePath)).append(" 2>/dev/null\n")
        append("cat /proc/modules | grep dfroot\n")
        append("ls -l /data/adb/ksud /data/adb/apd 2>/dev/null")
    }

    /**
     * late-load run straight from the SU manager's own native library, which is
     * where the shipped binary already is. No copying.
     */
    fun lateLoad(context: Context, backend: String, manager: String?, kmi: String?): String {
        require(backend == "apd" || backend == "ksud") { "Unsupported backend: $backend" }
        require(!manager.isNullOrBlank()) { "Select an SU manager first" }
        return quote(ManagerCatalog.libraryPath(context, backend, manager)) + " late-load --package-name " + quote(manager) +
            (kmi?.takeIf { it.isNotBlank() }?.let { " --kmi " + quote(it) } ?: "")
    }

    fun command(context: Context, id: String): String {
        val selected = ModuleStore.read(context).steps.firstOrNull {
            it.kind == ModuleKind.MANAGER && it.enabled && it.managerPackage.isNotBlank()
        }
        val managerPackage = selected?.managerPackage ?: ManagerCatalog.selected(context)
        val backend = selected?.backend?.takeIf { it.isNotBlank() }
            ?: managerPackage?.let { ManagerCatalog.backendFor(context, it) }
            ?: "ksud"
        val kmi = selected?.kmi ?: context.createDeviceProtectedStorageContext()
            .getSharedPreferences("dfroot", Context.MODE_PRIVATE).getString("kmi", null)
        return when (id) {
            "diagnostics" -> diagnostics(context)
            "clean_markers" -> cleanMarkers(context)
            "unload_module" -> UNLOAD_MODULE
            "cleanup" -> buildString {
                append(UNLOAD_MODULE).append('\n')
                append("unload_rc=\$?\n")
                append(cleanMarkers(context)).append('\n')
                append("clean_rc=\$?\n")
                append("printf 'rmmod=%s cleanup=%s\\n' \"\$unload_rc\" \"\$clean_rc\"\n")
                append("[ \"\$unload_rc\" -eq 0 ] && [ \"\$clean_rc\" -eq 0 ]")
            }
            "late_load" -> managerPackage?.let { lateLoad(context, backend, it, kmi) }
                ?: error("Select an SU manager first")
            else -> error("Unknown preset: $id")
        }
    }
}
