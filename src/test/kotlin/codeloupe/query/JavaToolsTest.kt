package codeloupe.query

import codeloupe.TestRepos
import codeloupe.TestRepos.git
import codeloupe.config.Config
import codeloupe.daemon.JobQueue
import codeloupe.index.BaseBuilder
import codeloupe.repo.Registry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `grep`, the repository map and worktree overlays over Java sources. */
class JavaToolsTest {
    private val sample = TestRepos.fixtureRepo("java/sample")
    private val sampleBase = TestRepos.tmpDir("java-tools").resolve("base.db").also { BaseBuilder.build(sample.toString(), git(sample, "rev-parse", "HEAD"), it) }
    private val usages = TestRepos.fixtureRepo("java/usages")
    private val usagesBase = TestRepos.tmpDir("java-tools").resolve("base.db").also { BaseBuilder.build(usages.toString(), git(usages, "rev-parse", "HEAD"), it) }

    @Test
    fun `grep groups hits under the Java declaration that encloses them`() = View(sampleBase).use { view ->
        val out = GrepQuery.run(view, GrepQuery.Args("\"audit\""))
        assertContains(out, "1 hit in 1 file")
        assertContains(out, "[OrderService.Audit] void record()\n    154 log(\"audit\");")
        assertContains(GrepQuery.run(view, GrepQuery.Args("@SafeVarargs")), "[OrderService] static <T extends Comparable<T>> T biggest(…)")
        assertContains(GrepQuery.run(view, GrepQuery.Args("com.example.util")), "(file level)")
    }

    @Test
    fun `the repository map ranks Java files by what refers to their types`() = View(usagesBase).use { view ->
        val out = RepoMap.run(view, RepoMap.Args(emptyList(), 1500, null))
        val files = out.lines().filter { it.startsWith("src/") }.map { it.substringAfterLast('/') }
        assertTrue(files.indexOf("Account.java") in 0..2, "the most referenced type is near the top: $files")
        assertContains(out, "public class Account")
        assertContains(RepoMap.run(view, RepoMap.Args(listOf("Accounts"), 1500, null)).lines().first(), "focus")
    }

    @Test
    fun `a worktree edit, a new file and a deleted file of Java sources show in the next query`() {
        val feature = TestRepos.tmpDir("wt").resolve("feature").also { git(usages, "worktree", "add", "-q", "-b", "feature", it.toString()) }
        val config = Config(TestRepos.tmpDir("home"), 0, 60_000, buildTimeoutMs = 120_000, buildHeapMb = 512, defaultRoot = null, overlayCheckMs = 0)
        val registry = Registry(config, JobQueue(CoroutineScope(Dispatchers.Default)))
        fun find(root: Path, q: String) = runBlocking { registry.query(root.toString()) { FindQuery.run(it, FindQuery.Args(q)) } }

        assertContains(find(feature, "Account.describe"), "public String describe()")
        val account = "src/main/java/com/example/model/Account.java"
        write(feature, account, Files.readString(usages.resolve(account)).replace("public String describe() {", "public String describe2() {"))
        write(feature, "src/main/java/com/example/model/Fresh.java", "package com.example.model;\n\npublic class Fresh {\n    public int hello() {\n        return 1;\n    }\n}\n")
        Files.delete(feature.resolve("src/main/java/com/example/model/Shape.java"))

        assertContains(find(feature, "Account.describe2"), "public String describe2()")
        assertTrue(find(feature, "Account.describe").startsWith("no declaration"), "the base copy of an edited file is masked")
        assertContains(find(feature, "Fresh"), "public class Fresh")
        assertFalse("class Shape" in find(feature, "Shape"), "a deleted file")
        assertContains(find(usages, "Account.describe"), "public String describe()")
        assertTrue(find(usages, "Fresh").startsWith("no declaration"), "the other worktree has not seen the file")
        assertEquals(1, registry.snapshot().single().overlays)
    }

    private fun write(root: Path, path: String, text: String) {
        root.resolve(path).also { it.parent.createDirectories() }.writeText(text)
    }
}
