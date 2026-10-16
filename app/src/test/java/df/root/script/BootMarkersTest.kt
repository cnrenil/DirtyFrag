package df.root.script

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class BootMarkersTest {
    @Test fun sameBootRequiresNonEmptyExactMatch() {
        assertTrue(BootMarkers.sameBoot("abc-def", "abc-def"))
        assertTrue(BootMarkers.sameBoot("abc-def\n", "abc-def"))
        assertTrue(BootMarkers.sameBoot("abc-def\nextra payload", "abc-def"))
        assertEquals("abc-def", BootMarkers.storedId("abc-def\nextra payload"))
        assertEquals("abc-def\nextra payload", BootMarkers.body("abc-def", "extra payload"))
        assertEquals("abc-def", BootMarkers.body("abc-def", ""))
        assertFalse(BootMarkers.sameBoot("abc-def", "other"))
        assertFalse(BootMarkers.sameBoot("", "abc-def"))
        assertFalse(BootMarkers.sameBoot("abc-def", ""))
        assertFalse(BootMarkers.sameBoot("", ""))
    }

    @Test fun leftoverMarkerFromAnotherBootIsIgnored() {
        val dir = createTempDirectory("df-markers").toFile()
        try {
            File(dir, "daemon-ready").writeText("boot-old")
            File(dir, "plan-done").writeText("boot-new\nplan accepted")
            assertFalse(BootMarkers.has(dir, "daemon-ready", "boot-new"))
            assertTrue(BootMarkers.has(dir, "plan-done", "boot-new"))
            assertFalse(BootMarkers.has(dir, "plan-done", "boot-old"))
            assertFalse(BootMarkers.has(dir, "missing", "boot-new"))
            File(dir, "boot-run").writeText("boot-new\nboot")
            File(dir, "plan-accepted").writeText("boot-new")
            assertTrue(BootMarkers.has(dir, "boot-run", "boot-new"))
            assertTrue(BootMarkers.has(dir, "plan-accepted", "boot-new"))
            assertFalse(BootMarkers.has(dir, "boot-run", "boot-old"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
