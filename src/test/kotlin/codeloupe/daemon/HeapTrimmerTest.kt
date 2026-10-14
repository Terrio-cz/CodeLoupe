package codeloupe.daemon

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HeapTrimmerTest {
    @Test
    fun `collects only when the heap grew past the slack since the last collection`() {
        var committed = 40L
        var collections = 0
        val trimmer = HeapTrimmer(slackMb = 32, committedMb = { committed }, collect = { collections++; committed = 50 })

        assertTrue(trimmer.check(), "40 MB is past the slack of a heap never collected")
        assertEquals(1, collections)
        committed = 80
        assertFalse(trimmer.check(), "80 MB is within 32 MB of the 50 MB the collection left")
        committed = 247
        assertTrue(trimmer.check())
        assertEquals(2, collections)
    }

    @Test
    fun `touches while a check is pending schedule nothing more`() {
        var checks = 0
        val trimmer = HeapTrimmer(delayMs = 50, slackMb = 0, committedMb = { checks++; 1L }, collect = {})
        repeat(20) { trimmer.touch() }
        Thread.sleep(400)
        assertEquals(2, checks, "one check: committed read before and after its collection")
    }
}
