package codeloupe.aot

import codeloupe.TestRepos
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AotLockTest {
    private val file = TestRepos.tmpDir("aot-lock").resolve("x.lock")

    @Test
    fun `of many callers at once one takes the lock`() {
        val won = AtomicInteger()
        val start = CountDownLatch(1)
        val threads = (1..16).map { Thread.ofPlatform().start { start.await(); if (AotLock(file).tryAcquire()) won.incrementAndGet() } }
        start.countDown()
        threads.forEach(Thread::join)
        assertEquals(1, won.get())
    }

    @Test
    fun `the lock is free again once released`() {
        val lock = AotLock(file)
        assertTrue(lock.tryAcquire())
        assertFalse(AotLock(file).tryAcquire())
        lock.release()
        assertTrue(AotLock(file).tryAcquire())
    }

    @Test
    fun `a lock of a holder that died is taken over after a start-up, one of a live holder only after its age`() {
        val quick = if (System.getProperty("os.name").startsWith("Windows")) listOf("cmd", "/c", "exit 0") else listOf("true")
        val dead = ProcessBuilder(quick).start().also { it.waitFor() }.pid()
        Files.writeString(file, "$dead\n")
        assertFalse(AotLock(file).tryAcquire(), "a holder that died a moment ago may still be starting its trainer")
        assertTrue(AotLock(file) { Instant.now().plus(Duration.ofSeconds(60)) }.tryAcquire(), "dead and past the start-up")

        Files.writeString(file, "${ProcessHandle.current().pid()}\n")
        assertFalse(AotLock(file) { Instant.now().plus(Duration.ofMinutes(10)) }.tryAcquire(), "alive")
        assertTrue(AotLock(file) { Instant.now().plus(Duration.ofMinutes(30)) }.tryAcquire(), "older than any training takes")
    }

    @Test
    fun `cancel ends a trainer with what it started, from the lock files of a home`() {
        val home = file.parent.resolve("home")
        val aot = Files.createDirectories(home.resolve("aot"))
        val fake = sleeper()
        try {
            val lock = aot.resolve("Kx-0123456789ab.lock")
            val trainer = aot.resolve("Kx-0123456789ab.trainer")
            Files.writeString(lock, trainerLock(fake, fake.toHandle().info().startInstant().get().toEpochMilli()))
            Files.writeString(trainer, fake.pid().toString())
            assertTrue(AotLauncher.cancel(home))
            assertTrue(fake.waitFor(10, java.util.concurrent.TimeUnit.SECONDS), "the trainer is gone")
            assertFalse(Files.exists(lock))
            assertFalse(Files.exists(trainer))
            assertFalse(AotLauncher.cancel(home), "nothing left to cancel")
        } finally {
            fake.destroyForcibly()
        }
    }

    @Test
    fun `cancel leaves alone a pid that another process has taken since the trainer wrote it`() {
        val fake = sleeper()
        try {
            Files.writeString(file, trainerLock(fake, fake.toHandle().info().startInstant().get().toEpochMilli() - 60_000))
            assertFalse(AotLock(file).cancel())
            assertTrue(fake.isAlive)
        } finally {
            fake.destroyForcibly()
        }
    }

    private fun trainerLock(process: Process, started: Long) = listOf(process.pid(), "trainer", started).joinToString(System.lineSeparator(), postfix = System.lineSeparator())

    private fun sleeper(): Process {
        val windows = System.getProperty("os.name").startsWith("Windows")
        return ProcessBuilder(if (windows) listOf("ping", "-n", "60", "127.0.0.1") else listOf("sleep", "60")).start()
    }

    @Test
    fun `cancel leaves a holder that is not a trainer alone`() {
        val lock = AotLock(file)
        assertTrue(lock.tryAcquire())
        assertFalse(lock.cancel(), "this process holds the lock and is no trainer")
        assertTrue(ProcessHandle.current().isAlive)
    }
}
