package df.root.shell

import org.junit.Assert.*
import org.junit.Test

class ProcessExecutorTest {
    @Test fun blockedOutputReaderCannotBypassTimeout() {
        val start = System.nanoTime()
        val result = ProcessExecutor.run(ProcessBuilder("/bin/sh", "-c", "printf before; exec sleep 10"), 100)
        assertEquals(124, result.exit)
        assertTrue(result.output.contains("before"))
        assertTrue((System.nanoTime() - start) / 1_000_000 < 3000)
    }
    @Test fun preservesExitAndBoundsOutput() {
        val result = ProcessExecutor.run(ProcessBuilder("/bin/sh", "-c", "printf abcdefghijk; exit 7"), 2000, 5)
        assertEquals(7, result.exit)
        assertEquals("ghijk", result.output)
    }
}
