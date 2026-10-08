package codeloupe.overlay

import codeloupe.TestRepos
import codeloupe.TestRepos.git
import codeloupe.config.Config
import codeloupe.daemon.JobQueue
import codeloupe.index.BuildResult
import codeloupe.index.StoreUpdate
import codeloupe.platform.Timings
import codeloupe.query.FindQuery
import codeloupe.repo.BuildLauncher
import codeloupe.repo.Registry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Worktree overlays and base syncs against real git repositories with two worktrees. */
class OverlayTest {
    private val repo = TestRepos.fixtureRepo(
        "kotlin/sample",
        mapOf(".gitignore" to "build/\n*.gen.kt\n", ALPHA to alpha("one"), GONE to "package demo\n\nclass Gone\n"),
    )
    private val feature = worktree("feature")
    private val config = Config(TestRepos.tmpDir("home"), 0, 60_000, buildTimeoutMs = 120_000, buildHeapMb = 512, defaultRoot = null, overlayCheckMs = 0)
    private val queue = JobQueue(CoroutineScope(Dispatchers.Default))

    @Test
    fun `changed, new and deleted files of a worktree show in its next query`() = changesShow(config)

    @Test
    fun `the same in a repository too large to walk, where git alone settles what changed`() = changesShow(config.copy(largeWorktreeFiles = 1))

    private fun changesShow(config: Config) {
        val registry = Registry(config, queue)
        write(repo, "build/Old.kt", "package demo\n\nclass Old\n")
        assertContains(find(registry, feature, "Alpha.one"), "fun one")
        assertNone(find(registry, repo, "Old"), "ignored directory")

        write(feature, ALPHA, alpha("two"))
        write(feature, BETA, "package demo\n\nclass Beta\n")
        Files.delete(feature.resolve(GONE))
        write(feature, "build/gen/Hidden.kt", "package demo\n\nclass Hidden\n")
        write(feature, "src/main/kotlin/demo/Skip.gen.kt", "package demo\n\nclass Skip\n")
        assertContains(find(registry, feature, "Alpha.two"), "fun two")
        assertNone(find(registry, feature, "Alpha.one"), "old body")
        assertContains(find(registry, feature, "Beta"), "class Beta")
        assertNone(find(registry, feature, "Gone"), "deleted file")
        assertNone(find(registry, feature, "Hidden"), "new ignored directory")
        assertNone(find(registry, feature, "Skip"), "ignored file")

        assertContains(find(registry, repo, "Alpha.one"), "fun one")
        assertNone(find(registry, repo, "Beta"), "other worktree's file")
        assertContains(find(registry, repo, "Gone"), "class Gone")

        git(feature, "add", "-A")
        git(feature, "commit", "-q", "-m", "feature work")
        assertContains(find(registry, feature, "Beta"), "class Beta")
        // Back to the base text, only with other line ends: the file leaves the overlay.
        write(feature, ALPHA, alpha("one").replace("\n", "\r\n"))
        assertContains(find(registry, feature, "Alpha.one"), "fun one")
        assertEquals(1, registry.snapshot().single().overlays)
    }

    @Test
    fun `a small move of the default branch syncs inline`() {
        val launcher = CountingLauncher()
        val registry = Registry(config, queue, launcher)
        assertContains(find(registry, repo, "Alpha.one"), "fun one")
        write(repo, ALPHA, alpha("three"))
        commit(repo, "small move")
        assertContains(find(registry, repo, "Alpha.three"), "fun three")
        assertEquals(git(repo, "rev-parse", "HEAD"), registry.snapshot().single().baseCommit, "base synced before the answer")
        assertEquals(0, launcher.syncs.get(), "no build worker")
        assertEquals(0, registry.snapshot().single().overlays)
        assertContains(find(registry, feature, "Alpha.one"), "fun one")
    }

    @Test
    fun `a default branch moved by 500 files syncs in the heavy lane while the old base answers`() {
        val gate = CompletableDeferred<Unit>()
        val launcher = CountingLauncher(gate)
        val registry = Registry(config, queue, launcher)
        write(feature, BETA, "package demo\n\nclass Beta\n")
        assertContains(find(registry, feature, "Beta"), "class Beta")

        for (i in 0 until 500) write(repo, "src/main/kotlin/gen/Gen$i.kt", "package gen\n\nclass Gen$i {\n    fun v() = $i\n}\n")
        write(repo, ALPHA, alpha("three"))
        commit(repo, "big move")
        val head = git(repo, "rev-parse", "HEAD")
        assertContains(find(registry, feature, "Beta"), "class Beta")
        assertContains(find(registry, feature, "Alpha.one"), "fun one")
        assertNone(find(registry, feature, "Gen1"), "not on the feature branch")
        assertEquals(1, launcher.syncs.get())
        assertTrue(queue.snapshot().heavy.running.orEmpty().startsWith("sync:"), queue.snapshot().toString())

        gate.complete(Unit)
        waitFor("base synced") { registry.snapshot().single().baseCommit == head }
        assertContains(find(registry, repo, "Gen499"), "class Gen499")
        assertContains(find(registry, repo, "Alpha.three"), "fun three")
        assertContains(find(registry, repo, "Gen0"), "class Gen0")
        // The feature worktree is still on the old commit: main's files are tombstones, its Alpha is the old one.
        assertNone(find(registry, feature, "Gen1"), "tombstone")
        assertContains(find(registry, feature, "Alpha.one"), "fun one")
        assertContains(find(registry, feature, "Beta"), "class Beta")
        assertEquals(0, launcher.overlays.get(), "the feature overlay copied its unchanged files from the old base")
    }

    @Test
    fun `a worktree with more than 200 changed files is indexed by a build worker`() {
        val launcher = CountingLauncher()
        val registry = Registry(config, queue, launcher)
        assertContains(find(registry, feature, "Alpha.one"), "fun one")
        for (i in 0 until 250) write(feature, "src/main/kotlin/many/Many$i.kt", "package many\n\nclass Many$i\n")
        assertContains(find(registry, feature, "Many249"), "class Many249")
        assertEquals(1, launcher.overlays.get())
        write(feature, "src/main/kotlin/many/Many0.kt", "package many\n\nclass Many0 {\n    fun again() = 0\n}\n")
        assertContains(find(registry, feature, "Many0.again"), "fun again")
        assertEquals(1, launcher.overlays.get(), "a small change after it is parsed in the daemon")
        write(feature, "src/main/kotlin/many/Generated.kt", TestRepos.bigClass("many", "Generated", 12_000))
        assertContains(find(registry, feature, "Generated.m11999"), "fun m11999")
        assertEquals(2, launcher.overlays.get(), "one file over 512 KB goes to a build worker too")
    }

    @Test
    fun `a large base file rewritten unchanged is compared by hash, not handed to a worker`() {
        val big = TestRepos.bigClass("many", "Generated", 12_000)
        write(repo, GENERATED, big)
        commit(repo, "generated code")
        val launcher = CountingLauncher()
        val registry = Registry(config, queue, launcher)
        assertContains(find(registry, feature, "Alpha.one"), "fun one")
        write(feature, GENERATED, big)
        Files.setLastModifiedTime(feature.resolve(GENERATED), java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5_000))
        assertContains(find(registry, feature, "Generated.m11999"), "fun m11999")
        // The same text checked out with CRLF line ends (autocrlf on Windows).
        write(feature, GENERATED, BOM + big.replace(LF, CRLF))
        assertContains(find(registry, feature, "Generated.m11999"), "fun m11999")
        assertEquals(0, launcher.overlays.get())
        assertEquals(0, registry.snapshot().single().overlays)
    }

    @Test
    fun `files outside a sparse checkout are answered from the base, not hidden`() {
        git(feature, "sparse-checkout", "set", "--no-cone", "/src/main/kotlin/demo/")
        assertTrue(!feature.resolve("src/main/kotlin/com/example/shop/Constructs.kt").exists())
        val registry = Registry(config, queue)
        assertContains(find(registry, feature, "Alpha.one"), "fun one")
        write(repo, "src/main/kotlin/com/example/shop/Constructs.kt", "package com.example.shop\n\nclass Moved\n")
        commit(repo, "change outside the cone")
        // The first query after the landing may still read the previous base (the worktree has not changed); then the base answers.
        waitFor("the landing shows through the base") { find(registry, feature, "Moved").contains("class Moved") }
    }

    @Test
    fun `queries waiting for a slow refresh share the wait instead of queueing behind each other`() {
        val gate = CompletableDeferred<Unit>()
        val launcher = CountingLauncher(overlayGate = gate)
        // The first base build is not under test and can outlast the short timeout when the machine is busy.
        val warm = Registry(config, queue)
        try {
            assertContains(find(warm, feature, "Alpha.one"), "fun one")
        } finally {
            warm.close()
        }
        val registry = Registry(config.copy(queryTimeoutMs = 1_500), queue, launcher)
        assertContains(find(registry, feature, "Alpha.one"), "fun one")
        for (i in 0 until 250) write(feature, "src/main/kotlin/many/Many$i.kt", "package many\n\nclass Many$i\n")
        val started = System.currentTimeMillis()
        val answers = (0 until 4).map { Thread.ofVirtual().start { runCatching { find(registry, feature, "Many1") } } }
        answers.forEach { it.join() }
        assertTrue(System.currentTimeMillis() - started < 4_000, "four waits of 1.5 s ran side by side")
        gate.complete(Unit)
        waitFor("refresh done") { queue.snapshot().heavy.running == null }
        assertContains(find(registry, feature, "Many249"), "class Many249")
    }

    @Test
    fun `the overlay of a removed worktree is deleted, a live one survives a restart`() {
        val other = worktree("other")
        write(feature, BETA, "package demo\n\nclass Beta\n")
        write(other, BETA, "package demo\n\nclass Other\n")
        val first = Registry(config, queue)
        assertContains(find(first, feature, "Beta"), "class Beta")
        assertContains(find(first, other, "Other"), "class Other")
        assertEquals(2, overlayFiles().size)

        git(repo, "worktree", "remove", "--force", feature.toString())
        first.close()
        val restartQueue = JobQueue(CoroutineScope(Dispatchers.Default))
        val restarted = Registry(config, restartQueue)
        assertContains(find(restarted, other, "Other"), "class Other")
        waitFor("overlay collected") { overlayFiles().size == 1 }
        assertEquals(1, restartQueue.snapshot().fast.done, "the collection only: the live overlay was reused, not rebuilt")
    }

    @Test
    fun `warm queries and the first query after a restart in an unchanged worktree start no git process`() {
        write(feature, BETA, "package demo\n\nclass Beta\n")
        val first = Registry(config, queue)
        assertContains(find(first, feature, "Beta"), "class Beta")
        val warm = Timings.gitSpawns()
        repeat(3) { assertContains(find(first, feature, "Beta"), "class Beta") }
        assertEquals(warm, Timings.gitSpawns(), "warm queries")
        first.close()

        val restarted = Registry(config, JobQueue(CoroutineScope(Dispatchers.Default)))
        assertContains(find(restarted, feature, "Beta"), "class Beta")
        write(feature, BETA, "package demo\n\nclass Gamma\n")
        assertContains(find(restarted, feature, "Gamma"), "class Gamma")
        assertNone(find(restarted, feature, "Beta"), "replaced class")
        assertEquals(warm, Timings.gitSpawns(), "first query after the restart, then an edit")
        restarted.close()

        // A snapshot that does not fit the overlay file is not trusted: git settles the worktree again.
        overlayFiles().forEach(Files::delete)
        val again = Registry(config, JobQueue(CoroutineScope(Dispatchers.Default)))
        assertContains(find(again, feature, "Gamma"), "class Gamma")
        assertTrue(Timings.gitSpawns() > warm, "reconciled with git")
    }

    @Test
    fun `new ignore rules apply on the next query, also in a nested ignore file written while the daemon was down`() {
        write(feature, BETA, "package demo\n\nclass Beta\n")
        write(feature, DELTA, "package demo\n\nclass Delta\n")
        val registry = Registry(config, queue)
        assertContains(find(registry, feature, "Beta"), "class Beta")
        write(feature, ".gitignore", "build/\n*.gen.kt\nBeta.kt\n")
        assertNone(find(registry, feature, "Beta"), "ignored while running")
        assertContains(find(registry, feature, "Delta"), "class Delta")
        registry.close()

        write(feature, "src/main/kotlin/demo/.gitignore", "Delta.kt\n")
        val restarted = Registry(config, JobQueue(CoroutineScope(Dispatchers.Default)))
        assertNone(find(restarted, feature, "Delta"), "ignored while the daemon was down")
        assertNone(find(restarted, feature, "Beta"), "still ignored")
    }

    @Test
    fun `a global excludes file that changes applies on the next query, also one edited while the daemon was down`() {
        val excludes = TestRepos.tmpDir("excludes").resolve("ignore")
        excludes.writeText("Epsilon.kt\n")
        git(repo, "config", "core.excludesFile", excludes.toString().replace('\\', '/'))
        val registry = Registry(config, queue)
        write(feature, EPSILON, "package demo\n\nclass Epsilon\n")
        assertNone(find(registry, feature, "Epsilon"), "ignored by the global file")

        excludes.writeText("")
        assertContains(find(registry, feature, "Epsilon"), "class Epsilon")
        excludes.writeText("# off\nEpsilon.kt\n")
        assertNone(find(registry, feature, "Epsilon"), "ignored again")
        registry.close()

        excludes.writeText("Other.kt\n")
        val restarted = Registry(config, JobQueue(CoroutineScope(Dispatchers.Default)))
        assertContains(find(restarted, feature, "Epsilon"), "class Epsilon")
    }

    @Test
    fun `git add -f and git rm --cached of a file show in the next query, an index refresh asks git nothing`() {
        val registry = Registry(config, queue)
        write(feature, FORCED, "package demo\n\nclass Forced\n")
        assertNone(find(registry, feature, "Forced"), "ignored")

        git(feature, "add", "-f", FORCED)
        assertContains(find(registry, feature, "Forced"), "class Forced")
        git(feature, "rm", "--cached", "-q", FORCED)
        assertNone(find(registry, feature, "Forced"), "untracked and ignored again")

        // The index is rewritten without a file starting or stopping to be tracked, as an IDE does all day.
        val spawns = Timings.gitSpawns()
        write(feature, ALPHA, alpha("one"))
        git(feature, "status", "--short")
        assertContains(find(registry, feature, "Alpha.one"), "fun one")
        assertEquals(spawns, Timings.gitSpawns(), "index refresh")
    }

    @Test
    fun `an unchanged worktree is answered from the previous base while its overlay is re-derived after a landing`() {
        val registry = Registry(config, queue)
        assertContains(find(registry, feature, "Alpha.one"), "fun one")
        write(repo, ALPHA, alpha("three"))
        commit(repo, "landing")
        assertEquals(0, registry.staleReads())
        // The worktree has not moved: the pair its last check settled is still true of it, and nothing waits for the new one.
        assertContains(find(registry, feature, "Alpha.one"), "fun one")
        assertEquals(1, registry.staleReads(), "answered from the previous base")
        waitFor("the overlay is re-derived against the landing") {
            val before = registry.staleReads()
            find(registry, feature, "Alpha.one")
            registry.staleReads() == before
        }
        assertContains(find(registry, feature, "Alpha.one"), "fun one")
        assertNone(find(registry, feature, "Alpha.three"), "the landing's code is not in the worktree")
        // The default branch's own worktree changed on disk with the landing: it never reads the old pair.
        assertContains(find(registry, repo, "Alpha.three"), "fun three")
    }

    @Test
    fun `an edit of a worktree after a landing is never answered from the previous pair`() {
        val registry = Registry(config, queue)
        assertContains(find(registry, feature, "Alpha.one"), "fun one")
        write(repo, ALPHA, alpha("three"))
        commit(repo, "landing")
        write(feature, ALPHA, alpha("edited"))
        assertContains(find(registry, feature, "Alpha.edited"), "fun edited")
        assertNone(find(registry, feature, "Alpha.one"), "the old body")
        assertEquals(0, registry.staleReads())
    }

    @Test
    fun `a landing reuses the facts the author's overlay parsed instead of parsing the files again`() {
        val registry = Registry(config, queue)
        assertContains(find(registry, repo, "Alpha.one"), "fun one")
        write(feature, ALPHA, alpha("shared"))
        assertContains(find(registry, feature, "Alpha.shared"), "fun shared")
        commit(feature, "feature work")
        git(repo, "merge", "-q", "--ff-only", "feature")
        val parsed = codeloupe.index.Extraction.parsed.get()
        // The default branch moved by the file the feature's overlay holds: the inline sync copies its facts.
        assertContains(find(registry, repo, "Alpha.shared"), "fun shared")
        assertEquals(git(repo, "rev-parse", "HEAD"), registry.snapshot().single().baseCommit, "base synced before the answer")
        assertEquals(parsed, codeloupe.index.Extraction.parsed.get(), "nothing was parsed for the landing")
    }

    private fun overlayFiles(): List<Path> = Files.walk(config.home).use { paths ->
        paths.filter { it.parent.fileName.toString() == "overlays" && it.toString().endsWith(".db") }.toList()
    }

    private fun find(registry: Registry, root: Path, q: String): String =
        runBlocking { registry.query(root.toString()) { FindQuery.run(it, FindQuery.Args(q)) } }

    private fun assertNone(answer: String, what: String) = assertTrue(answer.startsWith("no declaration"), "$what: $answer")

    private fun worktree(name: String): Path = TestRepos.tmpDir("wt").resolve(name).also { git(repo, "worktree", "add", "-q", "-b", name, it.toString()) }

    private fun write(root: Path, path: String, text: String) {
        root.resolve(path).also { it.parent.createDirectories() }.writeText(text)
    }

    private fun commit(root: Path, message: String) {
        git(root, "add", "-A")
        git(root, "commit", "-q", "-m", message)
    }

    private fun waitFor(what: String, condition: () -> Boolean) {
        val until = System.currentTimeMillis() + 60_000
        while (!condition()) {
            check(System.currentTimeMillis() < until) { "timed out waiting: $what" }
            Thread.sleep(50)
        }
    }

    /** The real build worker, counted; base syncs wait for [gate], overlay refreshes for [overlayGate]. */
    private class CountingLauncher(
        private val gate: CompletableDeferred<Unit>? = null,
        private val overlayGate: CompletableDeferred<Unit>? = null,
    ) : BuildLauncher(512, 120_000) {
        val syncs = AtomicInteger()
        val overlays = AtomicInteger()

        override fun update(commonDir: String, commit: String?, dbFile: Path, update: StoreUpdate, workDir: Path): BuildResult {
            if (commit == null) {
                overlays.incrementAndGet()
                overlayGate?.let { runBlocking { it.await() } }
            } else {
                syncs.incrementAndGet()
                gate?.let { runBlocking { it.await() } }
            }
            return super.update(commonDir, commit, dbFile, update, workDir)
        }
    }

    private companion object {
        const val ALPHA = "src/main/kotlin/demo/Alpha.kt"
        const val BETA = "src/main/kotlin/demo/Beta.kt"
        const val DELTA = "src/main/kotlin/demo/Delta.kt"
        const val EPSILON = "src/main/kotlin/demo/Epsilon.kt"
        const val FORCED = "src/main/kotlin/demo/Forced.gen.kt"
        const val GONE = "src/main/kotlin/demo/Gone.kt"
        const val GENERATED = "src/main/kotlin/many/Generated.kt"
        const val BOM = "﻿"
        const val LF = "\n"
        const val CRLF = "\r\n"

        fun alpha(member: String) = "package demo\n\nclass Alpha {\n    fun $member() = 1\n}\n"
    }
}
