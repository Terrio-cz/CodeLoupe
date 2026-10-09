package codeloupe.aot

import codeloupe.platform.DetachedStart
import codeloupe.platform.JavaProcess
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/**
 * When the AOT caches of this install are missing, starts one trainer for all the daemons that find them missing. The
 * daemon does it a while after it started, in the background: a CLI call never waits for it, and the first call is not
 * slower than it was. Calls that run while the caches are made, or after that failed, run as before.
 */
object AotLauncher {
    private val RETRY_AFTER: Duration = Duration.ofHours(6)

    private const val DAEMON_DELAY_MS = 10_000L

    /**
     * Called by a daemon that a launcher started. Waits a while so that the first queries have the machine, then starts a
     * trainer if the caches are missing. Does nothing in the daemons the trainer itself starts.
     */
    fun afterDaemonStart(delayMs: Long = DAEMON_DELAY_MS) {
        val caches = AotCaches.fromProperty() ?: return
        if (System.getenv(AotTrainer.TRAINING_ENV) != null) return
        Thread.ofPlatform().daemon().name("codeloupe-aot").start {
            runCatching {
                Thread.sleep(delayMs)
                startTraining(caches, spawn = ::spawn)
            }
        }
    }

    /** True when a trainer was started. [spawn] starts it. */
    internal fun startTraining(caches: AotCaches, classPath: String = System.getProperty("java.class.path"), spawn: (AotCaches, String) -> Unit): Boolean {
        if (caches.isReady() || recentlyFailed(caches)) return false
        // A class path of several entries (a development run) cannot be launched with `-jar` as the CLI cache is recorded.
        if (!classPath.endsWith(".jar") || java.io.File.pathSeparator in classPath) return false
        Files.createDirectories(caches.prefix.parent)
        if (!AotLock(caches.lock).tryAcquire()) return false
        try {
            spawn(caches, classPath)
        } catch (e: Throwable) {
            AotLock(caches.lock).release()
            throw e
        }
        return true
    }

    internal fun spawn(caches: AotCaches, classPath: String) {
        val command = JavaProcess.command(AotTrainerMain.NAME, listOf("-XX:+UseSerialGC", "-XX:TieredStopAtLevel=1", "-Xmx96m", "-Xlog:disable"), listOf(caches.prefix.toString(), classPath))
        // Below normal priority where there is a nice: the caches are for later calls, not for the one that is running.
        val niceness = if (System.getProperty("os.name").lowercase().startsWith("windows")) emptyList() else listOf("nice", "-n", "10")
        DetachedStart.start(niceness + command, caches.prefix.parent)
    }

    /**
     * Ends the trainers running for any install under [home], so that an installation or an update can replace the jars they
     * have open. The desktop app runs the bundled jar without a launcher, so it is the files that name a trainer, not this JVM.
     */
    fun cancel(home: Path): Boolean {
        val dir = home.resolve("aot")
        if (!Files.isDirectory(dir)) return false
        var any = false
        Files.newDirectoryStream(dir, "*.lock").use { locks ->
            for (lock in locks) {
                if (!AotLock(lock).cancel()) continue
                any = true
                AotLock(lock.resolveSibling(lock.fileName.toString().removeSuffix(".lock") + ".trainer")).release()
            }
        }
        return any
    }

    private fun recentlyFailed(caches: AotCaches): Boolean = try {
        val at = Files.readAllLines(caches.failed).first().trim().toLong()
        System.currentTimeMillis() - at < RETRY_AFTER.toMillis()
    } catch (_: Exception) {
        false
    }
}
