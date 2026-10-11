package codeloupe.daemon

import codeloupe.platform.OwnerOnly
import java.io.IOException
import java.net.BindException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Daemons that start on one home start one after the other. Two CLIs that find no daemon launch one each; before this, both built their
 * databases, token and sockets at once and the loser could take the winner's files with it. Under the lock the second finds the port taken
 * before it has opened anything and gives up quietly.
 */
internal object StartLock {
    private const val WAIT_MS = 30_000L
    private const val POLL_MS = 20L
    private const val PROBE_MS = 200

    /**
     * Runs [start] holding `<home>/daemon.lock`; throws [BindException] without running it when something already listens on [port].
     * A lock that cannot be had in [WAIT_MS] (a start that hangs, a file system without locks) is not waited for: [start] runs as it did before.
     */
    fun <T> held(home: Path, port: Int, start: () -> T): T = synchronized(this) {
        OwnerOnly.home(home)
        val channel = runCatching { FileChannel.open(home.resolve("daemon.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE) }.getOrNull()
        try {
            val lock = channel?.let(::acquire)
            try {
                if (listening(port)) throw BindException("127.0.0.1:$port is already served")
                start()
            } finally {
                runCatching { lock?.release() }
            }
        } finally {
            runCatching { channel?.close() }
        }
    }

    private fun acquire(channel: FileChannel): java.nio.channels.FileLock? {
        val until = System.nanoTime() + WAIT_MS * 1_000_000
        while (System.nanoTime() < until) {
            try {
                channel.tryLock()?.let { return it }
            } catch (_: OverlappingFileLockException) {
                // Held by this JVM: cannot happen under the monitor above.
            } catch (_: IOException) {
                return null
            }
            Thread.sleep(POLL_MS)
        }
        return null
    }

    private fun listening(port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), PROBE_MS) }
        true
    } catch (_: IOException) {
        false
    }
}
