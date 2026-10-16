package df.root.script

import org.junit.Assert.*
import org.junit.Test

class ManagerRebootTest {
    private val manager = ModuleStep(kind = ModuleKind.MANAGER, managerPackage = "test.manager")
    private val env = RequirementEnvironment(rootAvailable = true, nativeAvailable = true,
        managers = setOf("test.manager"), managerBackends = mapOf("test.manager" to "ksud"))

    @Test fun resetReplacesRatherThanAppendsAndPreservesRevisionAndManagerSelection() {
        val plan = ModulePlan(steps = listOf(manager.copy(softReboot = true)), onAppOpen = true, onBoot = true,
            lastFailure = "old", revision = 8)
        val reset = ModuleDefaults.reset(plan)
        assertEquals(listOf(ModuleKind.DAEMON, ModuleKind.SHELL, ModuleKind.SHELL, ModuleKind.MANAGER), reset.steps.map { it.kind })
        assertEquals(listOf("", ScriptPresets.PARTITIONS_RO, ScriptPresets.UNLOAD_MODULE, ""), reset.steps.map { it.script })
        assertEquals(8L, reset.revision)
        assertEquals("test.manager", reset.steps.last().managerPackage)
        assertFalse(reset.steps.last().softReboot)
        assertFalse(reset.onAppOpen)
        assertFalse(reset.onBoot)
        assertEquals("", reset.lastFailure)
        assertFalse(ModuleDefaults.providers.contains(ModuleKind.SOFT_REBOOT))
    }
    @Test fun enabledManagerRebootKeepsAutomaticFlags() {
        val plan = ModulePlan(steps = listOf(manager.copy(softReboot = true)), onAppOpen = true, onBoot = true)
        assertTrue(ModulePolicy.completed(plan).onBoot)
        assertTrue(ModulePolicy.completed(plan).onAppOpen)
        assertTrue(ModulePolicy.completed(plan.copy(steps = listOf(manager.copy(softReboot = true, enabled = false)))).onBoot)
    }
    @Test fun migrationAttachesEnabledLegacyRebootAndIsIdempotent() {
        val old = ModulePlan(steps = listOf(manager, ModuleStep(kind = ModuleKind.SOFT_REBOOT)), onBoot = true, revision = 3)
        val migrated = ModuleMigration.reboots(old)
        assertEquals(listOf(manager.copy(softReboot = true)), migrated.steps)
        assertFalse(migrated.onBoot)
        assertEquals(3L, migrated.revision)
        assertEquals(migrated, ModuleMigration.reboots(migrated))
    }
    @Test fun disabledLegacyRebootStaysDisabledAndDisabledManagerIsNotEnabled() {
        val disabledReboot = ModuleMigration.reboots(ModulePlan(steps = listOf(manager, ModuleStep(kind = ModuleKind.SOFT_REBOOT, enabled = false))))
        assertFalse(disabledReboot.steps.single().softReboot)
        val disabledManager = ModuleMigration.reboots(ModulePlan(steps = listOf(manager.copy(enabled = false), ModuleStep(kind = ModuleKind.SOFT_REBOOT))))
        assertFalse(disabledManager.steps.single().enabled)
        assertFalse(disabledManager.steps.single().softReboot)
        assertTrue(disabledManager.lastFailure.contains("could not be enabled safely"))
    }
    @Test fun orphanRebootMigrationReportsLostActionAndDisarms() {
        val migrated = ModuleMigration.reboots(ModulePlan(steps = listOf(ModuleStep(kind = ModuleKind.SOFT_REBOOT)), onBoot = true))
        assertTrue(migrated.steps.isEmpty())
        assertTrue(migrated.lastFailure.isNotBlank())
        assertFalse(migrated.onBoot)
    }
    @Test fun suAloneCannotPerformRebootHandshake() {
        val issues = ModulePreflight.inspect(ModulePlan(steps = listOf(manager.copy(softReboot = true))), env)
        assertEquals(listOf(RequirementReason.DAEMON_REQUIRED), issues.map { it.reason })
    }
    @Test fun rebootManagerMustBeFinalEnabledStep() {
        val reboot = manager.copy(softReboot = true)
        val script = ModuleStep(kind = ModuleKind.SHELL, script = "id")
        val ready = env.copy(daemonAvailable = true)
        assertEquals(listOf(RequirementReason.REBOOT_FINAL_REQUIRED), ModulePreflight.inspect(
            ModulePlan(steps = listOf(reboot, script)), ready).map { it.reason })
        assertTrue(ModulePreflight.inspect(ModulePlan(steps = listOf(reboot, script.copy(enabled = false))), ready).isEmpty())
    }
    @Test fun earlierDaemonSatisfiesManagerRebootHandshake() {
        assertTrue(ModulePreflight.inspect(ModulePlan(steps = listOf(ModuleStep(kind = ModuleKind.DAEMON),
            manager.copy(softReboot = true))), env.copy(rootAvailable = false)).isEmpty())
    }
}
