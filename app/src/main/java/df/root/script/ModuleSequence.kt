package df.root.script

import df.root.shell.ShellResult

/** Disabled entries are skipped. A failed step is recorded but does not abort the rest. */
internal object ModuleSequence {
    suspend fun execute(steps: List<ModuleStep>, provider: suspend (ModuleStep) -> ShellResult): ShellResult {
        var firstFailure: ShellResult? = null
        for (step in steps) {
            if (!step.enabled) continue
            val result = provider(step)
            if (!result.ok && firstFailure == null) firstFailure = result
        }
        return ShellResult(0, firstFailure?.output.orEmpty())
    }
}
