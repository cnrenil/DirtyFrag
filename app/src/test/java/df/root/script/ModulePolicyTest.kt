package df.root.script

import org.junit.Assert.*
import org.junit.Test

class ModulePolicyTest {
    private val first = ModuleStep(id = "first", kind = ModuleKind.SHELL, script = "echo first")
    private val second = ModuleStep(id = "second", kind = ModuleKind.MANAGER, managerPackage = "test.manager")
    private val plan = ModulePlan(steps = listOf(first, second), onAppOpen = true, onBoot = true)

    @Test fun beginKeepsAutomaticFlagsArmed() {
        val started = ModulePolicy.begin(plan, "token")
        assertTrue(started.onAppOpen)
        assertTrue(started.onBoot)
        assertEquals("token", started.inFlight)
        assertEquals("", started.lastFailure)
        assertEquals(plan.steps, started.steps)
    }
    @Test fun onlySuccessRestoresOptInFlagsAndOrder() {
        val completed = ModulePolicy.completed(plan.copy(inFlight = "token", lastFailure = "old"))
        assertTrue(completed.onAppOpen)
        assertTrue(completed.onBoot)
        assertEquals(listOf("first", "second"), completed.steps.map { it.id })
        assertEquals("", completed.lastFailure)
        assertEquals("", completed.inFlight)
    }
    @Test fun softRebootKeepsBootTriggerDuringTransactionAndRecovery() {
        val rebootPlan = plan.copy(steps = listOf(second.copy(softReboot = true)))
        val started = ModulePolicy.begin(rebootPlan, "token")
        assertTrue(started.onBoot)
        val recovered = ModulePolicy.recover(started, "")
        assertTrue(recovered.onBoot)
        assertEquals("", recovered.inFlight)
        assertEquals("", recovered.lastFailure)
    }
    @Test fun failureKeepsAutomaticExecutionWithoutDeletingSteps() {
        val failed = ModulePolicy.failed(plan, "exit=42")
        assertTrue(failed.onAppOpen)
        assertTrue(failed.onBoot)
        assertEquals("exit=42", failed.lastFailure)
        assertEquals(plan.steps, failed.steps)
    }
    @Test fun interruptedExecutionKeepsAutomaticFlags() {
        val running = ModulePolicy.begin(plan, "old-process")
        assertEquals(running, ModulePolicy.recover(running, "old-process"))
        val recovered = ModulePolicy.recover(running, "")
        assertTrue(recovered.onBoot)
        assertTrue(recovered.onAppOpen)
        assertTrue(recovered.lastFailure.contains("interrupted"))
        assertEquals("", recovered.inFlight)
    }
    @Test fun preflightDisarmsAutomaticExecution() {
        val disarmed = ModulePolicy.disarmed(plan, "No enabled modules")
        assertFalse(disarmed.onAppOpen)
        assertFalse(disarmed.onBoot)
        assertEquals("No enabled modules", disarmed.lastFailure)
    }
    @Test fun rebootProviderKeepsAutomaticExecutionArmed() {
        val reboot = second.copy(softReboot = true)
        val completed = ModulePolicy.completed(plan.copy(steps = listOf(first, reboot)))
        assertTrue(completed.onAppOpen)
        assertTrue(completed.onBoot)
        assertEquals("", completed.lastFailure)
        assertTrue(ModulePolicy.completed(plan.copy(steps = listOf(first, reboot.copy(enabled = false)))).onBoot)
    }
    @Test(expected = IllegalArgumentException::class) fun rejectDuplicateSteps() {
        ModuleStore.validate(plan.copy(steps = listOf(first, first)))
    }
    @Test(expected = IllegalArgumentException::class) fun rejectOversizedUtf8Scripts() {
        ModuleStore.validate(ModulePlan(steps = listOf(first.copy(script = "界".repeat(11000)))))
    }
    @Test fun cleanupAndLegacyProvidersRemainOptionalOrderedModules() {
        // Precondition checks are pure and cover the ordering rules that the
        // mutable tree cannot: an env with root but no manager must refuse a
        // soft reboot, and the daemon step must satisfy root for later steps.
        val env = RequirementEnvironment(rootAvailable = true, nativeAvailable = true, managers = emptySet())
        val issues = ModulePreflight.inspect(ModulePlan(steps = listOf(ModuleStep(kind = ModuleKind.MANAGER, managerPackage = "test.manager", softReboot = true))), env)
        assertTrue(issues.any { it.reason == RequirementReason.DAEMON_REQUIRED })
        assertTrue(ModulePreflight.inspect(ModulePlan(steps = listOf(ModuleStep(kind = ModuleKind.SHELL, script = "id"))), env).isEmpty())
        assertEquals(listOf(ModuleKind.DAEMON, ModuleKind.SHELL, ModuleKind.MANAGER), ModuleDefaults.legacy().map { it.kind })
        assertEquals(ScriptPresets.PARTITIONS_RO, ModuleDefaults.legacy()[1].script)
        assertNotEquals(ModuleDefaults.legacy().first().id, ModuleDefaults.legacy().first().id)
    }
    @Test fun daemonStepSatisfiesRootForLaterSteps() {
        val env = RequirementEnvironment(rootAvailable = false, nativeAvailable = true, managers = emptySet())
        val plan = ModulePlan(steps = listOf(ModuleStep(kind = ModuleKind.DAEMON), ModuleStep(kind = ModuleKind.MANAGER, managerPackage = "me.weishu.kernelsu")))
        // The daemon step cannot help an uninstalled manager, but it must not add
        // its own ROOT_REQUIRED for the manager step that follows it.
        assertFalse(ModulePreflight.inspect(plan, env).any { it.reason == RequirementReason.ROOT_REQUIRED })
        // Without the daemon first, the manager step has no privilege to load under.
        val orphan = ModulePlan(steps = listOf(ModuleStep(kind = ModuleKind.MANAGER, managerPackage = "me.weishu.kernelsu")))
        assertTrue(ModulePreflight.inspect(orphan, env).any { it.reason == RequirementReason.ROOT_REQUIRED })
    }
    @Test fun managerStepMustBeInstalledAndBackedByItsLibrary() {
        val installed = RequirementEnvironment(rootAvailable = true, nativeAvailable = true,
            managers = setOf("me.weishu.kernelsu"), managerBackends = mapOf("me.weishu.kernelsu" to "ksud"))
        val ok = ModulePlan(steps = listOf(ModuleStep(kind = ModuleKind.MANAGER, managerPackage = "me.weishu.kernelsu")))
        assertTrue(ModulePreflight.inspect(ok, installed).isEmpty())
        val gone = ModulePlan(steps = listOf(ModuleStep(kind = ModuleKind.MANAGER, managerPackage = "me.bmax.apatch")))
        assertEquals(listOf(RequirementReason.MANAGER_MISSING), ModulePreflight.inspect(gone, installed).map { it.reason })
        val noLibrary = installed.copy(backendLibraryPresent = false)
        assertEquals(listOf(RequirementReason.MANAGER_LIBRARY_MISSING), ModulePreflight.inspect(ok, noLibrary).map { it.reason })
    }
    @Test fun emptyPlanIsReportedBeforeItIsExecuted() {
        val env = RequirementEnvironment(rootAvailable = true, nativeAvailable = true, managers = emptySet())
        assertEquals(listOf(RequirementReason.EMPTY_PLAN),
            ModulePreflight.inspect(ModulePlan(), env).map { it.reason })
    }
    @Test fun decodeDropsStandaloneSoftRebootKind() {
        assertNull(ModuleStore.parseKind("SOFT_REBOOT"))
        assertNull(ModuleStore.parseKind("not-a-kind"))
        assertEquals(ModuleKind.MANAGER, ModuleStore.parseKind("MANAGER"))
    }
}
