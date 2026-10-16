package df.root.script

import org.junit.Assert.*
import org.junit.Test

/**
 * ScriptPresets' Android-free surface. The command builders take a Context, so
 * they are covered by instrumentation on device rather than here; what is
 * testable in a plain JVM unit test is the quoting and the preset catalogue.
 */
class ScriptPresetsTest {
    @Test fun quotingProtectsEmbeddedSingleQuotes() {
        assertEquals("'value'\\''with quote'", ScriptPresets.quote("value'with quote"))
        assertEquals("''", ScriptPresets.quote(""))
        assertEquals("'a b'", ScriptPresets.quote("a b"))
    }
    @Test fun unloadModuleIsAnExplicitCommand() {
        assertEquals("rmmod dfroot 2>/dev/null || true", ScriptPresets.UNLOAD_MODULE)
        assertTrue(ScriptPresets.PARTITIONS_RO.contains("blockdev --setro"))
        assertTrue(ScriptPresets.DISABLE_KSU_MODULES.contains("/data/adb/modules"))
        assertEquals("rm -f /dev/df* '/data/user_de/0/df.root/markers'/*", ScriptPresets.CLEAN_MARKERS)
    }
    @Test fun lateLoadPresetNoLongerStagesABinary() {
        // late-load is driven by the manager step now; the shortcut list must not
        // still offer a preset that copied libksud.so into the data dir.
        assertFalse(ScriptPresets.ids.contains("late_load"))
        assertTrue(ScriptPresets.ids.contains("clean_markers"))
    }
    @Test fun automaticExecutionIsOptIn() {
        assertFalse(ScriptConfig().enabled)
        assertEquals("", ScriptConfig().text)
        assertNotEquals(ScriptTrigger.APP_OPEN.key, ScriptTrigger.BOOT.key)
    }
}
