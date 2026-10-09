package codeloupe.changes

import codeloupe.TestRepos
import codeloupe.TestRepos.git
import codeloupe.config.Config
import codeloupe.daemon.JobQueue
import codeloupe.repo.Registry
import codeloupe.tools.ChangesTool
import codeloupe.tools.ToolArgs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** The Gradle task named for a test source set must be one Gradle has. */
class TestTaskNamesTest {
    private val repo = TestRepos.fixtureRepo(
        "kotlin/sample",
        mapOf(
            "web/src/main/kotlin/web/Shipping.kt" to "package web\n\nclass Shipping {\n    fun label(): String = \"x\"\n}\n",
            "web/src/test/kotlin/web/ShippingTest.kt" to "package web\n\nclass ShippingTest {\n    @Test fun labels() = Shipping().label()\n}\n",
            "web/src/integrationTest/kotlin/web/Harness.kt" to "package web\n\nclass Harness {\n    fun start(): Int = 1\n}\n",
        ),
    )
    private val feature = TestRepos.tmpDir("wt").resolve("feature").also { git(repo, "worktree", "add", "-q", "-b", "feature", it.toString()) }
    private val config = Config(TestRepos.tmpDir("home"), 0, 60_000, buildTimeoutMs = 120_000, buildHeapMb = 512, defaultRoot = null, overlayCheckMs = 0)
    private val registry = Registry(config, JobQueue(CoroutineScope(Dispatchers.Default)))

    private fun tests(): String = runBlocking {
        ChangesTool.answer(registry, feature.toString(), ToolArgs(buildJsonObject { put("tests", JsonPrimitive(true)) }))
    }

    private fun write(path: String, text: String) {
        feature.resolve(path).also { it.parent.createDirectories() }.writeText(text)
    }

    @Test
    fun `source sets without a task of their own run through the tasks Gradle has`() {
        assertEquals("test", TestClass.taskOf("test"))
        assertEquals("test", TestClass.taskOf("testFixtures"))
        assertEquals("allTests", TestClass.taskOf("commonTest"))
        assertEquals("integrationTest", TestClass.taskOf("integrationTest"))
        assertEquals("jvmTest", TestClass.taskOf("jvmTest"))
    }

    @Test
    fun `a changed resource of the test fixtures runs the tests of the module`() {
        write("web/src/testFixtures/resources/data.json", "{}\n")
        val text = tests()
        assertContains(text, "./gradlew :web:test")
        assertFalse(":web:testFixtures" in text, text)
    }

    @Test
    fun `a declaration of the integration tests that no test reaches runs that source set, not the unit tests`() {
        write("web/src/integrationTest/kotlin/web/Harness.kt", "package web\n\nclass Harness {\n    fun start(): Int = 2\n}\n")
        val text = tests()
        assertContains(text, ":web:integrationTest whole module")
        assertFalse(":web:test " in text, text)
    }
}
