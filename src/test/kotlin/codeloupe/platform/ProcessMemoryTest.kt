package codeloupe.platform

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The resident size of this JVM from whatever the OS offers: the working set (Windows), `/proc` (Linux), `ps` and `getrusage` (macOS). */
class ProcessMemoryTest {
    @Test
    fun `the resident size of a running JVM is a sensible number of megabytes`() {
        val current = assertNotNull(ProcessMemory.rssMb(), "the OS says how much memory this process holds")
        assertTrue(current in 20..64_000, "$current MB")
    }

    @Test
    fun `the peak is known and is not below what the process holds now, give or take what ps rounds`() {
        val peak = assertNotNull(ProcessMemory.peakRssMb())
        val current = assertNotNull(ProcessMemory.rssMb())
        assertTrue(peak in 20..64_000, "$peak MB")
        assertTrue(peak + 16 >= current, "peak $peak MB, now $current MB")
    }
}
