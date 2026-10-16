package df.root.daemon

import org.junit.Assert.*
import org.junit.Test

class DfctlProtocolTest {
    @Test fun requestIsBoundedByUtf8BytesAndRejectsLineInjection() {
        assertTrue(Dfctl.validRequest("exec", "echo ok"))
        assertFalse(Dfctl.validRequest("exec", "echo ok\nping"))
        assertFalse(Dfctl.validRequest("exec", "x\u0000y"))
        assertFalse(Dfctl.validRequest("exec", "界".repeat(1400)))
        assertFalse(Dfctl.validRequest("exec", "x".repeat(4090)))
        assertTrue(Dfctl.validRequest("exec", "x".repeat(4089)))
    }
    @Test fun parserRejectsWrongReplyVerbRatherThanAcceptingStaleStatus() {
        assertTrue(Dfctl.collect(".ok status\n".reader().buffered(), "exec", null) is Dfctl.Outcome.Unreachable)
        assertTrue(Dfctl.collect(".okay exec\n".reader().buffered(), "exec", null) is Dfctl.Outcome.Unreachable)
    }
    @Test fun parserPreservesErrorWithoutExitAndContinuation() {
        val result = Dfctl.collect("| first\n|+ second\n.err late-load not configured\n".reader().buffered(), "late-load", null) as Dfctl.Outcome.Success
        assertFalse(result.result.ok)
        assertNull(result.result.exit)
        assertEquals("not configured", result.result.error)
        assertEquals("first second", result.result.text)
    }
}
