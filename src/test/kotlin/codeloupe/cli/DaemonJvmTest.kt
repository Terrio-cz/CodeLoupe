package codeloupe.cli

import kotlin.test.Test
import kotlin.test.assertTrue

class DaemonJvmTest {
    @Test
    fun `the heap is 256 MB with a parse worker and 320 MB when the daemon parses itself`() {
        assertTrue("-Xmx256m" in DaemonJvm.args())
        assertTrue("-Xmx320m" in DaemonJvm.args(parsesHere = true))
    }

    @Test
    fun `a daemon that runs out of heap ends instead of holding its port`() {
        assertTrue("-XX:+ExitOnOutOfMemoryError" in DaemonJvm.args())
        assertTrue("-XX:+ExitOnOutOfMemoryError" in DaemonJvm.args(parsesHere = true))
    }
}
