package df.root.ui

import df.root.kmi.Kmi
import df.root.kmi.KmiInfo
import df.root.kmi.KmiSelection
import org.junit.Assert.*
import org.junit.Test

class MainUiStateTest {
    @Test fun executionUsesPlanInsteadOfGlobalManagerOrRootGate() {
        assertTrue(MainUiState().canRun)
        assertTrue(MainUiState(rooted = true).canRun)
        assertTrue(MainUiState(failedRun = true).canRun)
        assertFalse(MainUiState(running = true).canRun)
    }
    @Test fun confirmationDoesNotOverrideConcurrentRunInterlock() {
        assertTrue(MainUiState(runArmed = true).canRun)
        assertFalse(MainUiState(runArmed = true, running = true).canRun)
    }
    @Test fun kernelSelectionReflectsCustomAndUnknownBranchWithoutReplacingInput() {
        val info = KmiInfo("Test phone", "16", 36, "2026-01-01", "6.6.1-android15-8", "arm64", "android15-6.6", "6.6-android15-8")
        val custom = KmiSelection("android99-9.9", "custom", "vendor custom value")
        assertEquals("vendor custom value", Kmi.resolvedVermagic(info, custom))
        assertEquals("android99-9.9", custom.branch)
        assertEquals("", Kmi.resolvedVermagic(info, custom.copy(vermagicMode = "builtin")))
        assertEquals("6.6.1-android15-8 SMP preempt mod_unload modversions aarch64", Kmi.resolvedVermagic(info, custom.copy(vermagicMode = "android")))
    }
    @Test fun branchFromPropsWhenUnameOmitsAndroid() {
        assertEquals("android12-5.10", Kmi.branchFromProps("5.10", "12"))
        assertEquals("android12-5.10", Kmi.branchFromProps("5.10.198", "12"))
        assertNull(Kmi.branchFromProps("5.10", null))
        assertNull(Kmi.branchFromProps(null, "12"))
    }
}
