package df.root.script

import org.junit.Assert.*
import org.junit.Test

class ModuleKmiTest {
    @Test fun usesConfiguredBranchBeforeDetected() {
        assertEquals("android15-6.6", ModuleKmi.resolve("", "android15-6.6", "android14-5.15"))
    }
    @Test fun convertsFullKmiToNativeBranch() {
        assertEquals("android15-6.6", ModuleKmi.resolve("6.6-android15-8", null, null))
    }
    @Test fun invalidKmiIsRejected() {
        assertNull(ModuleKmi.resolve("not-a-kmi", null, null))
        assertNull(ModuleKmi.resolve("", null, null))
    }
}
