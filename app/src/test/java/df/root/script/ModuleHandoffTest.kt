package df.root.script

import org.junit.Assert.*
import org.junit.Test

class ModuleHandoffTest {
    @Test fun builtinKindsCompileToShellRatherThanNativeVerbs() {
        assertEquals(ScriptPresets.PARTITIONS_RO, ScriptPresets.moduleScript(ModuleKind.PARTITIONS_RO, "", "/m"))
        assertEquals(ScriptPresets.DISABLE_KSU_MODULES, ScriptPresets.moduleScript(ModuleKind.DISABLE_KSU_MODULES, "", "/m"))
        assertEquals(ScriptPresets.UNLOAD_MODULE, ScriptPresets.moduleScript(ModuleKind.UNLOAD_MODULE, "", "/m"))
        assertEquals("rm -f /dev/df* '/data/markers'/*", ScriptPresets.moduleScript(ModuleKind.CLEAN_MARKERS, "", "/data/markers"))
        assertEquals("echo hi", ScriptPresets.moduleScript(ModuleKind.SHELL, "echo hi", "/m"))
        val partitions = ModuleDefaults.step(ModuleKind.PARTITIONS_RO)
        val disable = ModuleDefaults.step(ModuleKind.DISABLE_KSU_MODULES)
        val unload = ModuleDefaults.step(ModuleKind.UNLOAD_MODULE)
        assertEquals(ModuleKind.SHELL, partitions.kind)
        assertEquals(ScriptPresets.PARTITIONS_RO, partitions.script)
        assertEquals(ModuleKind.SHELL, disable.kind)
        assertEquals(ScriptPresets.DISABLE_KSU_MODULES, disable.script)
        assertEquals(ModuleKind.SHELL, unload.kind)
        assertEquals(ScriptPresets.UNLOAD_MODULE, unload.script)
        assertFalse(ModuleDefaults.providers.contains(ModuleKind.CLEAN_MARKERS))
    }

    @Test fun planRoundTripsExecLateLoadAndSoftReboot() {
        val actions = listOf(
            HandoffAction("exec", "one", path = "/data/user_de/0/df.root/files/runs/one.sh"),
            HandoffAction("lateload", "two"),
            HandoffAction("lateload", "three", softReboot = true),
            HandoffAction("soft-reboot", "four"),
        )
        val text = ModuleHandoff.encode(actions)
        assertTrue(text.startsWith(ModuleHandoff.HEADER))
        assertEquals(actions, ModuleHandoff.parse(text))
        assertFalse(text.contains("fifo"))
        assertFalse(text.contains("exec echo"))
    }

    @Test fun parseStatusReadsDaemonProgress() {
        val status = ModuleHandoff.parseStatus("id=abc\nstate=accepted\nexit=0\nline=cleanup completed\n")
        assertEquals("abc", status.id)
        assertEquals("accepted", status.state)
        assertEquals(0, status.exit)
        assertTrue(status.ok)
        assertTrue(status.finished)
        assertFalse(ModuleHandoff.parseStatus("id=x\nstate=fail\nexit=7\n").ok)
    }

    @Test fun managerSidecarCarriesLibraryAndRebootFlag() {
        val text = ModuleHandoff.managerText("me.weishu.kernelsu", "ksud", "/data/app/libksud.so", "android16-6.12", true)
        assertTrue(text.contains("package=me.weishu.kernelsu"))
        assertTrue(text.contains("library=/data/app/libksud.so"))
        assertTrue(text.contains("soft_reboot=1"))
        assertTrue(text.contains("kmi=android16-6.12"))
    }

    @Test(expected = IllegalStateException::class)
    fun daemonAndManagerAreNotShellScripts() {
        ScriptPresets.moduleScript(ModuleKind.DAEMON, "", "/m")
    }

    @Test fun bootstrapSucceedsWhenReadyWasCleanedAfterAcceptedPlan() {
        val accepted = PlanStatus(id = "mgr", state = "accepted", exit = 0, line = "cleanup")
        assertTrue(ModuleHandoff.bootstrapSucceeded(rc = 1, daemonAlive = false, ready = false, status = accepted))
        assertTrue(ModuleHandoff.bootstrapSucceeded(rc = 0, daemonAlive = false, ready = false, status = null))
        assertTrue(ModuleHandoff.bootstrapSucceeded(rc = 2, daemonAlive = true, ready = false, status = null))
        assertFalse(ModuleHandoff.bootstrapSucceeded(rc = 1, daemonAlive = false, ready = false, status = null))
        assertFalse(ModuleHandoff.bootstrapSucceeded(rc = 1, daemonAlive = false, ready = false,
            status = PlanStatus(id = "x", state = "fail", exit = 1)))
    }
}
