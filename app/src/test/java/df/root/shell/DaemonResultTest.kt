package df.root.shell

import df.root.daemon.Dfctl
import org.junit.Assert.*
import org.junit.Test

class DaemonResultTest {
    @Test fun refusalWithoutExitCodeIsFailureAndPreservesReason() {
        val result = Shell.daemonResult(Dfctl.Result(emptyList(), false, null, "late-load is not configured"))
        assertEquals(1, result.exit)
        assertEquals("late-load is not configured", result.output)
    }
    @Test fun explicitErrorCannotBecomeSuccessEvenWithZeroExit() {
        assertFalse(Shell.daemonResult(Dfctl.Result(emptyList(), false, 0, "refused")).ok)
    }
    @Test fun normalExitAndBodyArePreserved() {
        assertEquals(ShellResult(42, "output\nexit=42"), Shell.daemonResult(Dfctl.Result(listOf("output"), false, 42, "exit=42")))
        assertEquals(ShellResult(0, "ok"), Shell.daemonResult(Dfctl.Result(listOf("ok"), true, null, null)))
    }
}
