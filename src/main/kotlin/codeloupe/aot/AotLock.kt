package codeloupe.aot

import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.time.Instant
import kotlin.math.abs

/**
 * One training at a time per install: a file created exclusively holds the process id of whoever trains, and whether that is
 * the call that is starting a trainer or the trainer itself, with the time it started (the JDK cannot read the command line
 * of another process on Windows, so a trainer is known by what it wrote). A holder that died without releasing is told by
 * its pid, once the lock is older than a start-up could take, or by its age.
 */
internal class AotLock(private val file: Path, private val now: () -> Instant = Instant::now) {
    /** True when this call took the lock for [pid]; false when a live trainer holds it. */
    fun tryAcquire(pid: Long = ProcessHandle.current().pid()): Boolean {
        repeat(3) {
            try {
                Files.writeString(file, "$pid\n$STARTER\n", StandardOpenOption.CREATE_NEW)
                return true
            } catch (_: FileAlreadyExistsException) {
                if (!stale()) return false
                runCatching { Files.deleteIfExists(file) }
            }
        }
        return false
    }

    /** The holder hands the lock to its own process, the trainer. */
    fun adopt(handle: ProcessHandle = ProcessHandle.current()) {
        val started = handle.info().startInstant().map { it.toEpochMilli() }.orElse(0)
        Files.writeString(file, "${handle.pid()}\n$TRAINER\n$started\n")
    }

    fun release() {
        runCatching { Files.deleteIfExists(file) }
    }

    fun held(): Boolean = Files.exists(file) && !stale()

    /**
     * Ends the trainer that holds the lock, with the JVMs it started, so that an installation or an update can replace the
     * jars it has open; true when there was one. A holder that is not a trainer (the call that is about to start one) is left alone.
     */
    fun cancel(): Boolean {
        val lines = runCatching { Files.readAllLines(file) }.getOrNull() ?: return false
        if (lines.getOrNull(1) != TRAINER) return false
        val handle = lines[0].trim().toLongOrNull()?.let { ProcessHandle.of(it).orElse(null) } ?: return false
        // The pid may have been given to another process since.
        val started = handle.info().startInstant().map { it.toEpochMilli() }.orElse(null) ?: return false
        if (abs(started - (lines.getOrNull(2)?.trim()?.toLongOrNull() ?: 0)) > START_TOLERANCE_MS) return false
        handle.descendants().forEach { it.destroyForcibly() }
        handle.destroyForcibly()
        release()
        return true
    }

    // A lock that cannot be read right now (its holder is writing it) is held; one that is gone is free.
    private fun stale(): Boolean = try {
        val age = Duration.between(Files.getLastModifiedTime(file).toInstant(), now())
        age > MAX_AGE || (age > START_UP && holder()?.let { ProcessHandle.of(it).isPresent } != true)
    } catch (_: NoSuchFileException) {
        true
    } catch (_: IOException) {
        false
    }

    private fun holder(): Long? = runCatching { Files.readAllLines(file).first().trim().toLong() }.getOrNull()

    private companion object {
        const val STARTER = "starter"
        const val TRAINER = "trainer"
        const val START_TOLERANCE_MS = 2_000L
        val MAX_AGE: Duration = Duration.ofMinutes(20)
        val START_UP: Duration = Duration.ofSeconds(30)
    }
}
