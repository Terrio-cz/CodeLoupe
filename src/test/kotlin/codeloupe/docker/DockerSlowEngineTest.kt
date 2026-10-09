package codeloupe.docker

import kotlinx.coroutines.runBlocking
import codeloupe.workspace.WorkspaceList
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DockerSlowEngineTest {
    private val healthy = "/version" to (200 to """{"Version":"27.0"}""")

    @Test
    fun `an engine that never answers fails the request at the deadline instead of holding the caller`() {
        FakeDockerEngine { _, _ -> null }.use { engine ->
            val started = System.nanoTime()
            val failure = assertFailsWith<DockerUnavailable> { DockerHttp(engine.endpoint, defaultTimeoutMs = 400).request("GET", "/containers/json?all=1") }
            assertTrue(failure.message.orEmpty().contains("did not answer"), failure.message)
            assertTrue(Duration.ofNanos(System.nanoTime() - started) < Duration.ofSeconds(10), "the request held the caller")
        }
    }

    @Test
    fun `a listing that fails after the engine answered is a report without an engine, not an empty engine`() = runBlocking {
        FakeDockerEngine { method, path ->
            when {
                path == healthy.first -> healthy.second
                path.startsWith("/containers/json") -> 500 to """{"message":"boom"}"""
                else -> 200 to "[]"
            }
        }.use { engine ->
            val report = ResourceInventory(emptyList(), { WorkspaceList("now", emptyList()) }, { engine.api }).report()
            assertNull(report.engine, "callers read an engine as proof that these resources are all there are")
            assertTrue(report.problems.single().contains("500"), report.problems.toString())
            assertEquals(emptyList(), report.resources)
        }
    }
}
