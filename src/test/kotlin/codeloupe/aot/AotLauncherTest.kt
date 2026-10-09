package codeloupe.aot

import codeloupe.TestRepos
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AotLauncherTest {
    private val dir = TestRepos.tmpDir("aot-launcher").resolve("aot")
    private val caches = AotCaches(dir.resolve("C__Apps_codeloupe-0123456789ab"), "jvm-1")
    private val jar = "C:/Apps/codeloupe/lib/codeloupe-0.1.0.jar"
    private val spawned = AtomicInteger()

    private fun start(classPath: String = jar) =
        AotLauncher.startTraining(caches, classPath) { _, _ -> spawned.incrementAndGet() }

    @Test
    fun `several daemons starting at once start one trainer`() {
        val go = CountDownLatch(1)
        val threads = (1..12).map { Thread.ofPlatform().start { go.await(); start() } }
        go.countDown()
        threads.forEach(Thread::join)
        assertEquals(1, spawned.get())
        assertTrue(AotLock(caches.lock).held())
    }

    @Test
    fun `nothing starts once the caches are in place, or while a trainer runs, or after one failed`() {
        Files.createDirectories(dir)
        Files.write(caches.cli, ByteArray(10))
        Files.write(caches.daemon, ByteArray(20))
        Files.writeString(caches.ready, caches.readyContent())
        assertFalse(start())

        Files.delete(caches.ready)
        Files.writeString(caches.failed, "${System.currentTimeMillis()}\nboom\n")
        assertFalse(start(), "a failure is remembered for hours")
        Files.writeString(caches.failed, "${System.currentTimeMillis() - 7 * 3_600_000L}\nold\n")
        assertTrue(start(), "an old failure is tried again")
        assertFalse(start(), "the trainer that was just started holds the lock")
        assertEquals(1, spawned.get())
    }

    @Test
    fun `a class path that is not a single jar cannot be trained`() {
        assertFalse(start(classPath = "build/classes;lib/a.jar"))
        assertFalse(start(classPath = "build/classes"))
        assertEquals(0, spawned.get())
    }

    @Test
    fun `a trainer that could not be started gives the lock back`() {
        assertFailsWith<IllegalStateException> { AotLauncher.startTraining(caches, jar) { _, _ -> error("no nice") } }
        assertFalse(AotLock(caches.lock).held())
        assertTrue(start())
    }
}
