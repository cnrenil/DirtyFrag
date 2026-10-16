package df.root.script

import df.root.shell.ShellResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ModuleSequenceTest {
    @Test fun providersExecuteInSavedOrderSkippingDisabledEntries() = runBlocking {
        val steps = listOf(ModuleStep(id = "one", kind = ModuleKind.DAEMON),
            ModuleStep(id = "skip", enabled = false), ModuleStep(id = "two", kind = ModuleKind.MANAGER))
        val calls = mutableListOf<String>()
        val result = ModuleSequence.execute(steps) { calls += it.id; ShellResult(0, it.id) }
        assertTrue(result.ok)
        assertEquals(listOf("one", "two"), calls)
    }
    @Test fun firstFailureDoesNotStopRemainingProviders() = runBlocking {
        val calls = mutableListOf<String>()
        val result = ModuleSequence.execute(listOf(ModuleStep(id = "one"), ModuleStep(id = "two"), ModuleStep(id = "three"))) {
            calls += it.id
            ShellResult(if (it.id == "two") 42 else 0, it.id)
        }
        assertTrue(result.ok)
        assertEquals("two", result.output)
        assertEquals(listOf("one", "two", "three"), calls)
    }
    @Test fun providerExceptionDoesNotRunLaterSteps() = runBlocking {
        val calls = mutableListOf<String>()
        try {
            ModuleSequence.execute(listOf(ModuleStep(id = "one"), ModuleStep(id = "two"))) {
                calls += it.id
                error("provider failed")
            }
            fail("exception expected")
        } catch (_: IllegalStateException) { assertEquals(listOf("one"), calls) }
    }
}
