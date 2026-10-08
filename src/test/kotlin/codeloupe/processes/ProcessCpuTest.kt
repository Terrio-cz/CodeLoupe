package codeloupe.processes

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProcessCpuTest {
    @Test
    fun `ps cpu times are read in the forms Linux and macOS print`() {
        assertEquals(83_000, ProcessCpu.parse("  1:23 "))
        assertEquals(1_230, ProcessCpu.parse("0:01.23"))
        assertEquals(3_723_000, ProcessCpu.parse("01:02:03"))
        assertEquals(((26 * 60 + 2) * 60 + 3) * 1000L, ProcessCpu.parse("1-02:02:03"))
        assertNull(ProcessCpu.parse(""))
        assertNull(ProcessCpu.parse("garbage"))
    }
}
