package codeloupe.daemon

import codeloupe.TestRepos
import codeloupe.cli.DaemonClient
import codeloupe.config.Config
import java.net.BindException
import java.net.ServerSocket
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Two CLIs that find no daemon start one each (CL-229); the one that loses the port must not leave the winner with a token other than the file's. */
class ParallelStartTest {
    @Test
    fun `two daemons started together on one home leave one that its clients can talk to`() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(ROUNDS) { round ->
                val config = Config(TestRepos.tmpDir("parallel-start"), ServerSocket(0).use { it.localPort }, 60_000, 120_000, 512, null)
                val barrier = CyclicBarrier(2)
                val starts = (1..2).map { pool.submit<Daemon> { barrier.await(); Daemon.start(config) } }
                val daemons = starts.mapNotNull { start ->
                    try {
                        start.get()
                    } catch (e: ExecutionException) {
                        assertTrue(e.cause is BindException, "round $round: the loser fails on the port, not otherwise: ${e.cause}")
                        null
                    }
                }
                try {
                    assertEquals(1, daemons.size, "round $round")
                    assertEquals(DaemonToken.read(config.home), daemons.single().token, "round $round: the winner holds the token of the file")
                    val client = DaemonClient(config)
                    assertNotNull(client.status(), "round $round")
                    assertEquals(200, client.send("GET", "/jobs").first, "round $round: the token the client reads is accepted")
                } finally {
                    daemons.forEach { it.stop() }
                }
            }
        } finally {
            pool.shutdownNow()
        }
    }

    private companion object {
        const val ROUNDS = 25
    }
}
