package codeloupe.cli

import kotlin.test.Test
import kotlin.test.assertTrue

class DaemonJvmTest {
    @Test
    fun `the heap is 64 MB with a parse worker and 80 MB when the daemon parses itself`() {
        assertTrue("-Xmx64m" in DaemonJvm.args())
        assertTrue("-Xmx80m" in DaemonJvm.args(parsesHere = true))
    }
}
