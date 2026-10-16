package df.root.script

import org.junit.Assert.*
import org.junit.Test

class ModuleAuditTest {
    @Test fun matchingRevisionCanBeSaved() { ModuleStore.checkRevision(4, 4) }
    @Test(expected = IllegalStateException::class) fun staleSnapshotCannotRestoreFailedRunFlags() {
        ModuleStore.checkRevision(3, 4)
    }
    @Test fun suDoesNotReplaceMissingDaemonBootstrapAssets() {
        val plan = ModulePlan(steps = listOf(ModuleStep(kind = ModuleKind.DAEMON)))
        val issues = ModulePreflight.inspect(plan, RequirementEnvironment(rootAvailable = true, nativeAvailable = false, managers = emptySet()))
        assertEquals(RequirementReason.NATIVE_MISSING, issues.single().reason)
    }
    @Test fun liveDaemonCanSkipMissingBootstrapAssets() {
        val plan = ModulePlan(steps = listOf(ModuleStep(kind = ModuleKind.DAEMON)))
        assertTrue(ModulePreflight.inspect(plan, RequirementEnvironment(rootAvailable = true, nativeAvailable = false,
            managers = emptySet(), daemonAvailable = true)).isEmpty())
    }
    @Test fun emptyAutomaticPlanFailsAndDisarmsRatherThanReentering() {
        val plan = ModulePlan(onBoot = true, onAppOpen = true)
        assertEquals(RequirementReason.EMPTY_PLAN, ModulePreflight.inspect(plan,
            RequirementEnvironment(rootAvailable = false, nativeAvailable = true, managers = emptySet())).single().reason)
        val failed = ModulePolicy.disarmed(plan, "No enabled modules")
        assertFalse(failed.onBoot)
        assertFalse(failed.onAppOpen)
    }
}
