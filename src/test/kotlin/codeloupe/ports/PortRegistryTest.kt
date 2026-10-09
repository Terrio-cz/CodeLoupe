package codeloupe.ports

import codeloupe.TestRepos
import codeloupe.config.PortsConfig
import codeloupe.docker.OwnershipClass
import codeloupe.docker.ResourceEntry
import codeloupe.docker.ResourceKind
import codeloupe.workspace.WorkspaceRef
import kotlinx.coroutines.runBlocking
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue


class PortRegistryTest {
    private class FakeProbe(val busy: MutableSet<Int> = HashSet(), val listening: MutableMap<Int, Listener> = HashMap()) : PortProbe {
        override fun inUse(port: Int) = port in busy || port in listening
        override fun listeners() = listening.toMap()
    }

    private val file = TestRepos.tmpDir("ports").resolve("ports.json")
    private val ter5 = WorkspaceRef("Terrio", "TER-5")
    private val ter6 = WorkspaceRef("Terrio", "TER-6")

    private fun container(name: String, workspace: String?, vararg ports: Int) = ResourceEntry(
        ResourceKind.CONTAINER, "id-$name", listOf(name), if (workspace == null) OwnershipClass.UNOWNED else OwnershipClass.OWNED, workspace?.let { "Terrio" }, workspace,
        null, null, null, "running", null, null, ports.toList(),
    )

    private fun registry(range: IntRange?, probe: PortProbe, boxes: List<ResourceEntry>? = emptyList()) =
        PortRegistry(PortsConfig(range), PortStore(file), probe, { boxes })

    @Test
    fun `a name gets its port once, other names and workspaces get other ports, and the file keeps them`() = runBlocking {
        val registry = registry(19000..19010, FakeProbe())
        val app = registry.allocate(ter5, "app")
        assertEquals(19000, app.port)
        assertEquals(app, registry.allocate(ter5, "APP"))
        val db = registry.allocate(ter5, "postgres")
        val other = registry.allocate(ter6, "app")
        assertEquals(listOf(19000, 19001, 19002), listOf(app.port, db.port, other.port))
        // A new registry over the same file (daemon restart) answers the same.
        assertEquals(app.port, registry(19000..19010, FakeProbe()).allocate(ter5, "app").port)
        assertEquals(1, registry.free(ter5, "app"))
        assertEquals(2, registry.free(ter6) + registry.free(ter5))
        assertEquals(0, registry.allocated)
    }

    @Test
    fun `an allocation skips a live listener, a port the Docker publishes and a recorded one`() = runBlocking {
        val probe = FakeProbe(busy = mutableSetOf(19000, 19002))
        val registry = PortRegistry(PortsConfig(19000..19010), PortStore(file), probe, { listOf(container("web", "TER-9", 19001)) })
        assertEquals(19003, registry.allocate(ter5, "app").port)
        assertEquals(19004, registry.allocate(ter6, "app").port)
    }

    // Four consecutive ports nothing holds (the ephemeral range around a random port is full of client sockets).
    private fun freeBlock(): Int = (21_000..29_000 step 8).first { start ->
        runCatching { (start..start + 3).map { ServerSocket(it) }.forEach { it.close() } }.isSuccess
    }

    @Test
    fun `a real listener in the range is never handed out`() = runBlocking {
        val start = freeBlock()
        ServerSocket(start).use {
            val registry = registry(start..start + 3, LocalPorts())
            val port = registry.allocate(ter5, "app").port
            assertNotEquals(start, port)
            assertTrue(port in start..start + 3)
        }
    }

    @Test
    fun `no range, or a used up one, is an error with the reason`() {
        assertTrue(assertFailsWith<IllegalStateException> { runBlocking { registry(null, FakeProbe()).allocate(ter5, "app") } }.message!!.contains("workspaces.ports.range"))
        val small = registry(19000..19000, FakeProbe())
        runBlocking { small.allocate(ter5, "app") }
        assertTrue(assertFailsWith<IllegalStateException> { runBlocking { small.allocate(ter6, "app") } }.message!!.contains("no free port"))
    }

    @Test
    fun `a conflict names the owning container or process, the workspace's own are in use, foreign holders in the range are listed`() = runBlocking {
        val probe = FakeProbe()
        val boxes = mutableListOf<ResourceEntry>()
        val registry = PortRegistry(PortsConfig(19000..19010), PortStore(file), probe, { boxes })
        val app = registry.allocate(ter5, "app")
        val db = registry.allocate(ter5, "postgres")
        val web = registry.allocate(ter5, "web")
        val idle = registry.allocate(ter5, "idle")
        assertEquals(listOf(19000, 19001, 19002, 19003), listOf(app.port, db.port, web.port, idle.port))

        boxes += container("terrio-ter-5-app-1", "TER-5", 19000)
        boxes += container("unrelated-db", null, 19001)
        probe.listening[19002] = Listener(19002, 4242, "java -jar C:/ws/TER-5/app.jar")
        probe.listening[19004] = Listener(19004, 77, "node dev-server.js")
        boxes += container("unrelated-cache", null, 19005)

        val report = registry.status()
        val byPort = report.allocations.associateBy { it.allocation.port }
        assertEquals(PortState.IN_USE, byPort.getValue(19000).state)
        assertEquals(PortState.CONFLICT, byPort.getValue(19001).state)
        assertTrue(byPort.getValue(19001).usedBy!!.contains("unrelated-db"), byPort.getValue(19001).usedBy)
        assertEquals(PortState.IN_USE, byPort.getValue(19002).state, "its command line names the workspace")
        assertEquals(PortState.FREE, byPort.getValue(19003).state)
        assertEquals(null, byPort.getValue(19003).usedBy)
        assertEquals(listOf(19004, 19005), report.foreign.map { it.port })
        assertTrue(report.foreign[0].usedBy.contains("pid 77"))
        assertTrue(report.foreign[1].usedBy.contains("unrelated-cache"))

        probe.listening[19003] = Listener(19003, 9, "python other.py")
        val owned = registry.status().allocations.first { it.allocation.port == 19003 }
        assertEquals(PortState.CONFLICT, owned.state)
        assertTrue(owned.usedBy!!.contains("pid 9") && owned.usedBy!!.contains("python"))
    }

    @Test
    fun `a real process on an allocated port is a conflict`() = runBlocking {
        val free = freeBlock()
        val registry = registry(free..free, LocalPorts())
        assertEquals(free, registry.allocate(ter5, "app").port)
        assertEquals(PortState.FREE, registry.status().allocations.single().state)
        ServerSocket(free).use {
            val status = registry.status().allocations.single()
            assertEquals(PortState.CONFLICT, status.state)
            assertTrue(status.usedBy != null)
            LocalPorts().listeners()[free]?.pid?.let { pid -> assertTrue(status.usedBy!!.contains("pid $pid"), status.usedBy) }
        }
    }

    @Test
    fun `Docker not answering is reported and the rest still works`() = runBlocking {
        val registry = registry(19000..19002, FakeProbe(), boxes = null)
        registry.allocate(ter5, "app")
        val report = registry.status()
        assertEquals(1, report.allocations.size)
        assertTrue(report.problems.single().contains("Docker"))
    }
}
