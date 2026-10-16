package df.root.script

/** Pure transitions shared by crash recovery and the executor. */
internal object ModulePolicy {
    fun begin(plan: ModulePlan, token: String): ModulePlan =
        plan.copy(inFlight = token, lastFailure = "")
    /** Step/runtime failure: keep auto-run so boot and app-open still repair. */
    fun failed(plan: ModulePlan, reason: String) = plan.copy(inFlight = "", lastFailure = reason)
    /** Preconditions that would loop forever on every launch. */
    fun disarmed(plan: ModulePlan, reason: String) = plan.copy(onAppOpen = false, onBoot = false,
        inFlight = "", lastFailure = reason)
    fun completed(plan: ModulePlan) = plan.copy(inFlight = "", lastFailure = "")
    fun recover(plan: ModulePlan, activeToken: String): ModulePlan {
        if (plan.inFlight.isEmpty() || plan.inFlight == activeToken) return plan
        val rebootAccepted = plan.steps.any { it.enabled && it.kind == ModuleKind.MANAGER && it.softReboot }
        return if (rebootAccepted) completed(plan)
        else plan.copy(inFlight = "", lastFailure = "Previous execution was interrupted")
    }
}
