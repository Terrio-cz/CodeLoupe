package codeloupe.repo

import codeloupe.TestRepos
import codeloupe.config.Config
import codeloupe.daemon.JobQueue
import codeloupe.index.BaseBuilder
import codeloupe.index.Store
import codeloupe.platform.Sha1
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class RegistryTest {
    private val repo = TestRepos.fixtureRepo("kotlin/sample")
    private val commonDir = repo.resolve(".git").toString().replace('\\', '/')

    private fun config(heapMb: Int = 512, buildTimeoutMs: Long = 120_000) = Config(TestRepos.tmpDir("home"), 0, 60_000, buildTimeoutMs, heapMb, null)

    @Test
    fun `a build over its time limit says so`(): Unit = runBlocking {
        val registry = Registry(config(buildTimeoutMs = 200), JobQueue(this))
        val failure = assertFailsWith<IllegalStateException> { registry.query(repo.toString()) { } }
        assertEquals("build timed out after 200 ms", failure.message)
    }

    @Test
    fun `reads run at most maxParallelQueries at a time, the rest wait their turn`(): Unit = runBlocking {
        val registry = Registry(config().copy(maxParallelQueries = 2), JobQueue(this))
        registry.query(repo.toString()) { } // the first query builds the base
        val running = java.util.concurrent.atomic.AtomicInteger()
        val peak = java.util.concurrent.atomic.AtomicInteger()
        val answers = (1..8).map {
            async(Dispatchers.Default) {
                registry.query(repo.toString()) {
                    peak.accumulateAndGet(running.incrementAndGet(), ::maxOf)
                    Thread.sleep(80)
                    running.decrementAndGet()
                    it
                }
            }
        }
        answers.forEach { it.await() }
        assertEquals(2, peak.get(), "two at a time, never more, and the gate was in use")
    }

    @Test
    fun `a failed build is not retried for the same commit and shows in the status`(): Unit = runBlocking {
        // A 1 MB heap makes the worker JVM refuse to start: a cheap, real build failure.
        val queue = JobQueue(this)
        val registry = Registry(config(heapMb = 1), queue)
        val first = assertFailsWith<IllegalStateException> { registry.query(repo.toString()) { } }
        assertContains(first.message!!, "build exited")
        val second = assertFailsWith<IllegalStateException> { registry.query(repo.toString()) { } }
        assertContains(second.message!!, "failed")
        assertEquals(1, queue.snapshot().failed)
        assertNotNull(registry.snapshot().single().failure)
    }

    @Test
    fun `a base of another index format is rebuilt, not served`() {
        val config = config()
        val dir = config.home.resolve("repos").resolve(Sha1.hex(commonDir.lowercase()).take(12))
        Files.createDirectories(dir)
        val old = dir.resolve("base-old.db")
        BaseBuilder.build(repo.toString(), TestRepos.git(repo, "rev-parse", "HEAD"), old)
        fun stateFor(format: String): RepoState {
            val record = """{"id":"x","commonDir":"$commonDir","baseCommit":"abc","baseFile":"${old.toString().replace("\\", "\\\\")}","format":"$format"}"""
            Files.writeString(dir.resolve("repo.json"), record)
            return Registry(config, JobQueue(CoroutineScope(Dispatchers.Default))).repo(commonDir)
        }
        assertEquals("abc", stateFor(Store.FORMAT).baseCommit)
        val stale = stateFor("1/tree-sitter")
        assertNull(stale.baseFile)
        assertNull(stale.baseCommit)
    }

    @Test
    fun `relative roots are refused`() {
        val registry = Registry(config(), JobQueue(CoroutineScope(Dispatchers.Default)))
        assertContains(assertFailsWith<IllegalArgumentException> { registry.locate(".") }.message!!, "absolute")
    }
}
