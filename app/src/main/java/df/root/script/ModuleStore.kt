package df.root.script

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

// SOFT_REBOOT is deprecated: retained only to decode and migrate old plans.
enum class ModuleKind { DAEMON, SHELL, MANAGER, DISABLE_KSU_MODULES, PARTITIONS_RO, CLEAN_MARKERS, UNLOAD_MODULE, SOFT_REBOOT }

/**
 * One entry in the plan.
 *
 * [backend] is a cache of what the manager shipped when the step was saved. It
 * is never chosen by the user: ksud and apd are bound to the manager package
 * that provides them, so the manager selection *is* the backend selection. The
 * field survives only so a plan saved before a manager update still says which
 * library to expect.
 */
data class ModuleStep(
    val id: String = UUID.randomUUID().toString(), val kind: ModuleKind = ModuleKind.SHELL,
    val enabled: Boolean = true, val name: String = "", val script: String = "",
    val managerPackage: String = "", val backend: String = "", val kmi: String = "", val softReboot: Boolean = false,
)
data class ModulePlan(
    val steps: List<ModuleStep> = emptyList(), val onAppOpen: Boolean = false, val onBoot: Boolean = false,
    val lastFailure: String = "", val inFlight: String = "", val revision: Long = 0,
)

/** The complete plan is committed atomically. Execution always uses an immutable snapshot. */
object ModuleStore {
    @Volatile internal var activeToken: String = ""
    private fun prefs(context: Context) = context.createDeviceProtectedStorageContext()
        .getSharedPreferences("module_plan", Context.MODE_PRIVATE)
    fun directory(context: Context) = File(context.createDeviceProtectedStorageContext().filesDir, "modules")

    /** Where generated per-step shell files are written before execution. */
    fun scripts(context: Context) = File(context.createDeviceProtectedStorageContext().filesDir, "runs")

    @Synchronized fun read(context: Context): ModulePlan {
        val prefs = prefs(context)
        if (!prefs.contains("plan")) {
            val old = context.createDeviceProtectedStorageContext().getSharedPreferences("startup_scripts", Context.MODE_PRIVATE)
            val imported = ScriptTrigger.entries.mapNotNull { trigger ->
                old.getString(trigger.key + "_text", null)?.takeIf { it.isNotBlank() }?.let {
                    ModuleStep(kind = ModuleKind.SHELL, enabled = false, name = "Imported: ${trigger.key}", script = it)
                }
            }
            val legacy = context.createDeviceProtectedStorageContext().getSharedPreferences("dfroot", Context.MODE_PRIVATE)
            val manager = legacy.getString("su_manager", "") ?: ""
            val initial = ModulePlan(steps = ModuleDefaults.legacy().map { step ->
                if (step.kind == ModuleKind.MANAGER && manager.isNotBlank()) step.copy(managerPackage = manager,
                    backend = legacy.getString("su_backend", "")?.takeIf { it != "auto" } ?: "") else step
            } + imported)
            persist(context, initial)
        }
        var plan = decode(JSONObject(prefs.getString("plan", "{}")!!))
        if (activeToken.isEmpty() && plan.steps.any { it.kind == ModuleKind.SOFT_REBOOT }) {
            persist(context, ModuleMigration.reboots(plan))
            plan = decode(JSONObject(prefs.getString("plan", "{}")!!))
        }
        if (plan.inFlight.isNotEmpty() && plan.inFlight != activeToken) {
            val recovered = ModulePolicy.recover(plan, activeToken)
            persist(context, recovered)
            return decode(JSONObject(prefs.getString("plan", "{}")!!))
        }
        return plan
    }

    @Synchronized fun save(context: Context, plan: ModulePlan) {
        check(activeToken.isEmpty()) { "Modules are running" }
        validate(plan)
        checkRevision(plan.revision, storedRevision(context))
        persist(context, plan.copy(inFlight = ""))
    }
    @Synchronized internal fun persist(context: Context, plan: ModulePlan) {
        validate(plan)
        val current = storedRevision(context)
        check(current < Long.MAX_VALUE) { "Module revision exhausted" }
        val committed = plan.copy(revision = current + 1)
        check(prefs(context).edit().putString("plan", encode(committed).toString()).commit()) { "Unable to save modules" }
    }
    private fun storedRevision(context: Context): Long = prefs(context).getString("plan", null)
        ?.let { JSONObject(it).optLong("revision", 0) } ?: 0
    internal fun checkRevision(expected: Long, actual: Long) {
        check(expected == actual) { "Module plan changed; reload before saving" }
    }
    fun validate(plan: ModulePlan) {
        require(plan.revision >= 0) { "Invalid module revision" }
        require(plan.steps.size <= 100) { "At most 100 modules" }
        require(plan.steps.map { it.id }.toSet().size == plan.steps.size) { "Duplicate module IDs" }
        plan.steps.forEach {
            require(it.id.matches(Regex("[A-Za-z0-9_-]{1,80}")))
            require('\u0000' !in it.script && it.script.toByteArray(Charsets.UTF_8).size <= 32768) { "Script exceeds 32 KiB or contains NUL" }
            require(it.backend in listOf("", "apd", "ksud")) { "Unknown backend: ${it.backend}" }
            require(it.name.length <= 256 && it.managerPackage.length <= 256 && it.kmi.length <= 256)
            require(it.kmi.none { ch -> ch == '\u0000' || ch == '\n' || ch == '\r' })
        }
    }
    internal fun encode(plan: ModulePlan) = JSONObject().put("version", 1).put("revision", plan.revision)
        .put("onAppOpen", plan.onAppOpen).put("onBoot", plan.onBoot)
        .put("lastFailure", plan.lastFailure).put("inFlight", plan.inFlight)
        .put("steps", JSONArray().apply { plan.steps.forEach { step -> put(JSONObject()
            .put("id", step.id).put("kind", step.kind.name).put("enabled", step.enabled).put("name", step.name)
            .put("script", step.script).put("managerPackage", step.managerPackage).put("backend", step.backend).put("kmi", step.kmi).put("softReboot", step.softReboot)) } })
    internal fun decode(json: JSONObject): ModulePlan {
        require(json.optInt("version", 1) == 1) { "Unsupported module plan version" }
        val array = json.optJSONArray("steps") ?: JSONArray()
        return ModulePlan(steps = (0 until array.length()).map { i -> array.getJSONObject(i).let {
            ModuleStep(id = it.getString("id"), kind = ModuleKind.valueOf(it.getString("kind")),
                enabled = it.optBoolean("enabled", true), name = it.optString("name"), script = it.optString("script"),
                managerPackage = it.optString("managerPackage"), backend = it.optString("backend").takeIf { b -> b != "auto" } ?: "", kmi = it.optString("kmi"), softReboot = it.optBoolean("softReboot", false))
        } }, onAppOpen = json.optBoolean("onAppOpen"), onBoot = json.optBoolean("onBoot"),
            lastFailure = json.optString("lastFailure"), inFlight = json.optString("inFlight"), revision = json.optLong("revision", 0)).also(::validate)
    }
}

object ModuleDefaults {
    val shellPresetKinds = setOf(
        ModuleKind.PARTITIONS_RO, ModuleKind.DISABLE_KSU_MODULES, ModuleKind.CLEAN_MARKERS, ModuleKind.UNLOAD_MODULE,
    )

    fun presetScript(kind: ModuleKind): String? = when (kind) {
        ModuleKind.PARTITIONS_RO -> ScriptPresets.PARTITIONS_RO
        ModuleKind.DISABLE_KSU_MODULES -> ScriptPresets.DISABLE_KSU_MODULES
        ModuleKind.CLEAN_MARKERS -> ScriptPresets.CLEAN_MARKERS
        ModuleKind.UNLOAD_MODULE -> ScriptPresets.UNLOAD_MODULE
        else -> null
    }

    fun fallbackName(kind: ModuleKind) = when (kind) {
        ModuleKind.PARTITIONS_RO -> "Protect read-only partitions"
        ModuleKind.DISABLE_KSU_MODULES -> "Disable KernelSU modules"
        ModuleKind.CLEAN_MARKERS -> "Clean run markers"
        ModuleKind.UNLOAD_MODULE -> "Unload dfroot module"
        else -> ""
    }

    /** Built-in actions other than daemon and late-load are pre-filled SHELL steps. */
    fun step(kind: ModuleKind, title: String = ""): ModuleStep {
        val script = presetScript(kind) ?: return ModuleStep(kind = kind)
        return ModuleStep(kind = ModuleKind.SHELL, name = title.ifBlank { fallbackName(kind) }, script = script)
    }

    fun legacy(titles: Map<ModuleKind, String> = emptyMap()) =
        listOf(ModuleKind.DAEMON, ModuleKind.PARTITIONS_RO, ModuleKind.UNLOAD_MODULE, ModuleKind.MANAGER)
            .map { step(it, titles[it].orEmpty()) }

    val providers = ModuleKind.entries.filter { it != ModuleKind.SOFT_REBOOT }
    fun reset(plan: ModulePlan, titles: Map<ModuleKind, String> = emptyMap()): ModulePlan {
        val selected = plan.steps.firstOrNull { it.enabled && it.kind == ModuleKind.MANAGER }
        val steps = legacy(titles).map { step ->
            if (step.kind == ModuleKind.MANAGER && selected != null) step.copy(
                managerPackage = selected.managerPackage, backend = selected.backend, kmi = selected.kmi) else step
        }
        return plan.copy(steps = steps, onAppOpen = false, onBoot = false, lastFailure = "", inFlight = "")
    }
}

internal object ModuleMigration {
    fun reboots(plan: ModulePlan): ModulePlan {
        if (plan.steps.none { it.kind == ModuleKind.SOFT_REBOOT }) return plan
        val migrated = mutableListOf<ModuleStep>()
        val warnings = mutableListOf<String>()
        plan.steps.forEachIndexed { index, step ->
            if (step.kind != ModuleKind.SOFT_REBOOT) migrated += step
            else if (step.enabled) {
                val managerIndex = migrated.indexOfLast { it.kind == ModuleKind.MANAGER }
                if (managerIndex < 0 || !migrated[managerIndex].enabled)
                    warnings += "Legacy soft reboot at step ${index + 1} could not be enabled safely: no preceding enabled manager. Review manager settings."
                else migrated[managerIndex] = migrated[managerIndex].copy(softReboot = true)
            }
        }
        val note = (listOf(plan.lastFailure).filter { it.isNotBlank() } + warnings).joinToString("\n")
        return plan.copy(steps = migrated, onAppOpen = false, onBoot = false, inFlight = "", lastFailure = note)
    }
}

data class ManagerApp(val packageName: String, val label: String, val backends: List<String>) {
    /** The backend this manager is bound to. ksud/apd ship in pairs; picking one
     *  independently of the manager is what produced "manager does not provide
     *  libapd.so" after the user had already chosen it. */
    val backend: String get() = backends.first()
}

object ManagerCatalog {
    fun scan(context: Context): List<ManagerApp> = context.packageManager.getInstalledApplications(0).mapNotNull { app ->
        val dir = app.nativeLibraryDir ?: return@mapNotNull null
        val backends = order.filter { File(dir, "lib$it.so").isFile }
        if (backends.isEmpty()) null else ManagerApp(app.packageName, context.packageManager.getApplicationLabel(app).toString(), backends)
    }.sortedBy { it.label.lowercase() }

    private val order = listOf("ksud", "apd")

    /** Absolute path of a manager's late-load library, or a clear error. */
    fun libraryPath(context: Context, backend: String, packageName: String): String {
        require(backend == "ksud" || backend == "apd") { "Unsupported backend: $backend" }
        val file = File(libraryDirectory(context, packageName), "lib$backend.so")
        require(file.isFile) { "$packageName does not ship lib$backend.so" }
        return file.absolutePath
    }

    /** Root of a manager's native libraries. */
    fun libraryDirectory(context: Context, packageName: String): String {
        val info = context.packageManager.getApplicationInfo(packageName, 0)
        return info.nativeLibraryDir ?: error("$packageName has no native library directory")
    }

    /** The manager the user last selected, or one derivable from the plan. */
    fun selected(context: Context): String? = ModuleStore.read(context).steps
        .firstOrNull { it.kind == ModuleKind.MANAGER && it.managerPackage.isNotBlank() }?.managerPackage
        ?: context.createDeviceProtectedStorageContext()
            .getSharedPreferences("dfroot", Context.MODE_PRIVATE).getString("su_manager", null)

    fun backendFor(context: Context, packageName: String): String? =
        scan(context).firstOrNull { it.packageName == packageName }?.backend
}
