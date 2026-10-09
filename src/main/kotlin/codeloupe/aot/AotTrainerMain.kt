package codeloupe.aot

import java.nio.file.Files
import kotlin.system.exitProcess

/**
 * The JVM [AotLauncher] starts to make the caches of an install: `AotTrainerMain <prefix> <class path>`. It takes over the
 * lock the starter holds, trains, and writes a note ([AotCaches.failed]) when it could not, so that the next calls do not try again at once.
 */
object AotTrainerMain {
    const val NAME = "codeloupe.aot.AotTrainerMain"

    @JvmStatic
    fun main(args: Array<String>) {
        val caches = AotCaches(java.nio.file.Path.of(args[0]))
        // Starting a process detached can end up starting it twice (DetachedStart falls back when it cannot read the answer).
        val running = AotLock(caches.trainer)
        if (!running.tryAcquire()) exitProcess(0)
        val lock = AotLock(caches.lock)
        lock.adopt()
        val ok = try {
            AotTrainer(caches, args[1]).train()
        } catch (e: Throwable) {
            runCatching { Files.writeString(caches.failed, "${System.currentTimeMillis()}\n${e::class.simpleName}: ${e.message.orEmpty().lineSequence().first()}\n") }
            false
        } finally {
            lock.release()
            running.release()
        }
        exitProcess(if (ok) 0 else 1)
    }
}
