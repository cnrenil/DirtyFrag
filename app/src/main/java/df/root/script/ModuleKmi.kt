package df.root.script

import android.content.Context
import df.root.kmi.Kmi

/** Resolve once for the run, so settings and execution cannot disagree. */
internal object ModuleKmi {
    private val branch = Regex("android[0-9]+-[0-9]+\\.[0-9]+")
    private val full = Regex("([0-9]+\\.[0-9]+)-(android[0-9]+)-[0-9]+")

    fun resolve(override: String, selection: String?, detected: String?): String? {
        val value = override.trim().ifEmpty { selection?.trim().orEmpty().ifEmpty { detected?.trim().orEmpty() } }
        if (branch.matches(value)) return value
        val match = full.matchEntire(value) ?: return null
        return "${match.groupValues[2]}-${match.groupValues[1]}"
    }

    fun resolve(context: Context, override: String = ""): String? =
        resolve(override, Kmi.readSelection(context).branch, Kmi.readInfo().detectedBranch)

    fun snapshot(context: Context, plan: ModulePlan): ModulePlan {
        val selection = Kmi.readSelection(context).branch
        val detected = Kmi.readInfo().detectedBranch
        return plan.copy(steps = plan.steps.map { step ->
            if (step.enabled && step.kind == ModuleKind.MANAGER)
                step.copy(kmi = resolve(step.kmi, selection, detected).orEmpty()) else step
        })
    }
}
