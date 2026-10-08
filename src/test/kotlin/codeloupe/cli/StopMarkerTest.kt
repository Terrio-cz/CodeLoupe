package codeloupe.cli

import codeloupe.TestRepos
import codeloupe.config.Config
import codeloupe.daemon.Daemon
import com.github.ajalt.clikt.core.parse
import java.net.ServerSocket
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StopMarkerTest {
    @Test
    fun `stop writes the marker the desktop app honours and start removes it`() {
        val home = TestRepos.tmpDir("stop-marker")
        val config = Config(home, ServerSocket(0).use { it.localPort }, 60_000, 120_000, 512, null)
        val marker = StopMarker.file(home)

        val daemon = Daemon.start(config)
        try {
            assertFalse(Files.exists(marker))
            StopCommand { config }.parse(emptyList<String>())
            assertTrue(Files.exists(marker), "stop leaves the marker")
        } finally {
            runCatching { daemon.stop() }
        }

        // A daemon that is up again (here: started in-process) is what `start` finds; it only has to clear the marker.
        val again = Daemon.start(config)
        try {
            StartCommand { config }.parse(emptyList<String>())
            assertFalse(Files.exists(marker), "start removes the marker")
        } finally {
            again.stop()
        }
    }

    @Test
    fun `stop writes the marker also when no daemon runs`() {
        val home = TestRepos.tmpDir("stop-marker-idle")
        val config = Config(home, ServerSocket(0).use { it.localPort }, 60_000, 120_000, 512, null)
        StopCommand { config }.parse(emptyList<String>())
        assertTrue(Files.exists(StopMarker.file(home)), "a stop of nothing is still a decision the app must honour")
        StopMarker.clear(home)
        assertFalse(Files.exists(StopMarker.file(home)))
    }
}
