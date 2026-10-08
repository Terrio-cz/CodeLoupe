package codeloupe.ports

import codeloupe.config.PortsConfig
import codeloupe.docker.OwnershipClass
import codeloupe.docker.ResourceEntry
import codeloupe.docker.ResourceKind
import codeloupe.platform.IsoTime
import codeloupe.workspace.WorkspaceRef

/**
 * Ports per workspace, drawn from the configured range. A workspace asks for a port by name; the same name gets the same
 * port again. A new port is one nothing listens on, no container publishes and no workspace has recorded, so an
 * allocation never collides with a live listener (it holds nothing open, though: a process that starts later can still
 * take it, and [status] then reports the conflict with its owner).
 *
 * [containers] gives the containers of the local Docker (null when it does not answer), with their published ports.
 */
class PortRegistry(
    private val config: PortsConfig,
    private val store: PortStore,
    private val probe: PortProbe,
    private val containers: suspend () -> List<ResourceEntry>?,
    private val now: () -> String = IsoTime::now,
) {
    val allocated: Int get() = store.all().size

    /** The port of [name] in workspace [ref]: the recorded one, or a new one. [IllegalStateException] when there is no range or it is used up. */
    @Synchronized
    fun allocate(ref: WorkspaceRef, name: String, publishedByDocker: Set<Int>): PortAllocation {
        val range = config.range ?: throw IllegalStateException("no port range configured; set workspaces.ports.range in config.json, e.g. [19000, 19999]")
        require(name.isNotBlank()) { "a port needs a name" }
        store.find(ref.repo, ref.workspace, name)?.let { return it }
        val recorded = store.all().mapTo(HashSet()) { it.port }
        val port = range.firstOrNull { it !in recorded && it !in publishedByDocker && !probe.inUse(it) }
            ?: throw IllegalStateException("no free port left in ${range.first}-${range.last}")
        return PortAllocation(port, ref.repo, ref.workspace, name, now()).also(store::add)
    }

    suspend fun allocate(ref: WorkspaceRef, name: String): PortAllocation =
        allocate(ref, name, containers().orEmpty().flatMapTo(HashSet()) { it.publishedPorts })

    /** Frees the workspace's port [name], or all of them. Answers how many. */
    fun free(ref: WorkspaceRef, name: String? = null): Int = store.free(ref.repo, ref.workspace, name)

    /** Every allocation with what holds it, and the foreign holders of ports in the range. */
    suspend fun status(): PortReport {
        val problems = ArrayList<String>()
        val boxes = containers()
        if (boxes == null) problems += "Docker did not answer; containers are not considered"
        val byPort = HashMap<Int, MutableList<ResourceEntry>>()
        boxes.orEmpty().filter { it.kind == ResourceKind.CONTAINER && it.state == "running" }.forEach { c -> c.publishedPorts.forEach { byPort.getOrPut(it) { ArrayList() } += c } }
        val listeners = probe.listeners()
        val allocations = store.all().map { a -> PortStatus(a, state(a, byPort[a.port].orEmpty(), listeners[a.port], probe), usedBy(a.port, byPort[a.port].orEmpty(), listeners[a.port])) }
            .map { if (it.state == PortState.FREE) it.copy(usedBy = null) else it }
        val recorded = store.all().mapTo(HashSet()) { it.port }
        val foreign = config.range?.let { range ->
            (byPort.keys + listeners.keys).filter { it in range && it !in recorded }.sorted().map { ForeignPort(it, usedBy(it, byPort[it].orEmpty(), listeners[it]) ?: "?") }
        }.orEmpty()
        return PortReport(now(), config.range?.let { "${it.first}-${it.last}" }, allocations, foreign, problems)
    }

    private fun state(a: PortAllocation, boxes: List<ResourceEntry>, listener: Listener?, probe: PortProbe): PortState = when {
        boxes.any { ownedBy(it, a) } -> PortState.IN_USE
        boxes.isNotEmpty() -> PortState.CONFLICT
        listener != null -> if (listener.process?.contains(a.workspace, ignoreCase = true) == true) PortState.IN_USE else PortState.CONFLICT
        // Held but not listed (the OS tool is missing or hides the owner): something is there, and it is not known to be the workspace's.
        probe.inUse(a.port) -> PortState.CONFLICT
        else -> PortState.FREE
    }

    private fun ownedBy(c: ResourceEntry, a: PortAllocation) =
        c.ownership != OwnershipClass.UNOWNED && c.repo.equals(a.repo, ignoreCase = true) && c.workspace.equals(a.workspace, ignoreCase = true)

    private fun usedBy(port: Int, boxes: List<ResourceEntry>, listener: Listener?): String? = when {
        boxes.isNotEmpty() -> boxes.joinToString("; ") { c -> "container ${c.names.firstOrNull() ?: c.id} (${c.workspace?.let { "workspace $it" } ?: "no workspace"})" }
        listener != null -> "process " + listOfNotNull(listener.pid?.let { "pid $it" }, listener.process?.take(160)).joinToString(" ").ifEmpty { "on port $port" }
        else -> if (probe.inUse(port)) "an unknown process" else null
    }
}
